#Requires -Version 5.1
<#
.SYNOPSIS
  Do Spec 8 phone QA runner for Tim's Windows 11 PC (PowerShell 5.1).

.DESCRIPTION
  Clones or fetches tdrevans-aus/do-app, checks out -Ref (default
  do/spec-8-quiet-hours-and-repeating-cap), builds the USE debug APK, and runs
  the Spec 8 acceptance tests the debug broadcasts can reach.
  Evidence goes to C:\Users\tdrev\do-qa\qa-evidence\Do-spec08-<stamp>\.
  Results are written to C:\Users\tdrev\do-qa\results-spec-08.md.

  Fires must land 0 to 60 seconds after the scheduled time. Early is FAIL.
  Titles are T_s8_<name>_<runstamp>. Windows are set from the phone's current
  minute. adb calls time out at 90 seconds, install at 180.
  Files move through /data/local/tmp via the shared QA lib.

  The finally block marks leftover T_s8 rows Repeat Off then Done, puts quiet
  hours back to off / 22:00 / 07:00 / followDnd=false / criticalPauses=false /
  simDnd=real, puts Do Not Disturb back only when this run turned it on from
  ALL, restores snooze options and the fake unlock, and restores the Sleeping
  rule only if this run snoozed it. This script never sets a PIN or a lock
  screen, never changes the clock or time zone, and never force-stops.

.PARAMETER DryRun
  Print the plan without changing the phone.

.PARAMETER Only
  Optional test ID filter, e.g. -Only AT-02,AT-14

.PARAMETER Ref
  Git commit, short SHA, or branch. Default is the Spec 8 branch.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string[]]$Only,
    [string]$Ref = 'do/spec-8-quiet-hours-and-repeating-cap'
)

$ErrorActionPreference = 'Stop'

$RepoDir = 'C:\Users\tdrev\do-qa\repo'
$RepoUrl = 'https://github.com/tdrevans-aus/do-app.git'
$ExpectedSha = $Ref
$AdbPath = 'C:\Users\tdrev\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$AndroidHome = 'C:\Users\tdrev\AppData\Local\Android\Sdk'
$Serial = 'R5CXC0FWWVN'
$Pkg = 'com.evansappstudio.doapp'
$Recv = 'com.evansappstudio.doapp/.debug.DebugReceiver'
$AddAction = 'com.evansappstudio.doapp.debug.ADD'
$DoneAction = 'com.evansappstudio.doapp.debug.DONE'
$SnoozeAction = 'com.evansappstudio.doapp.debug.SNOOZE'
$SetRepeatAction = 'com.evansappstudio.doapp.debug.SET_REPEAT'
$DumpRepeatAction = 'com.evansappstudio.doapp.debug.DUMP_REPEAT'
$FakeAction = 'com.evansappstudio.doapp.debug.FAKE_PURCHASE'
$DumpUnlockAction = 'com.evansappstudio.doapp.debug.DUMP_UNLOCK'
$ExportAction = 'com.evansappstudio.doapp.debug.EXPORT'
$ImportAction = 'com.evansappstudio.doapp.debug.IMPORT'
$ConfigSnoozeAction = 'com.evansappstudio.doapp.debug.CONFIG_SNOOZE'
$ConfigParseAction = 'com.evansappstudio.doapp.debug.CONFIG_PARSE'
$SetQuietAction = 'com.evansappstudio.doapp.debug.SET_QUIET_HOURS'
$DumpQuietAction = 'com.evansappstudio.doapp.debug.DUMP_QUIET'
$ResultsPath = 'C:\Users\tdrev\do-qa\results-spec-08.md'
$PhoneTolMs = 60000
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:RunStamp = $Stamp

function Normalize-TestId {
    [CmdletBinding()]
    param([string]$Id)
    $t = $Id.Trim().ToUpper()
    if ($t -like 'DO-*') { return $t }
    if ($t -match '^(AT|RP)-') { return "DO-$t" }
    return $t
}

$OnlySet = @()
if ($Only) {
    foreach ($item in $Only) {
        foreach ($part in ($item -split ',')) {
            if ($part.Trim()) { $OnlySet += (Normalize-TestId -Id $part) }
        }
    }
}

function Should-Run {
    [CmdletBinding()]
    param([string]$Id)
    if ($OnlySet.Count -eq 0) { return $true }
    return ($OnlySet -contains (Normalize-TestId -Id $Id))
}

$script:EvidenceDir = $null
$script:LogcatProc = $null
$script:LogcatFile = $null
$script:Results = New-Object System.Collections.Generic.List[object]
$script:CreatedIds = New-Object System.Collections.Generic.List[int64]
$script:Adb = $AdbPath
$script:SleepingSnoozed = $false
$script:SnoozeChanged = $false
$script:SnoozeSnapshot = @()
$script:DndPath = 'sim'
$script:DndNote = ''
$script:RealDndTouched = $false
$script:ParseTouched = $false
$script:CapReady = $false

function Write-Log {
    [CmdletBinding()]
    param([string]$Message)
    $line = '[Do-QA {0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message
    Write-Host $line
    if ($script:EvidenceDir) {
        if (-not (Test-Path $script:EvidenceDir)) { New-Item -ItemType Directory -Path $script:EvidenceDir -Force | Out-Null }
        $logPath = Join-Path $script:EvidenceDir 'Do-runner.log'
        $enc = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::AppendAllText($logPath, ($line + [Environment]::NewLine), $enc)
    }
}

# Dot-source at script scope. A function that dotsources the lib defines those
# functions in the function's scope, and they vanish when it returns.
$QaSharedLib = $null
$QaSharedCandidates = @(
    (Join-Path $PSScriptRoot 'lib\qa-runner-shared.ps1'),
    (Join-Path $PSScriptRoot 'qa\lib\qa-runner-shared.ps1'),
    (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib\qa-runner-shared.ps1')
)
foreach ($c in $QaSharedCandidates) {
    if ($c -and (Test-Path -LiteralPath $c)) { $QaSharedLib = $c; break }
}
if (-not $QaSharedLib) {
    throw "Missing lib/qa-runner-shared.ps1 (looked in: $($QaSharedCandidates -join '; ')). Copy the qa/lib folder next to this runner."
}
. $QaSharedLib
Test-QaSharedLibInScope | Out-Null

function Invoke-Adb {
    [CmdletBinding()]
    param(
        [string[]]$AdbArgs,
        [switch]$AllowFail,
        [int]$TimeoutSec = 90
    )
    $all = @('-s', $Serial) + @($AdbArgs)
    Write-Log ("adb " + ($all -join ' '))
    if ($DryRun) { return @{ ExitCode = 0; Output = ''; Error = ''; TimedOut = $false } }
    $r = Invoke-QaAdbTimed -Adb $script:Adb -ArgumentList $all -TimeoutSec $TimeoutSec
    if ($r.TimedOut) { throw ("adb timed out after {0}s: {1}" -f $TimeoutSec, ($all -join ' ')) }
    if (-not $AllowFail -and $r.ExitCode -ne 0) { throw ("adb exited {0}: {1}" -f $r.ExitCode, $r.Output) }
    return $r
}

function Invoke-AdbShell {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [switch]$AllowFail,
        [int]$TimeoutSec = 90
    )
    Invoke-Adb -AdbArgs @('shell', $Command) -AllowFail:$AllowFail -TimeoutSec $TimeoutSec
}

function Save-Text {
    [CmdletBinding()]
    param([string]$Name, [string]$Text)
    if (-not $script:EvidenceDir) { return }
    $path = Join-Path $script:EvidenceDir $Name
    if ($null -eq $Text) { $Text = '' }
    $enc = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($path, $Text, $enc)
}

function Record-Result {
    [CmdletBinding()]
    param(
        [string]$Id,
        [ValidateSet('PASS','FAIL','RECORD','MANUAL','SKIP','NOT RUN')]
        [string]$Status,
        [string]$Detail
    )
    $nid = Normalize-TestId -Id $Id
    Write-Log ("{0}  {1}  {2}" -f $nid, $Status, $Detail)
    $script:Results.Add([pscustomobject]@{ Id = $nid; Status = $Status; Detail = $Detail })
}

function Find-JavaHome {
    [CmdletBinding()]
    param()
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $globs = @(
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        'C:\Program Files\Microsoft\jdk-17*',
        'C:\Program Files\Java\jdk-17*',
        'C:\Program Files\Android\Android Studio\jbr'
    )
    foreach ($g in $globs) {
        $hits = @(Get-Item -Path $g -ErrorAction SilentlyContinue)
        if ($hits.Count -gt 0) { return $hits[0].FullName }
    }
    return $null
}

function Invoke-Git {
    [CmdletBinding()]
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    $ErrorActionPreference = 'Continue'
    & git @GitArgs 2>&1 | ForEach-Object { Write-Host "$_" }
}

function Invoke-Git-Out {
    [CmdletBinding()]
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    $ErrorActionPreference = 'Continue'
    $o = & git @GitArgs 2>$null
    return $o
}

function Ensure-Checkout {
    [CmdletBinding()]
    param()
    if ($DryRun) {
        Write-Log "DryRun: would clone/fetch $RepoUrl into $RepoDir and checkout $ExpectedSha"
        return
    }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'git is not on PATH.' }
    if (-not (Test-Path (Join-Path $RepoDir '.git'))) {
        Write-Log "Cloning $RepoUrl into $RepoDir"
        Invoke-Git -GitArgs @('clone', $RepoUrl, $RepoDir)
        if ($LASTEXITCODE -ne 0) { throw "git clone failed ($LASTEXITCODE)" }
    }
    Push-Location $RepoDir
    try {
        Invoke-Git -GitArgs @('fetch', 'origin', $ExpectedSha)
        if ($LASTEXITCODE -ne 0) { throw "git fetch failed ($LASTEXITCODE)" }
        Invoke-Git -GitArgs @('checkout', '--detach', 'FETCH_HEAD')
        if ($LASTEXITCODE -ne 0) { throw "git checkout $ExpectedSha failed ($LASTEXITCODE)" }
        $head = (Invoke-Git-Out -GitArgs @('rev-parse', 'HEAD')).Trim()
        Write-Log ("Checkout OK: {0} (requested -Ref {1})" -f $head, $ExpectedSha)
    } finally {
        Pop-Location
    }
}

function Invoke-BuildAndInstall {
    [CmdletBinding()]
    param()
    if ($DryRun) {
        Write-Log 'DryRun: would run gradlew.bat assembleDebug and adb install -r'
        return
    }
    $javaHome = Find-JavaHome
    if ($javaHome) { $env:JAVA_HOME = $javaHome; Write-Log "JAVA_HOME=$javaHome" }
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_SDK_ROOT = $AndroidHome
    $wrapper = Join-Path $RepoDir 'gradlew.bat'
    if (-not (Test-Path $wrapper)) { throw "gradlew.bat not found in $RepoDir" }
    Push-Location $RepoDir
    try {
        Write-Log 'Running gradlew.bat assembleDebug'
        $ErrorActionPreference = 'Continue'
        & cmd.exe /c "`"$wrapper`" assembleDebug --no-daemon 2>&1" | ForEach-Object { Write-Host "$_" }
        if ($LASTEXITCODE -ne 0) { throw "gradlew assembleDebug failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
    $apk = Join-Path $RepoDir 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw "Debug APK not found at $apk" }
    Copy-Item $apk (Join-Path $script:EvidenceDir 'app-debug.apk') -Force
    Uninstall-DoAppIfDowngrade -RepoDir $RepoDir -Package $Pkg | Out-Null
    Invoke-Adb -AdbArgs @('install', '-r', $apk) -TimeoutSec 180 | Out-Null
    Invoke-AdbShell -Command "pm grant $Pkg android.permission.POST_NOTIFICATIONS" -AllowFail | Out-Null
    Invoke-AdbShell -Command "am start -n $Pkg/.MainActivity" -AllowFail | Out-Null
    Start-Sleep -Seconds 3
}

function Start-DoLogcat {
    [CmdletBinding()]
    param()
    if ($DryRun) { return }
    Invoke-Adb -AllowFail -AdbArgs @('logcat', '-c') | Out-Null
    $script:LogcatFile = Join-Path $script:EvidenceDir 'Do-logcat-DoAlarm.txt'
    $script:LogcatProc = Start-Process -FilePath $script:Adb -ArgumentList @('-s', $Serial, 'logcat', '-v', 'threadtime', '-s', 'DoAlarm:V') -RedirectStandardOutput $script:LogcatFile -RedirectStandardError (Join-Path $script:EvidenceDir 'Do-logcat-DoAlarm.err.txt') -PassThru -WindowStyle Hidden
    Write-Log "logcat pid $($script:LogcatProc.Id)"
}

function Stop-DoLogcat {
    [CmdletBinding()]
    param()
    if ($script:LogcatProc) { Stop-QaProcessTree -Process $script:LogcatProc }
    $script:LogcatProc = $null
}

function Get-LogcatText {
    [CmdletBinding()]
    param()
    if ($script:LogcatFile -and (Test-Path $script:LogcatFile)) {
        return [System.IO.File]::ReadAllText($script:LogcatFile)
    }
    return ''
}

function Get-LogLen {
    [CmdletBinding()]
    param()
    $text = Get-LogcatText
    if ($text) { return $text.Length }
    return 0
}

function Wait-ForMatch {
    [CmdletBinding()]
    param([string]$Pattern, [int]$BeforeLen, [int]$WaitSeconds = 20)
    if ($DryRun) { return $null }
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $text = Get-LogcatText
        if ($text -and $text.Length -ge $BeforeLen) {
            $slice = $text.Substring($BeforeLen)
            $m = [regex]::Match($slice, $Pattern)
            if ($m.Success) { return $m }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Send-Broadcast {
    [CmdletBinding()]
    param([string]$Command)
    Invoke-AdbShell -Command $Command -AllowFail | Out-Null
}

function Remember-Id {
    [CmdletBinding()]
    param($Id)
    $value = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    if ($value -gt 0 -and -not $script:CreatedIds.Contains($value)) { $script:CreatedIds.Add($value) }
    return $value
}

function Get-Field {
    [CmdletBinding()]
    param([string]$Dump, [string]$Key)
    if (-not $Dump) { return '' }
    $m = [regex]::Match($Dump, "(?:^|\s)$([regex]::Escape($Key))=([^\s]+)")
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

function Get-PhoneClock {
    [CmdletBinding()]
    param()
    $raw = (Invoke-AdbShell -Command 'date +%s').Output
    $epoch = ConvertTo-Int64Scalar -Value (($raw -split "`n")[0]) -Name 'epoch'
    $hm = ((Invoke-AdbShell -Command 'date +%H:%M').Output -split "`n")[0].Trim()
    $parts = $hm -split ':'
    $minute = ([int]$parts[0] * 60) + [int]$parts[1]
    return @{ EpochSec = $epoch; Minute = $minute; Hm = $hm }
}

function Format-QuietMinute {
    [CmdletBinding()]
    param([int]$Minute)
    $m = $Minute % 1440
    if ($m -lt 0) { $m += 1440 }
    return ('{0:D2}:{1:D2}' -f [int][Math]::Floor($m / 60), ($m % 60))
}

function Get-WindowSpec {
    [CmdletBinding()]
    param([int]$StartDelta, [int]$EndDelta)
    $clock = Get-PhoneClock
    $start = $clock.Minute + $StartDelta
    $end = $clock.Minute + $EndDelta
    return @{
        Start = (Format-QuietMinute -Minute $start)
        End = (Format-QuietMinute -Minute $end)
        NowMinute = $clock.Minute
    }
}

function Set-Quiet {
    [CmdletBinding()]
    param([string]$Extras)
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $SetQuietAction -n $Recv $Extras"
    if ($DryRun) { return 'DoAlarm quiet set' }
    $m = Wait-ForMatch -Pattern 'DoAlarm quiet (set|bad args)[^\r\n]*' -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Get-QuietDump {
    [CmdletBinding()]
    param([int64]$Id = 0)
    $before = Get-LogLen
    $idArg = ''
    if ($Id -gt 0) { $idArg = "--el id $Id" }
    Send-Broadcast -Command "am broadcast -a $DumpQuietAction -n $Recv $idArg"
    if ($DryRun) { return 'DoAlarm quiet enabled=false start=22:00 end=07:00 followDnd=false criticalPauses=false filter=1 filterSource=real dndActive=false windowEnd=none' }
    $m = Wait-ForMatch -Pattern 'DoAlarm quiet enabled=[^\r\n]+' -BeforeLen $before -WaitSeconds 15
    $line = ''
    if ($m) { $line = $m.Value }
    if ($Id -gt 0) {
        $idLine = Wait-ForMatch -Pattern ("DoAlarm quiet id={0}[^\r\n]+" -f $Id) -BeforeLen $before -WaitSeconds 8
        if ($idLine) { $line = $line + "`n" + $idLine.Value }
    }
    return $line
}

function Add-StampedReminder {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [int]$InSeconds = 20,
        [int]$NagMinutes = 1,
        [string]$Alert = '',
        [string]$Unit = '',
        [string]$WeekDays = '',
        [string]$From = '',
        [switch]$Parse,
        [string]$RawTitle = ''
    )
    $title = $RawTitle
    if (-not $title) { $title = ("T_s8_{0}_{1}" -f $Name, $script:RunStamp) }
    $before = Get-LogLen
    $safe = $title.Replace("'", '')
    $extra = "--es title '$safe' --ei inSeconds $InSeconds --ei nagMinutes $NagMinutes"
    if ($Alert) { $extra += " --es alert $Alert" }
    if ($Unit) { $extra += " --es unit $Unit" }
    if ($WeekDays) { $extra += " --es weekDays $WeekDays" }
    if ($From) { $extra += " --es from $From" }
    if ($Parse) { $extra += ' --ez parse true' }
    Send-Broadcast -Command "am broadcast -a $AddAction -n $Recv $extra"
    if ($DryRun) { return @{ Id = 0; DueAt = 0; Title = $title; Blocked = $false; BlockText = '' } }
    $added = Wait-ForMatch -Pattern 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)' -BeforeLen $before -WaitSeconds 20
    $blocked = Wait-ForMatch -Pattern 'DoAlarm unlock blocked (?:active|repeating)=[^\r\n]+' -BeforeLen $before -WaitSeconds 3
    if ($added) {
        $info = @{
            Id = (Remember-Id -Id $added.Groups[1].Value)
            DueAt = [int64]$added.Groups[2].Value
            Title = $title
            Blocked = $false
            BlockText = ''
        }
        Write-Log ("ADD {0} id={1} dueAt={2}" -f $title, $info.Id, $info.DueAt)
        return $info
    }
    $blockText = ''
    if ($blocked) { $blockText = $blocked.Value }
    return @{ Id = 0; DueAt = 0; Title = $title; Blocked = [bool]$blocked; BlockText = $blockText }
}

function Finish-OneReminder {
    [CmdletBinding()]
    param($Id)
    $id = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    if ($id -le 0) { return }
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $id --es unit OFF"
    if (-not $DryRun) { Start-Sleep -Milliseconds 400 }
    Send-Broadcast -Command "am broadcast -a $DoneAction -n $Recv --el id $id"
}

function Finish-TrackedReminders {
    [CmdletBinding()]
    param()
    $ids = @($script:CreatedIds.ToArray())
    foreach ($id in $ids) {
        try { Finish-OneReminder -Id $id } catch { Write-Log ("Done failed for ${id}: " + $_.Exception.Message) }
    }
    Write-Log ("Marked done (Repeat Off first) for ids: " + ($(if ($ids.Count) { $ids -join ',' } else { 'none' })))
    $script:CreatedIds.Clear()
}

function Clear-LeftoverRows {
    [CmdletBinding()]
    param()
    if ($DryRun) { return }
    $query = "SELECT id, title FROM reminder WHERE isDone=0;"
    $listed = Invoke-AdbShell -Command "run-as $Pkg sqlite3 databases/do.db '$query'" -AllowFail
    $text = [string]$listed.Output
    if ($text -notmatch '^\d+\|') {
        Write-Log 'Could not list leftover rows with sqlite3. Cap tests record the starting DUMP_UNLOCK counts.'
        return
    }
    foreach ($line in ($text -split "`n")) {
        if ($line -notmatch '^(\d+)\|(.*)$') { continue }
        $id = [int64]$Matches[1]
        $title = $Matches[2]
        if ($title -like 'T_s4_*' -or $title -like 'T_s8_*' -or $title -like 'T_off*') {
            Write-Log "Leftover done: $id $title"
            Finish-OneReminder -Id $id
        }
    }
}

function Set-FakeState {
    [CmdletBinding()]
    param([string]$State)
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $FakeAction -n $Recv --es state $State"
    if ($DryRun) { return "DoAlarm unlock state=$State source=fake" }
    $m = Wait-ForMatch -Pattern 'DoAlarm unlock state=\S+ source=\S+[^\r\n]*' -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Get-UnlockDump {
    [CmdletBinding()]
    param()
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpUnlockAction -n $Recv"
    if ($DryRun) { return 'DoAlarm unlock state=unlocked source=fake pending=false product=do_unlock price=none active=0 cap=5 repeating=0 repeatCap=2' }
    $m = Wait-ForMatch -Pattern 'DoAlarm unlock state=\S+ source=\S+[^\r\n]*' -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Get-RepeatDump {
    [CmdletBinding()]
    param($Id)
    $id = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpRepeatAction -n $Recv --el id $id"
    if ($DryRun) { return '' }
    $m = Wait-ForMatch -Pattern ("DoAlarm repeat id={0}[^\r\n]+" -f $id) -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Wait-Alert {
    [CmdletBinding()]
    param($Id, [int]$BeforeLen, [int]$WaitSeconds = 70)
    $id = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    $m = Wait-ForMatch -Pattern ("DoAlarm fire id={0} scheduled=(\d+) actual=(\d+)" -f $id) -BeforeLen $BeforeLen -WaitSeconds $WaitSeconds
    if (-not $m) { return $null }
    $sched = [int64]$m.Groups[1].Value
    $act = [int64]$m.Groups[2].Value
    $held = Wait-ForMatch -Pattern ("DoAlarm dnd-hold id={0} scheduled={1} " -f $id, $sched) -BeforeLen $BeforeLen -WaitSeconds 5
    return @{ Scheduled = $sched; Actual = $act; Delta = ($act - $sched); Held = [bool]$held; Text = $m.Value }
}

function Test-OnTime {
    [CmdletBinding()]
    param($Fire)
    if ($null -eq $Fire) { return $false }
    $delta = [int64]$Fire.Delta
    return ($delta -ge 0 -and $delta -le $PhoneTolMs)
}

function Initialize-DndPath {
    [CmdletBinding()]
    param()
    $dump = Get-QuietDump
    $script:FreshQuiet = $dump
    Save-Text -Name 'quiet-at-start.txt' -Text $dump
    $filter = Get-Field -Dump $dump -Key 'filter'
    if ($filter -ne '1') {
        $script:DndPath = 'sim'
        $script:DndNote = 'DND on at start, simulated'
        Write-Log $script:DndNote
        return
    }
    $probe = Invoke-AdbShell -Command 'cmd notification set_dnd on' -AllowFail
    if ($probe.ExitCode -ne 0) {
        $script:DndPath = 'sim'
        $script:DndNote = 'DND real path unavailable'
        Write-Log $script:DndNote
        return
    }
    $script:RealDndTouched = $true
    $ok = $false
    $deadline = (Get-Date).AddSeconds(10)
    do {
        $now = Get-QuietDump
        if ((Get-Field -Dump $now -Key 'filterSource') -eq 'real' -and (Get-Field -Dump $now -Key 'dndActive') -eq 'true') {
            $ok = $true
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Invoke-AdbShell -Command 'cmd notification set_dnd off' -AllowFail | Out-Null
    if (-not $ok) {
        $script:DndPath = 'sim'
        $script:DndNote = 'DND real path unavailable'
    } else {
        $script:DndPath = 'real'
        $script:DndNote = 'DND real path'
    }
    Write-Log $script:DndNote
}

function Set-DndMode {
    [CmdletBinding()]
    param([ValidateSet('on','off')][string]$Mode)
    if ($script:DndPath -eq 'real') {
        $script:RealDndTouched = $true
        Invoke-AdbShell -Command "cmd notification set_dnd $Mode" -AllowFail | Out-Null
        $want = 'false'
        if ($Mode -eq 'on') { $want = 'true' }
        $deadline = (Get-Date).AddSeconds(10)
        do {
            $dump = Get-QuietDump
            if ((Get-Field -Dump $dump -Key 'filterSource') -eq 'real' -and (Get-Field -Dump $dump -Key 'dndActive') -eq $want) {
                return $dump
            }
            Start-Sleep -Milliseconds 400
        } while ((Get-Date) -lt $deadline)
        return (Get-QuietDump)
    }
    $sim = 'off'
    if ($Mode -eq 'on') { $sim = 'on' }
    Set-Quiet -Extras "--es simDnd $sim" | Out-Null
    return (Get-QuietDump)
}

function Snapshot-Snooze {
    [CmdletBinding()]
    param()
    $local = Join-Path $script:EvidenceDir 'do_settings-before.xml'
    try {
        Copy-QaRunAsFileToLocal -Adb $script:Adb -Serial $Serial -Package $Pkg -RemoteRelative 'shared_prefs/do_settings.xml' -LocalPath $local -TimeoutSec 90
    } catch {
        Write-Log ("Could not snapshot snooze settings: " + $_.Exception.Message)
        return
    }
    $text = [System.IO.File]::ReadAllText($local)
    $script:SnoozeSnapshot = @()
    foreach ($index in 0, 1, 2) {
        $kind = 'FROM_NOW'
        $value = @(60, 180, 1440)[$index]
        $km = [regex]::Match($text, ("name=`"snooze_{0}_kind`"[^>]*value=`"([^`"]+)`"" -f $index))
        $vm = [regex]::Match($text, ("name=`"snooze_{0}_value`"[^>]*value=`"(\d+)`"" -f $index))
        if ($km.Success) { $kind = $km.Groups[1].Value }
        if ($vm.Success) { $value = [int]$vm.Groups[1].Value }
        $script:SnoozeSnapshot += @{ Index = $index; Kind = $kind; Value = $value }
    }
}

function Restore-Snooze {
    [CmdletBinding()]
    param()
    if (-not $script:SnoozeChanged) {
        Write-Log 'Snooze options were not changed.'
        return
    }
    if ($script:SnoozeSnapshot.Count -eq 0) {
        Send-Broadcast -Command "am broadcast -a $ConfigSnoozeAction -n $Recv --ei option 0 --es kind FROM_NOW --ei value 60"
        Send-Broadcast -Command "am broadcast -a $ConfigSnoozeAction -n $Recv --ei option 1 --es kind FROM_NOW --ei value 180"
        Send-Broadcast -Command "am broadcast -a $ConfigSnoozeAction -n $Recv --ei option 2 --es kind FROM_NOW --ei value 1440"
        Write-Log 'Restored snooze options to the factory defaults.'
        return
    }
    foreach ($row in $script:SnoozeSnapshot) {
        Send-Broadcast -Command ("am broadcast -a {0} -n {1} --ei option {2} --es kind {3} --ei value {4}" -f $ConfigSnoozeAction, $Recv, $row.Index, $row.Kind, $row.Value)
    }
    Write-Log 'Restored snooze options to the starting values.'
}

function Get-SleepingRuleText {
    [CmdletBinding()]
    param()
    $listed = [string](Invoke-AdbShell -Command 'cmd notification list_automatic_zen_rules' -AllowFail).Output
    if ($listed.Trim()) { return $listed }
    $dump = [string](Invoke-AdbShell -Command 'dumpsys notification' -AllowFail).Output
    $lines = @()
    foreach ($line in ($dump -split "`n")) {
        if ($line -match 'EVERY_NIGHT_DEFAULT_RULE|Sleeping') { $lines += $line.Trim() }
    }
    return ($lines -join "`n")
}

function Restore-SleepingRule {
    [CmdletBinding()]
    param([string]$RuleId)
    if (-not $RuleId) { return }
    Write-Log "Restoring DND rule ${RuleId}: OVERRIDE_DEACTIVATE, then re-activate."
    Invoke-AdbShell -Command "cmd notification set_automatic_zen_rule_state $RuleId OVERRIDE_DEACTIVATE" -AllowFail | Out-Null
    Invoke-AdbShell -Command "cmd notification set_automatic_zen_rule_state $RuleId enabled" -AllowFail | Out-Null
    Invoke-AdbShell -Command "cmd notification set_automatic_zen_rule_state $RuleId true" -AllowFail | Out-Null
}

function Restore-QuietDefaults {
    [CmdletBinding()]
    param()
    Set-Quiet -Extras '--ez enabled false --es start 22:00 --es end 07:00 --ez followDnd false --ez criticalPauses false --es simDnd real' | Out-Null
}

function Restore-PhoneState {
    [CmdletBinding()]
    param()
    Write-Log 'Restoring phone state'
    if ($DryRun) { return }
    try { Restore-QuietDefaults } catch { Write-Log $_.Exception.Message }
    try { Set-FakeState -State 'unlocked' | Out-Null } catch { Write-Log $_.Exception.Message }
    try { Restore-Snooze } catch { Write-Log $_.Exception.Message }
    try {
        if ($script:ParseTouched) {
            Send-Broadcast -Command "am broadcast -a $ConfigParseAction -n $Recv --es value ON"
        }
    } catch { Write-Log $_.Exception.Message }
    try {
        if ($script:DndPath -eq 'real' -and $script:RealDndTouched) {
            Invoke-AdbShell -Command 'cmd notification set_dnd off' -AllowFail | Out-Null
            Write-Log 'Do Not Disturb set back off. It was ALL at the start.'
        } else {
            Write-Log ("Do Not Disturb left on the real path. {0}" -f $script:DndNote)
        }
    } catch { Write-Log $_.Exception.Message }
    try {
        if ($script:SleepingSnoozed) {
            Restore-SleepingRule -RuleId 'EVERY_NIGHT_DEFAULT_RULE'
        } else {
            Write-Log 'Sleeping DND rule was not snoozed; left unchanged.'
        }
    } catch { Write-Log $_.Exception.Message }
    Write-Log 'Restore pass finished. No PIN, clock, time zone or force-stop was used.'
}

function Run-AT02 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-02'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $dump = $script:FreshQuiet
    if (-not $dump) { $dump = Get-QuietDump }
    Save-Text -Name 'AT-02.txt' -Text $dump
    $ok = ($dump -match 'enabled=false') -and ($dump -match 'start=22:00') -and ($dump -match 'end=07:00') -and ($dump -match 'followDnd=false') -and ($dump -match 'criticalPauses=false') -and ($dump -match 'filterSource=real')
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $dump } else { Record-Result -Id $tid -Status 'FAIL' -Detail $dump }
}

function Run-AT03 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-03'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 4
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1} --ez criticalPauses false --ez followDnd false" -f $win.Start, $win.End) | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'std' -InSeconds 20 -NagMinutes 1
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $defer = Wait-ForMatch -Pattern ("DoAlarm quiet-defer id={0} planned=\d+ at=(\d+)" -f $row.Id) -BeforeLen $before -WaitSeconds 15
    $dump = Get-QuietDump -Id $row.Id
    $end = Get-Field -Dump $dump -Key 'windowEnd'
    $next = Get-Field -Dump $dump -Key 'nextFireAt'
    $pending = Get-Field -Dump $dump -Key 'pendingNag'
    $endFire = $null
    if ($defer) {
        $endAt = [int64]$defer.Groups[1].Value
        $endFire = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 360
        if ($endFire -and [int64]$endFire.Scheduled -ne $endAt) { $endFire = $null }
    }
    $grid = $null
    if ($endFire) { $grid = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90 }
    $ok = (Test-OnTime -Fire $first) -and (-not $first.Held) -and $defer -and ($pending -eq 'true') -and ($next -eq $end) -and (Test-OnTime -Fire $endFire) -and (Test-OnTime -Fire $grid)
    $detail = "first=$($first.Delta) defer=$($defer.Value) next=$next end=$end grid=$($grid.Scheduled) path=n/a"
    Restore-QuietDefaults
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT04 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-04'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 4
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1} --ez criticalPauses false" -f $win.Start, $win.End) | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'crit' -InSeconds 20 -NagMinutes 1 -Alert 'CRITICAL'
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $second = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $slice = ''
    $text = Get-LogcatText
    if ($text -and $text.Length -ge $before) { $slice = $text.Substring($before) }
    $deferred = $slice -match ("DoAlarm quiet-defer id={0} " -f $row.Id)
    $dump = Get-QuietDump -Id $row.Id
    $exempt = Get-Field -Dump $dump -Key 'exempt'
    $ok = (Test-OnTime -Fire $first) -and (Test-OnTime -Fire $second) -and (-not $deferred) -and ($exempt -eq 'true')
    Restore-QuietDefaults
    $detail = "first=$($first.Delta) second=$($second.Delta) exempt=$exempt deferred=$deferred"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT05 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-05'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 4
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1}" -f $win.Start, $win.End) | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'prom' -InSeconds 20 -NagMinutes 1 -Alert 'PROMINENT'
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $slice = ''
    $text = Get-LogcatText
    if ($text -and $text.Length -ge $before) { $slice = $text.Substring($before) }
    $deferred = $slice -match ("DoAlarm quiet-defer id={0} " -f $row.Id)
    $dump = Get-QuietDump -Id $row.Id
    $exempt = Get-Field -Dump $dump -Key 'exempt'
    $ok = (Test-OnTime -Fire $first) -and (-not $deferred) -and ($exempt -eq 'true')
    Restore-QuietDefaults
    $detail = "dump exempt=$exempt deferred=$deferred. Screen is MANUAL."
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT06 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-06'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $wrap = Get-WindowSpec -StartDelta -1 -EndDelta -2
    Set-Quiet -Extras ("--es start {0} --es end {1}" -f $wrap.Start, $wrap.End) | Out-Null
    $inside = Get-QuietDump
    $same = Get-WindowSpec -StartDelta -20 -EndDelta -5
    Set-Quiet -Extras ("--es start {0} --es end {1}" -f $same.Start, $same.End) | Out-Null
    $outside = Get-QuietDump
    $inOk = (Get-Field -Dump $inside -Key 'inWindow') -eq 'true' -and (Get-Field -Dump $inside -Key 'windowEnd') -match '^\d+$'
    $outOk = (Get-Field -Dump $outside -Key 'inWindow') -eq 'false' -and (Get-Field -Dump $outside -Key 'windowEnd') -eq 'none'
    Restore-QuietDefaults
    $detail = "inside=$(Get-Field -Dump $inside -Key 'inWindow') end=$(Get-Field -Dump $inside -Key 'windowEnd') outside=$(Get-Field -Dump $outside -Key 'inWindow') end2=$(Get-Field -Dump $outside -Key 'windowEnd')"
    if ($inOk -and $outOk) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT07 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-07'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Restore-QuietDefaults
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'toggle' -InSeconds 20 -NagMinutes 1
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 10
    $set = Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1}" -f $win.Start, $win.End)
    $held = Get-QuietDump -Id $row.Id
    $moved = (Get-Field -Dump $held -Key 'nextFireAt') -eq (Get-Field -Dump $held -Key 'windowEnd')
    $defer = $set + "`n" + (Get-LogcatText)
    $sawDefer = $defer -match ("DoAlarm quiet-defer id={0} " -f $row.Id)
    Set-Quiet -Extras '--ez enabled false' | Out-Null
    $back = Get-QuietDump -Id $row.Id
    $next = Get-Field -Dump $back -Key 'nextFireAt'
    $fire = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $ok = (Test-OnTime -Fire $first) -and $moved -and $sawDefer -and (Test-OnTime -Fire $fire) -and ($next -match '^\d+$')
    $detail = "moved=$moved defer=$sawDefer next=$next fireDelta=$($fire.Delta)"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT08 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-08'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $script:SnoozeChanged = $true
    Send-Broadcast -Command "am broadcast -a $ConfigSnoozeAction -n $Recv --ei option 1 --es kind FROM_NOW --ei value 2"
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 6
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1}" -f $win.Start, $win.End) | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'snooze' -InSeconds 20 -NagMinutes 1
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    Send-Broadcast -Command "am broadcast -a $SnoozeAction -n $Recv --el id $($row.Id) --ei option 1"
    $snooze = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 150
    $dump = Get-QuietDump -Id $row.Id
    $deferred = (Get-Field -Dump $dump -Key 'nextFireAt') -eq (Get-Field -Dump $dump -Key 'windowEnd')
    Restore-QuietDefaults
    $ok = (Test-OnTime -Fire $first) -and (Test-OnTime -Fire $snooze) -and (-not $snooze.Held) -and $deferred
    $detail = "first=$($first.Delta) snooze=$($snooze.Delta) deferred=$deferred"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT09 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-09'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'unlocked' | Out-Null
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 4
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1}" -f $win.Start, $win.End) | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'rep' -InSeconds 20 -NagMinutes 1
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($row.Id) --es unit DAY"
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    Send-Broadcast -Command "am broadcast -a $DoneAction -n $Recv --el id $($row.Id)"
    Start-Sleep -Seconds 2
    $dump = Get-RepeatDump -Id $row.Id
    $rows = Get-RepeatDumpsFromText -Text $dump -Id $row.Id
    $due = $null
    $pattern = $null
    if ($rows -and $rows.Count -gt 0) {
        $due = [int64]$rows[$rows.Count - 1].DueAt
        $pattern = $rows[$rows.Count - 1].PatternDue
    }
    $sameMinute = $false
    if ($null -ne $due) {
        $gap = $due - [int64]$row.DueAt
        $sameMinute = ($gap -ge 23 * 3600000) -and ($gap -le 25 * 3600000)
    }
    $patternOk = ($null -ne $pattern) -and ([int64]$pattern -eq [int64]$row.DueAt)
    Restore-QuietDefaults
    $detail = "due=$due pattern=$pattern gapOk=$sameMinute dump=$dump"
    if ((Test-OnTime -Fire $first) -and $sameMinute -and $patternOk) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT13 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-13'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $dir = Join-Path $script:EvidenceDir 'backup'
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $remote = 'files/qa-spec08-export.json'
    Invoke-AdbShell -Command "run-as $Pkg mkdir -p files" -AllowFail | Out-Null
    $pwd = ([string](Invoke-AdbShell -Command "run-as $Pkg pwd" -AllowFail).Output).Trim()
    $abs = ($pwd.TrimEnd('/') + '/' + $remote)
    Set-Quiet -Extras '--ez enabled true --es start 23:15 --es end 06:45 --ez followDnd true --ez criticalPauses true' | Out-Null
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path '$abs'"
    Start-Sleep -Seconds 2
    $local = Join-Path $dir 'export.json'
    Copy-QaRunAsFileToLocal -Adb $script:Adb -Serial $Serial -Package $Pkg -RemoteRelative $remote -LocalPath $local -TimeoutSec 90
    $original = [System.IO.File]::ReadAllText($local)
    Set-Quiet -Extras '--ez enabled false --es start 21:00 --es end 08:00 --ez followDnd false --ez criticalPauses false' | Out-Null
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path '$abs' --es mode replace"
    Start-Sleep -Seconds 2
    $replaced = Get-QuietDump
    $replaceOk = ($replaced -match 'enabled=true') -and ($replaced -match 'start=23:15') -and ($replaced -match 'end=06:45') -and ($replaced -match 'followDnd=true') -and ($replaced -match 'criticalPauses=true')
    Set-Quiet -Extras '--ez enabled false --es start 21:10 --es end 08:10 --ez followDnd false --ez criticalPauses false' | Out-Null
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path '$abs' --es mode merge"
    Start-Sleep -Seconds 2
    $merged = Get-QuietDump
    $mergeOk = ($merged -match 'enabled=false') -and ($merged -match 'start=21:10') -and ($merged -match 'followDnd=false')
    $stripped = [regex]::Replace($original, ',\s*"quiet(?:Enabled|StartMinute|EndMinute|FollowDnd|CriticalPauses)"\s*:\s*[^,\r\n}]+', '')
    $stripPath = Join-Path $dir 'spec7.json'
    $enc = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($stripPath, $stripped, $enc)
    Copy-QaLocalFileToRunAs -Adb $script:Adb -Serial $Serial -Package $Pkg -RemoteRelative 'files/qa-spec08-spec7.json' -LocalPath $stripPath -TimeoutSec 90
    $spec7 = ($pwd.TrimEnd('/') + '/files/qa-spec08-spec7.json')
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path '$spec7' --es mode replace"
    Start-Sleep -Seconds 2
    $left = Get-QuietDump
    $leftOk = ($left -match 'enabled=false') -and ($left -match 'start=21:10') -and ($left -match 'followDnd=false') -and ($left -match 'criticalPauses=false')
    Restore-QuietDefaults
    $detail = "replace=$replaceOk merge=$mergeOk spec7=$leftOk"
    if ($replaceOk -and $mergeOk -and $leftOk) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Ensure-CapBaseline {
    [CmdletBinding()]
    param()
    if ($script:CapReady) { return }
    Set-FakeState -State 'unlocked' | Out-Null
    Clear-LeftoverRows
    $start = Get-UnlockDump
    Write-Log ("Cap baseline " + $start)
    Save-Text -Name 'cap-baseline.txt' -Text $start
    Set-FakeState -State 'locked' | Out-Null
    $script:R1 = Add-StampedReminder -Name 'r1' -InSeconds 3600 -NagMinutes 0 -Unit 'DAY'
    $script:R2 = Add-StampedReminder -Name 'r2' -InSeconds 3600 -NagMinutes 0 -Unit 'DAY'
    $script:CapReady = $true
}

function Run-AT14 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-14'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Ensure-CapBaseline
    $third = Add-StampedReminder -Name 'r3' -InSeconds 3600 -NagMinutes 0 -Unit 'WEEK' -WeekDays 'MON'
    $plain = Add-StampedReminder -Name 'plain' -InSeconds 3600 -NagMinutes 0
    $script:Plain = $plain
    $dump = Get-UnlockDump
    $ok = ($script:R1.Id -gt 0) -and ($script:R2.Id -gt 0) -and $third.Blocked -and ($third.BlockText -match 'repeating=2') -and ($third.BlockText -match 'repeatCap=2') -and ($plain.Id -gt 0) -and ($dump -match 'repeating=2 repeatCap=2')
    $detail = "third=$($third.BlockText) plain=$($plain.Id) dump=$dump. WEEK includes weekDays=MON so Spec 4 accepts the rule."
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT15 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-15'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Ensure-CapBaseline
    if (-not (Should-Run -Id 'Do-AT-14')) { Run-AT14 }
    $script:ParseTouched = $true
    Send-Broadcast -Command "am broadcast -a $ConfigParseAction -n $Recv --es value ON"
    $title = "T_s8_nl_$($script:RunStamp) every day 9am"
    $before = Get-LogLen
    $blocked = Add-StampedReminder -Name 'nl' -InSeconds 3600 -NagMinutes 0 -Parse -RawTitle $title
    $text = Get-LogcatText
    $slice = ''
    if ($text.Length -ge $before) { $slice = $text.Substring($before) }
    $parsed = $slice -match 'DoAlarm parse match='
    Send-Broadcast -Command "am broadcast -a $ConfigParseAction -n $Recv --es value OFF"
    $literal = Add-StampedReminder -Name 'nlfull' -InSeconds 3600 -NagMinutes 0 -RawTitle $title
    Send-Broadcast -Command "am broadcast -a $ConfigParseAction -n $Recv --es value ON"
    $ok = $blocked.Blocked -and ($blocked.BlockText -match 'repeating=2') -and (-not $parsed) -and ($literal.Id -gt 0)
    $detail = "blocked=$($blocked.BlockText) parsed=$parsed literal=$($literal.Id)"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT16 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-16'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Ensure-CapBaseline
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($script:Plain.Id) --es unit DAY"
    $blocked = Wait-ForMatch -Pattern 'DoAlarm unlock blocked repeating=[^\r\n]+' -BeforeLen $before -WaitSeconds 15
    $off = Get-RepeatDump -Id $script:Plain.Id
    $before2 = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($script:R2.Id) --es unit WEEK --es weekDays MON"
    $allowed = Wait-ForMatch -Pattern ("DoAlarm repeat id={0}[^\r\n]+" -f $script:R2.Id) -BeforeLen $before2 -WaitSeconds 15
    Send-Broadcast -Command "am broadcast -a $DoneAction -n $Recv --el id $($script:R1.Id)"
    Start-Sleep -Seconds 1
    $still = Get-UnlockDump
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($script:R1.Id) --es unit OFF"
    Start-Sleep -Seconds 1
    $freed = Get-UnlockDump
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($script:Plain.Id) --es unit DAY"
    Start-Sleep -Seconds 1
    $plainDump = Get-RepeatDump -Id $script:Plain.Id
    $ok = $blocked -and ($off -match 'rule=OFF') -and $allowed -and ($still -match 'repeating=2') -and ($freed -match 'repeating=1') -and ($plainDump -match 'DAY')
    $detail = "blocked=$($blocked.Value) still=$still freed=$freed plain=$plainDump"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT17 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-17'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'unlocked' | Out-Null
    $a = Add-StampedReminder -Name 'imp1' -InSeconds 3600 -NagMinutes 0 -Unit 'DAY'
    $b = Add-StampedReminder -Name 'imp2' -InSeconds 3600 -NagMinutes 0 -Unit 'DAY'
    $c = Add-StampedReminder -Name 'imp3' -InSeconds 3600 -NagMinutes 0 -Unit 'DAY'
    $pwd = ([string](Invoke-AdbShell -Command "run-as $Pkg pwd" -AllowFail).Output).Trim()
    $remote = 'files/qa-spec08-imp.json'
    $abs = ($pwd.TrimEnd('/') + '/' + $remote)
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path '$abs'"
    Start-Sleep -Seconds 2
    Set-FakeState -State 'locked' | Out-Null
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path '$abs' --es mode replace"
    Start-Sleep -Seconds 2
    $dump = Get-UnlockDump
    $plain = Add-StampedReminder -Name 'impplain' -InSeconds 3600 -NagMinutes 0
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($plain.Id) --es unit DAY"
    $blocked = Wait-ForMatch -Pattern 'DoAlarm unlock blocked repeating=[^\r\n]+' -BeforeLen $before -WaitSeconds 15
    $fake = Set-FakeState -State 'unlocked'
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($plain.Id) --es unit DAY"
    Start-Sleep -Seconds 1
    $after = Get-RepeatDump -Id $plain.Id
    $ok = ($a.Id -gt 0) -and ($b.Id -gt 0) -and ($c.Id -gt 0) -and ($dump -match 'repeating=3') -and $blocked -and ($fake -match 'repeatCap=2') -and ($after -match 'DAY')
    $detail = "dump=$dump blocked=$($blocked.Value) fake=$fake after=$after"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT20 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-20'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Restore-QuietDefaults
    Set-Quiet -Extras '--ez followDnd false' | Out-Null
    $on = Set-DndMode -Mode 'on'
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'dndoff' -InSeconds 20 -NagMinutes 1
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $n1 = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $n2 = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $text = Get-LogcatText
    $held = $text.Substring($before) -match ("DoAlarm dnd-hold id={0} " -f $row.Id)
    Set-DndMode -Mode 'off' | Out-Null
    $ok = (Test-OnTime -Fire $first) -and (Test-OnTime -Fire $n1) -and (Test-OnTime -Fire $n2) -and (-not $held) -and ((Get-Field -Dump $on -Key 'dndActive') -eq 'true') -and ((Get-Field -Dump $on -Key 'followDnd') -eq 'false')
    $detail = "path=$($script:DndPath) holds=$held n1=$($n1.Delta) n2=$($n2.Delta)"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT21 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-21'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-Quiet -Extras '--ez enabled false --ez followDnd true --ez criticalPauses false' | Out-Null
    Set-DndMode -Mode 'on' | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'dndon' -InSeconds 20 -NagMinutes 1
    $script:DndOnId = $row.Id
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $holds = 0
    $guard = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $guard -and $holds -lt 2) {
        $remain = [int]($guard - (Get-Date)).TotalSeconds
        if ($remain -lt 5) { break }
        $wait = 80
        if ($remain -lt $wait) { $wait = $remain }
        $fire = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds $wait
        if ($fire -and $fire.Held -and (Test-OnTime -Fire $fire)) { $holds += 1 }
        elseif (-not $fire) { break }
    }
    $dump = Get-QuietDump -Id $row.Id
    Finish-OneReminder -Id $row.Id
    $ok = (Test-OnTime -Fire $first) -and (-not $first.Held) -and ($holds -ge 2) -and ((Get-Field -Dump $dump -Key 'dndHeld') -eq 'true') -and ((Get-Field -Dump $dump -Key 'pendingNag') -eq 'true')
    $detail = "path=$($script:DndPath) holds=$holds dndHeld=$(Get-Field -Dump $dump -Key 'dndHeld')"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT22 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-22'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-Quiet -Extras '--ez enabled false --ez followDnd true' | Out-Null
    Set-DndMode -Mode 'off' | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'dndmid' -InSeconds 20 -NagMinutes 1
    $script:MidId = $row.Id
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $nag = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    Set-DndMode -Mode 'on' | Out-Null
    $held = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $ok = (Test-OnTime -Fire $first) -and (Test-OnTime -Fire $nag) -and (-not $nag.Held) -and $held.Held -and (Test-OnTime -Fire $held)
    $detail = "path=$($script:DndPath) nagHeld=$($nag.Held) laterHeld=$($held.Held)"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT23 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-23'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $id = $script:MidId
    if (-not $id) { $id = $script:DndOnId }
    $before = Get-LogLen
    Set-DndMode -Mode 'off' | Out-Null
    $release = Wait-ForMatch -Pattern ("DoAlarm dnd-release id={0} " -f $id) -BeforeLen $before -WaitSeconds 20
    $filter = Wait-ForMatch -Pattern 'DoAlarm dnd filter=1 active=false' -BeforeLen $before -WaitSeconds 5
    $fire = Wait-Alert -Id $id -BeforeLen $before -WaitSeconds 70
    $dump = Get-QuietDump -Id $id
    Finish-OneReminder -Id $id
    $ok = $release -and $filter -and (Test-OnTime -Fire $fire) -and (-not $fire.Held) -and ((Get-Field -Dump $dump -Key 'dndHeld') -eq 'false')
    $detail = "path=$($script:DndPath) release=$([bool]$release) delta=$($fire.Delta)"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT24 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-24'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-Quiet -Extras '--ez enabled false --ez followDnd true' | Out-Null
    Set-DndMode -Mode 'on' | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'dndset' -InSeconds 20 -NagMinutes 1
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $held = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $relBefore = Get-LogLen
    Set-Quiet -Extras '--ez followDnd false' | Out-Null
    $release = Wait-ForMatch -Pattern ("DoAlarm dnd-release id={0} " -f $row.Id) -BeforeLen $relBefore -WaitSeconds 15
    $fire = Wait-Alert -Id $row.Id -BeforeLen $relBefore -WaitSeconds 70
    $later = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    Set-Quiet -Extras '--ez followDnd true' | Out-Null
    $again = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    Set-DndMode -Mode 'off' | Out-Null
    $ok = (Test-OnTime -Fire $first) -and $held.Held -and $release -and (Test-OnTime -Fire $fire) -and (-not $later.Held) -and $again.Held
    $detail = "path=$($script:DndPath) release=$([bool]$release) laterHeld=$($later.Held) again=$($again.Held)"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT25 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-25'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-Quiet -Extras '--ez enabled false --ez followDnd true --ez criticalPauses false' | Out-Null
    Set-DndMode -Mode 'on' | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'dndcrit' -InSeconds 20 -NagMinutes 1 -Alert 'CRITICAL'
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $nag = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $prom = Add-StampedReminder -Name 'dndprom' -InSeconds 20 -NagMinutes 1 -Alert 'PROMINENT'
    $promFire = Wait-Alert -Id $prom.Id -BeforeLen (Get-LogLen) -WaitSeconds 80
    $text = Get-LogcatText
    $held = $false
    if ($text -and $text.Length -ge $before) {
        $held = $text.Substring($before) -match ("DoAlarm dnd-hold id=({0}|{1}) " -f $row.Id, $prom.Id)
    }
    $dumpC = Get-QuietDump -Id $row.Id
    $dumpP = Get-QuietDump -Id $prom.Id
    Set-DndMode -Mode 'off' | Out-Null
    $ok = (Test-OnTime -Fire $first) -and (Test-OnTime -Fire $nag) -and (-not $nag.Held) -and (-not $held) -and ((Get-Field -Dump $dumpC -Key 'exempt') -eq 'true') -and ((Get-Field -Dump $dumpP -Key 'exempt') -eq 'true') -and (Test-OnTime -Fire $promFire)
    $detail = "path=$($script:DndPath) critExempt=$(Get-Field -Dump $dumpC -Key 'exempt') promExempt=$(Get-Field -Dump $dumpP -Key 'exempt')"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT26 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-26'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-DndMode -Mode 'off' | Out-Null
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 4
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1} --ez criticalPauses true --ez followDnd false" -f $win.Start, $win.End) | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'cpq' -InSeconds 20 -NagMinutes 1 -Alert 'CRITICAL'
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $defer = Wait-ForMatch -Pattern ("DoAlarm quiet-defer id={0} " -f $row.Id) -BeforeLen $before -WaitSeconds 20
    $held = Get-QuietDump -Id $row.Id
    Set-Quiet -Extras '--ez criticalPauses false' | Out-Null
    $back = Get-QuietDump -Id $row.Id
    $aOk = (Test-OnTime -Fire $first) -and $defer -and ((Get-Field -Dump $held -Key 'exempt') -eq 'false') -and ((Get-Field -Dump $back -Key 'exempt') -eq 'true')
    Set-Quiet -Extras '--ez enabled false --ez followDnd true --ez criticalPauses true' | Out-Null
    Set-DndMode -Mode 'on' | Out-Null
    $beforeB = Get-LogLen
    $dnd = Add-StampedReminder -Name 'cpd' -InSeconds 20 -NagMinutes 1 -Alert 'CRITICAL'
    $bFirst = Wait-Alert -Id $dnd.Id -BeforeLen $beforeB -WaitSeconds 80
    $bHold = Wait-Alert -Id $dnd.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $relBefore = Get-LogLen
    Set-Quiet -Extras '--ez criticalPauses false' | Out-Null
    $release = Wait-ForMatch -Pattern ("DoAlarm dnd-release id={0} " -f $dnd.Id) -BeforeLen $relBefore -WaitSeconds 15
    $bFire = Wait-Alert -Id $dnd.Id -BeforeLen $relBefore -WaitSeconds 70
    $prom = Add-StampedReminder -Name 'cpp' -InSeconds 20 -NagMinutes 1 -Alert 'PROMINENT'
    Set-Quiet -Extras '--ez criticalPauses true' | Out-Null
    $promDump = Get-QuietDump -Id $prom.Id
    $text = Get-LogcatText
    $promBad = $text -match ("DoAlarm (?:quiet-defer|dnd-hold) id={0} " -f $prom.Id)
    Set-DndMode -Mode 'off' | Out-Null
    Restore-QuietDefaults
    $ok = $aOk -and (Test-OnTime -Fire $bFirst) -and $bHold.Held -and $release -and (Test-OnTime -Fire $bFire) -and ((Get-Field -Dump $promDump -Key 'exempt') -eq 'true') -and (-not $promBad)
    $detail = "path=$($script:DndPath) a=$aOk hold=$($bHold.Held) release=$([bool]$release) promExempt=$(Get-Field -Dump $promDump -Key 'exempt')"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT27 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-27'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $win = Get-WindowSpec -StartDelta -1 -EndDelta 3
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1} --ez followDnd true --ez criticalPauses false" -f $win.Start, $win.End) | Out-Null
    Set-DndMode -Mode 'on' | Out-Null
    $before = Get-LogLen
    $row = Add-StampedReminder -Name 'both' -InSeconds 20 -NagMinutes 1
    $first = Wait-Alert -Id $row.Id -BeforeLen $before -WaitSeconds 80
    $defer = Wait-ForMatch -Pattern ("DoAlarm quiet-defer id={0} planned=\d+ at=(\d+)" -f $row.Id) -BeforeLen $before -WaitSeconds 20
    $endFire = $null
    if ($defer) { $endFire = Wait-Alert -Id $row.Id -BeforeLen (Get-LogLen) -WaitSeconds 300 }
    $relBefore = Get-LogLen
    Set-DndMode -Mode 'off' | Out-Null
    $released = Wait-Alert -Id $row.Id -BeforeLen $relBefore -WaitSeconds 70
    # A nag already held by Do Not Disturb, then a window turned on, then Do Not
    # Disturb off: the release is placed at the window end, not at now.
    Set-Quiet -Extras '--ez enabled false --ez followDnd true --ez criticalPauses false' | Out-Null
    Set-DndMode -Mode 'on' | Out-Null
    $before2 = Get-LogLen
    $fresh = Add-StampedReminder -Name 'both2' -InSeconds 20 -NagMinutes 1
    $f1 = Wait-Alert -Id $fresh.Id -BeforeLen $before2 -WaitSeconds 80
    $fHold = Wait-Alert -Id $fresh.Id -BeforeLen (Get-LogLen) -WaitSeconds 90
    $win2 = Get-WindowSpec -StartDelta -1 -EndDelta 6
    Set-Quiet -Extras ("--ez enabled true --es start {0} --es end {1} --ez followDnd true" -f $win2.Start, $win2.End) | Out-Null
    $rel2 = Get-LogLen
    Set-DndMode -Mode 'off' | Out-Null
    $releasedToEnd = Wait-ForMatch -Pattern ("DoAlarm dnd-release id={0} " -f $fresh.Id) -BeforeLen $rel2 -WaitSeconds 15
    $toEnd = Get-QuietDump -Id $fresh.Id
    $end = Get-Field -Dump $toEnd -Key 'windowEnd'
    $next = Get-Field -Dump $toEnd -Key 'nextFireAt'
    Finish-OneReminder -Id $row.Id
    Finish-OneReminder -Id $fresh.Id
    Restore-QuietDefaults
    $ok = (Test-OnTime -Fire $first) -and $defer -and $endFire.Held -and (Test-OnTime -Fire $released) -and (Test-OnTime -Fire $f1) -and (-not $f1.Held) -and $fHold.Held -and $releasedToEnd -and ($next -eq $end) -and ($end -ne 'none') -and ($next -ne 'none')
    $detail = "path=$($script:DndPath) endHold=$($endFire.Held) release=$($released.Delta) toEnd=$next window=$end dndRelease=$([bool]$releasedToEnd)"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT28 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-28'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $before = Get-LogLen
    Set-Quiet -Extras '--es simDnd on' | Out-Null
    $on = Get-QuietDump
    $onLog = Wait-ForMatch -Pattern 'DoAlarm dnd filter=2 active=true' -BeforeLen $before -WaitSeconds 10
    Set-Quiet -Extras '--es simDnd off' | Out-Null
    $off = Get-QuietDump
    Set-Quiet -Extras '--es simDnd real' | Out-Null
    $real = Get-QuietDump
    $ok = ((Get-Field -Dump $on -Key 'filter') -eq '2') -and ((Get-Field -Dump $on -Key 'filterSource') -eq 'sim') -and ((Get-Field -Dump $on -Key 'dndActive') -eq 'true') -and $onLog -and ((Get-Field -Dump $off -Key 'filter') -eq '1') -and ((Get-Field -Dump $off -Key 'filterSource') -eq 'sim') -and ((Get-Field -Dump $off -Key 'dndActive') -eq 'false') -and ((Get-Field -Dump $real -Key 'filterSource') -eq 'real')
    $detail = "on=$(Get-Field -Dump $on -Key 'filter') off=$(Get-Field -Dump $off -Key 'filter') real=$(Get-Field -Dump $real -Key 'filterSource')"
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail } else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Record-ManualRows {
    [CmdletBinding()]
    param()
    $rows = @(
        @{ Id = 'Do-AT-01'; Status = 'MANUAL'; Detail = 'CI. Quiet-hours window maths, DST, first fire, nag pause and resume, Critical and Prominent bypass, DND hold and release, and the repeating cap are unit tests. checkAlarmManifests covers USE and SCHEDULE.' },
        @{ Id = 'Do-AT-05'; Status = 'MANUAL'; Detail = 'Human screen. The dump is scripted. Tim sees the full-screen alarm inside the window, taps Stop, and the next nag follows the interval rather than the window end.' },
        @{ Id = 'Do-AT-10'; Status = 'MANUAL'; Detail = 'Human reboot. A held Standard nag fires at the window end, not at boot. A Critical nag inside the window stays on its schedule. A dndHeld nag whose Do Not Disturb ends while the phone is off fires within a minute of onExternalClock.' },
        @{ Id = 'Do-AT-11'; Status = 'MANUAL'; Detail = 'Unit only. The runner does not change the phone clock or zone.' },
        @{ Id = 'Do-AT-12'; Status = 'MANUAL'; Detail = 'Human. Quiet hours, Starts 22:00, Ends 07:00, the two switches, subtitles and caption. No permission prompt for the Do Not Disturb switch. Equal start and end is refused. Values survive leaving Settings and match DUMP_QUIET.' },
        @{ Id = 'Do-AT-18'; Status = 'MANUAL'; Detail = 'Human. Fake locked, two repeating reminders. Daily on a third opens the unlock screen and Back returns to the unsaved editor. "Bins every Tuesday 7am" on add opens the unlock screen. Editing an existing repeating reminder saves.' },
        @{ Id = 'Do-AT-19'; Status = 'MANUAL'; Detail = 'CI. assembleDebug, testDebugUnitTest, lintDebug, and checkAlarmManifests in USE and SCHEDULE.' }
    )
    foreach ($row in $rows) {
        if (-not (Should-Run -Id $row.Id)) { continue }
        Record-Result -Id $row.Id -Status $row.Status -Detail $row.Detail
    }
}

function Write-ResultsFile {
    [CmdletBinding()]
    param([string]$Overall)
    $nl = [Environment]::NewLine
    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add('# Do Spec 8 phone results')
    [void]$lines.Add('')
    [void]$lines.Add(('Generated: {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')))
    [void]$lines.Add(('Ref: `{0}`' -f $ExpectedSha))
    [void]$lines.Add(('Device: Samsung Galaxy S24+ serial `{0}`' -f $Serial))
    [void]$lines.Add(('Evidence: `{0}`' -f $script:EvidenceDir))
    [void]$lines.Add(('DND: {0}' -f $script:DndNote))
    [void]$lines.Add(('Overall (automated rows): **{0}**' -f $Overall))
    [void]$lines.Add('')
    [void]$lines.Add('| ID | Status | Detail |')
    [void]$lines.Add('|---|---|---|')
    foreach ($row in $script:Results) {
        $detail = ([string]$row.Detail).Replace('|', '/')
        [void]$lines.Add(('| {0} | {1} | {2} |' -f $row.Id, $row.Status, $detail))
    }
    [void]$lines.Add('')
    [void]$lines.Add('MANUAL rows do not fail the automated score. Restore puts quiet hours off, simDnd real, the fake unlock unlocked, and snooze options back. Do Not Disturb is turned off only when this run started from ALL. No PIN.')
    $text = [string]::Join($nl, $lines) + $nl
    $dir = Split-Path $ResultsPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $enc = New-Object System.Text.UTF8Encoding $true
    [System.IO.File]::WriteAllText($ResultsPath, $text, $enc)
    if ($script:EvidenceDir) {
        Copy-Item $ResultsPath (Join-Path $script:EvidenceDir 'results-spec-08.md') -Force
    }
    Write-Log "Wrote $ResultsPath"
}

$script:EvidenceRoot = 'C:\Users\tdrev\do-qa\qa-evidence'
$script:EvidenceDir = Join-Path $script:EvidenceRoot ("Do-spec08-" + $Stamp)

try {
    if ($DryRun) {
        Write-Log 'DryRun is on: no git checkout, no install, no phone changes.'
        $script:EvidenceDir = Join-Path $env:TEMP ("Do-spec08-dryrun-" + $Stamp)
    }
    if (-not (Test-Path $script:EvidenceDir)) {
        New-Item -ItemType Directory -Path $script:EvidenceDir -Force | Out-Null
    }
    Write-Log "Evidence folder: $script:EvidenceDir"
    if (-not (Test-Path $AdbPath)) {
        $found = Get-Command adb -ErrorAction SilentlyContinue
        if ($found) { $script:Adb = $found.Source }
        elseif (-not $DryRun) { throw "adb not found at $AdbPath and not on PATH." }
    }
    $rules = Get-SleepingRuleText
    Save-Text -Name 'sleeping-dnd-before.txt' -Text $rules
    Write-Log 'Sleeping / EVERY_NIGHT_DEFAULT_RULE snapshot saved. This run does not set a screen-lock PIN.'
    Ensure-Checkout
    if ($DryRun) {
        Write-Log 'DryRun command plan: checkout, assembleDebug, downgrade guard, quiet hours, repeating cap, Do Not Disturb.'
        Record-Result -Id 'Do-runner' -Status 'SKIP' -Detail 'DryRun only.'
        Record-ManualRows
        return
    }
    Invoke-BuildAndInstall
    Start-DoLogcat
    Snapshot-Snooze
    Initialize-DndPath
    Run-AT02
    Run-AT03
    Run-AT04
    Run-AT05
    Run-AT06
    Run-AT07
    Run-AT08
    Run-AT09
    Run-AT13
    Run-AT14
    Run-AT15
    Run-AT16
    Run-AT17
    Run-AT20
    Run-AT21
    Run-AT22
    Run-AT23
    Run-AT24
    Run-AT25
    Run-AT26
    Run-AT27
    Run-AT28
    Record-ManualRows
}
catch {
    Write-Log ("ERROR: " + $_.Exception.Message)
    Record-Result -Id 'Do-runner' -Status 'FAIL' -Detail $_.Exception.Message
    throw
}
finally {
    try { Finish-TrackedReminders } catch { Write-Log ("Cleanup failed: " + $_.Exception.Message) }
    try { Restore-PhoneState } catch { Write-Log ("Restore failed: " + $_.Exception.Message) }
    Stop-DoLogcat
    $autoFails = @($script:Results | Where-Object { $_.Status -eq 'FAIL' })
    $overall = 'PASS'
    if ($autoFails.Count -gt 0) { $overall = 'FAIL' }
    try { Write-ResultsFile -Overall $overall } catch { Write-Log ("Could not write results: " + $_.Exception.Message) }
    Write-Log "Done. Overall (automated rows)=$overall. Results: $ResultsPath"
}
