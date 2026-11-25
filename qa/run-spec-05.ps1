#Requires -Version 5.1
<#
.SYNOPSIS
  Do Spec 5 phone QA runner for Tim's Windows 11 PC (PowerShell 5.1).

.DESCRIPTION
  Clones or fetches tdrevans-aus/do-app into C:\Users\tdrev\do-qa\repo, checks out
  the commit in -Ref (default do/spec-5-nl-alert-levels), builds the default USE
  debug APK, installs it on the Samsung Galaxy S24+ (R5CXC0FWWVN), and runs the
  Spec 5 acceptance tests that can be driven by the debug broadcasts.
  Evidence goes to C:\Users\tdrev\do-qa\qa-evidence\Do-spec05-<stamp>\.
  Results are written to C:\Users\tdrev\do-qa\results-spec-05.md.

  Human ATs (Set to / Keep, save-does-not-apply, editor chrome, Critical sound
  through DND, Prominent lock screen, unlocked banner, reboot-without-unlock)
  are listed as MANUAL. This script does not replace run-spec-04.ps1.

  Do-runner.log in the evidence folder is the authoritative live log.

  ALWAYS restores the phone in a finally block: Parse Dates ON, default alert
  Standard, the captured full-screen and Do Not Disturb appops, automatic time
  zone, and leftover T_s5_* reminders (Mark Done). Does not set a PIN, does not
  factory-reset, and does not turn on Do Not Disturb or silent mode. Never uses
  force-stop; if am kill leaves the process running the row is RECORD, not FAIL.

  ADD with parse true and SET_ALERT_LEVEL write through, unlike the editor.

.PARAMETER DryRun
  Print the commands without changing the phone or writing a checkout.

.PARAMETER Only
  Optional test ID filter, e.g. -Only AT-02,AT-05

.PARAMETER Ref
  Git commit or ref to check out in C:\Users\tdrev\do-qa\repo. Default is the
  Spec 5 branch. A short SHA, full SHA, or branch name is accepted.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string[]]$Only,
    [string]$Ref = 'do/spec-5-nl-alert-levels'
)

$ErrorActionPreference = 'Stop'

$RepoDir      = 'C:\Users\tdrev\do-qa\repo'
$RepoUrl      = 'https://github.com/tdrevans-aus/do-app.git'
$ExpectedSha  = $Ref
$AdbPath      = 'C:\Users\tdrev\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$AndroidHome  = 'C:\Users\tdrev\AppData\Local\Android\Sdk'
$Serial       = 'R5CXC0FWWVN'
$Pkg          = 'com.evansappstudio.doapp'
$Recv         = 'com.evansappstudio.doapp/.debug.DebugReceiver'
$AddAction    = 'com.evansappstudio.doapp.debug.ADD'
$DoneAction   = 'com.evansappstudio.doapp.debug.DONE'
$ParseAction  = 'com.evansappstudio.doapp.debug.PARSE'
$SetAlertAction = 'com.evansappstudio.doapp.debug.SET_ALERT_LEVEL'
$ConfigParseAction = 'com.evansappstudio.doapp.debug.CONFIG_PARSE'
$ConfigAlertAction = 'com.evansappstudio.doapp.debug.CONFIG_ALERT_DEFAULT'
$DumpAlertAction = 'com.evansappstudio.doapp.debug.DUMP_ALERT'
$DumpRepeatAction = 'com.evansappstudio.doapp.debug.DUMP_REPEAT'
$ResultsPath  = 'C:\Users\tdrev\do-qa\results-spec-05.md'
$PhoneTolMs   = 60000
$Stamp        = Get-Date -Format 'yyyyMMdd-HHmmss'

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
$script:LogcatProc  = $null
$script:LogcatFile  = $null
$script:Results     = New-Object System.Collections.Generic.List[object]
$script:CreatedIds  = New-Object System.Collections.Generic.List[int64]
$script:PhoneTouched = $false
$script:Adb = $AdbPath
$script:OriginalFsi = $null
$script:OriginalDnd = $null

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

function ConvertTo-ScalarString {
    [CmdletBinding()]
    param($Value)
    if ($null -eq $Value) { return '' }
    if ($Value -is [string]) { return $Value }
    return [string]$Value
}

function ConvertTo-Int64Scalar {
    [CmdletBinding()]
    param($Value, [string]$Name)
    $text = ConvertTo-ScalarString -Value $Value
    $trimmed = $text.Trim()
    if ($trimmed -match '^-?\d+$') { return [int64]$trimmed }
    throw "Expected an integer for $Name but got '$trimmed'"
}

function Invoke-Adb {
    [CmdletBinding()]
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$AdbArgs,
        [switch]$AllowFail
    )
    $all = @('-s', $Serial) + $AdbArgs
    Write-Log ("adb " + ($all -join ' '))
    if ($DryRun) { return @{ ExitCode = 0; Output = '' } }
    $ErrorActionPreference = 'Continue'
    $out = & $script:Adb @all 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $text = ($out -join "`n")
    if (-not $AllowFail -and $code -ne 0) { throw "adb exited $code : $text" }
    return @{ ExitCode = $code; Output = $text }
}

function Invoke-AdbShell {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [switch]$AllowFail
    )
    Invoke-Adb -AllowFail:$AllowFail 'shell' $Command
}

function Invoke-AdbArgList {
    [CmdletBinding()]
    param(
        [string[]]$ArgList,
        [switch]$AllowFail
    )
    $all = @('-s', $Serial) + @($ArgList)
    Write-Log ("adb " + ($all -join ' '))
    if ($DryRun) { return @{ ExitCode = 0; Output = '' } }
    $ErrorActionPreference = 'Continue'
    $out = & $script:Adb @all 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $text = ($out -join "`n")
    if (-not $AllowFail -and $code -ne 0) { throw "adb exited $code : $text" }
    return @{ ExitCode = $code; Output = $text }
}

function Save-Text {
    [CmdletBinding()]
    param([string]$Name, [string]$Text)
    if (-not $script:EvidenceDir) { return }
    $path = Join-Path $script:EvidenceDir $Name
    if ($null -eq $Text) { $Text = '' }
    Set-Content -Path $path -Value $Text -Encoding UTF8
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
    Invoke-Adb -AdbArgs @('install', '-r', $apk) | Out-Null
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
    $script:LogcatProc = Start-Process -FilePath $script:Adb -ArgumentList @('-s', $Serial, 'logcat', '-v', 'threadtime', '-s', 'DoAlarm:I', '*:S') -RedirectStandardOutput $script:LogcatFile -RedirectStandardError (Join-Path $script:EvidenceDir 'Do-logcat-DoAlarm.err.txt') -PassThru -WindowStyle Hidden
    Write-Log "logcat pid $($script:LogcatProc.Id)"
}

function Stop-DoLogcat {
    [CmdletBinding()]
    param()
    if ($script:LogcatProc -and -not $script:LogcatProc.HasExited) {
        try { Stop-Process -Id $script:LogcatProc.Id -Force -ErrorAction SilentlyContinue } catch { }
    }
    $script:LogcatProc = $null
}

function Get-LogcatText {
    [CmdletBinding()]
    param()
    if ($script:LogcatFile -and (Test-Path $script:LogcatFile)) {
        return Get-Content -Path $script:LogcatFile -Raw -ErrorAction SilentlyContinue
    }
    return ''
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

function Get-LogLen {
    [CmdletBinding()]
    param()
    $text = Get-LogcatText
    if ($text) { return $text.Length }
    return 0
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
    if ($value -gt 0) { $script:CreatedIds.Add($value) }
    return $value
}

function Add-PlainReminder {
    [CmdletBinding()]
    param([string]$Title, [int]$InSeconds, [int]$NagMinutes, [string]$Alert)
    $before = Get-LogLen
    $safe = $Title.Replace("'", '')
    $cmd = "am broadcast -a $AddAction -n $Recv --es title '$safe' --ei inSeconds $InSeconds --ei nagMinutes $NagMinutes"
    if ($Alert) { $cmd = $cmd + " --es alert $Alert" }
    Send-Broadcast -Command $cmd
    if ($DryRun) { return @{ Id = 0; DueAt = 0; NagMinutes = $NagMinutes } }
    $m = Wait-ForMatch -Pattern 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)' -BeforeLen $before -WaitSeconds 20
    if (-not $m) { throw "Debug ADD did not log for $Title" }
    $info = @{
        Id = (Remember-Id -Id $m.Groups[1].Value)
        DueAt = [int64]$m.Groups[2].Value
        NagMinutes = [int]$m.Groups[3].Value
        Title = $Title
    }
    Write-Log ("ADD {0} id={1} dueAt={2}" -f $Title, $info.Id, $info.DueAt)
    return $info
}

function Add-ParsedReminder {
    [CmdletBinding()]
    param([string]$Title, [int]$InSeconds, [int]$NagMinutes)
    $before = Get-LogLen
    $safe = $Title.Replace("'", '')
    $cmd = "am broadcast -a $AddAction -n $Recv --es title '$safe' --ei inSeconds $InSeconds --ei nagMinutes $NagMinutes --ez parse true"
    Send-Broadcast -Command $cmd
    if ($DryRun) { return @{ Id = 0; DueAt = 0 } }
    $m = Wait-ForMatch -Pattern 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)' -BeforeLen $before -WaitSeconds 20
    if (-not $m) { throw "Parsed ADD did not log for $Title" }
    $slice = ''
    $text = Get-LogcatText
    if ($text -and $text.Length -ge $before) { $slice = $text.Substring($before) }
    return @{
        Id = (Remember-Id -Id $m.Groups[1].Value)
        DueAt = [int64]$m.Groups[2].Value
        Slice = $slice
    }
}

function Done-Reminder {
    [CmdletBinding()]
    param($Id)
    $id = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    Send-Broadcast -Command "am broadcast -a $DoneAction -n $Recv --el id $id"
}

function Send-Parse {
    [CmdletBinding()]
    param([string]$Text, [string]$Now, [string]$DraftDue)
    $before = Get-LogLen
    $safe = $Text.Replace("'", '')
    $cmd = "am broadcast -a $ParseAction -n $Recv --es text '$safe'"
    if ($Now) { $cmd = $cmd + " --el now $Now" }
    if ($DraftDue) { $cmd = $cmd + " --el draftDue $DraftDue" }
    Send-Broadcast -Command $cmd
    if ($DryRun) { return '' }
    Start-Sleep -Seconds 1
    $all = Get-LogcatText
    if ($all -and $all.Length -ge $before) { return $all.Substring($before) }
    return ''
}

function Set-AlertLevel {
    [CmdletBinding()]
    param($Id, [string]$Level)
    $id = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    Send-Broadcast -Command "am broadcast -a $SetAlertAction -n $Recv --el id $id --es level $Level"
    Start-Sleep -Milliseconds 800
}

function Config-Parse {
    [CmdletBinding()]
    param([string]$Value)
    Send-Broadcast -Command "am broadcast -a $ConfigParseAction -n $Recv --es value $Value"
    Start-Sleep -Milliseconds 500
}

function Config-AlertDefault {
    [CmdletBinding()]
    param([string]$Level)
    Send-Broadcast -Command "am broadcast -a $ConfigAlertAction -n $Recv --es level $Level"
    Start-Sleep -Milliseconds 500
}

function Dump-Alert {
    [CmdletBinding()]
    param([string]$Id)
    $before = Get-LogLen
    $cmd = "am broadcast -a $DumpAlertAction -n $Recv"
    if ($Id) { $cmd = $cmd + " --el id $Id" }
    Send-Broadcast -Command $cmd
    if ($DryRun) { return '' }
    $m = Wait-ForMatch -Pattern 'DoAlarm alert [^\r\n]+' -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Dump-RepeatCount {
    [CmdletBinding()]
    param($Id, [int]$Count)
    $id = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpRepeatAction -n $Recv --el id $id --ei count $Count"
    if ($DryRun) { return '' }
    Start-Sleep -Seconds 2
    $text = Get-LogcatText
    if ($text -and $text.Length -ge $before) { return $text.Substring($before) }
    return ''
}

function Get-FiresForId {
    [CmdletBinding()]
    param($Id)
    $id = ConvertTo-Int64Scalar -Value $Id -Name 'Id'
    $text = Get-LogcatText
    $list = New-Object System.Collections.Generic.List[object]
    if ($text) {
        foreach ($m in [regex]::Matches($text, "DoAlarm fire id=$id scheduled=(\d+) actual=(\d+)")) {
            $list.Add(@{ Scheduled = [int64]$m.Groups[1].Value; Actual = [int64]$m.Groups[2].Value })
        }
    }
    return $list
}

function Wait-ForFire {
    [CmdletBinding()]
    param($Id, [int]$WaitSeconds)
    if ($DryRun) { return $true }
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $fires = @(Get-FiresForId -Id $Id)
        if ($fires.Count -gt 0) { return $true }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Get-NotifDump {
    [CmdletBinding()]
    param()
    $r = Invoke-AdbArgList -AllowFail -ArgList @('shell', 'dumpsys', 'notification', '--noredact')
    return ConvertTo-ScalarString -Value $r.Output
}

function Get-AppOpMode {
    [CmdletBinding()]
    param([string]$Op)
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "cmd appops get $Pkg $Op" -AllowFail).Output
    if ($text -match ':\s*(allow|deny|default|ignore)') { return $Matches[1] }
    return 'default'
}

function Set-AppOpMode {
    [CmdletBinding()]
    param([string]$Op, [string]$Mode)
    if (-not $Mode) { $Mode = 'default' }
    Invoke-AdbShell -Command "cmd appops set $Pkg $Op $Mode" -AllowFail | Out-Null
}

function Get-UtcEpochMs {
    [CmdletBinding()]
    param([int]$Year, [int]$Month, [int]$Day, [int]$Hour, [int]$Minute, [int]$Second)
    $utc = New-Object DateTime $Year, $Month, $Day, $Hour, $Minute, $Second, ([DateTimeKind]::Utc)
    $origin = New-Object DateTime 1970, 1, 1, 0, 0, 0, ([DateTimeKind]::Utc)
    return [int64]($utc - $origin).TotalMilliseconds
}

function Get-PhoneZone {
    [CmdletBinding()]
    param()
    $zone = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'getprop persist.sys.timezone' -AllowFail).Output
    return $zone.Trim()
}

function Get-PhoneNowMs {
    [CmdletBinding()]
    param()
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'date +%s' -AllowFail).Output
    $sec = 0L
    if ($text.Trim() -match '^\d+') { $sec = [int64]$Matches[0] }
    return $sec * 1000
}

function Finish-TestReminders {
    [CmdletBinding()]
    param()
    foreach ($id in @($script:CreatedIds)) {
        try { Done-Reminder -Id $id } catch { Write-Log ("Done failed for ${id}: " + $_.Exception.Message) }
    }
}

function Restore-Spec5Settings {
    [CmdletBinding()]
    param()
    Write-Log 'Restoring Parse Dates, default alert, FSI and DND appops'
    if ($DryRun) { return }
    try { Config-Parse -Value 'ON' } catch { Write-Log $_.Exception.Message }
    try { Config-AlertDefault -Level 'STANDARD' } catch { Write-Log $_.Exception.Message }
    if ($script:OriginalFsi) { Set-AppOpMode -Op 'USE_FULL_SCREEN_INTENT' -Mode $script:OriginalFsi }
    if ($script:OriginalDnd) { Set-AppOpMode -Op 'ACCESS_NOTIFICATION_POLICY' -Mode $script:OriginalDnd }
    Invoke-AdbShell -Command 'settings put global auto_time 1' -AllowFail | Out-Null
    Invoke-AdbShell -Command 'settings put global auto_time_zone 1' -AllowFail | Out-Null
}

function Kill-And-Relaunch {
    [CmdletBinding()]
    param()
    $before = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "pidof $Pkg" -AllowFail).Output
    Invoke-AdbShell -Command "am kill $Pkg" -AllowFail | Out-Null
    Start-Sleep -Seconds 1
    $after = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "pidof $Pkg" -AllowFail).Output
    Invoke-AdbShell -Command "am start -n $Pkg/.MainActivity" -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    $killed = [string]::IsNullOrWhiteSpace($after.Trim()) -or ($before.Trim() -ne $after.Trim())
    return $killed
}

function Run-AT02 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-02'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $now = Get-UtcEpochMs -Year 2026 -Month 11 -Day 25 -Hour 10 -Minute 3 -Second 0
    $draft = $now + (5 * 60 * 1000)
    $zone = Get-PhoneZone
    $one = Send-Parse -Text 'Collect passport next Monday 4pm' -Now "$now" -DraftDue "$draft"
    $two = Send-Parse -Text 'Buy milk' -Now "$now" -DraftDue "$draft"
    $three = Send-Parse -Text 'Bins out every Tuesday at 7am' -Now "$now" -DraftDue "$draft"
    $expectedDue = Get-UtcEpochMs -Year 2026 -Month 11 -Day 30 -Hour 8 -Minute 0 -Second 0
    $matchOk = $one -match 'match="next Monday 4pm"' -and $one -match 'title="Collect passport"' -and $one -match 'rule=OFF'
    $dueOk = $true
    if ($zone -eq 'Australia/Perth') {
        $dueOk = $one -match ("dueAt=" + $expectedDue)
    }
    $noneOk = $two -match 'parse none text="Buy milk"'
    $binsOk = $three -match 'title="Bins out"' -and $three -match 'rule=1 WEEK TUE'
    $detail = "zone=$zone match=$matchOk due=$dueOk none=$noneOk bins=$binsOk"
    if ($matchOk -and $dueOk -and $noneOk -and $binsOk) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } elseif ($zone -ne 'Australia/Perth' -and $matchOk -and $noneOk -and $binsOk) {
        Record-Result -Id $tid -Status 'RECORD' -Detail ($detail + ' dueAt checked only when the phone zone is Australia/Perth')
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT03 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-03'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $dentist = Add-ParsedReminder -Title 'Dentist 3p Thu' -InSeconds 600 -NagMinutes 0
    $bins = Add-ParsedReminder -Title 'Take bins out every day' -InSeconds 600 -NagMinutes 0
    $dump = Dump-RepeatCount -Id $bins.Id -Count 3
    $titleOk = $dentist.Slice -match 'parse match=' -and $bins.Slice -match 'title="Take bins out"'
    $daily = $dump -match 'rule=1 DAY'
    $detail = "dentist id=$($dentist.Id) due=$($dentist.DueAt); bins id=$($bins.Id) due=$($bins.DueAt) daily=$daily titleLog=$titleOk"
    if ($titleOk -and $daily -and $dentist.Id -gt 0) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT06 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-06'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $before = Get-LogLen
    Config-Parse -Value 'OFF'
    $offLog = ''
    $text = Get-LogcatText
    if ($text -and $text.Length -ge $before) { $offLog = $text.Substring($before) }
    $parsed = Send-Parse -Text 'tomorrow' -Now '' -DraftDue ''
    $killed = Kill-And-Relaunch
    $dump = Dump-Alert -Id ''
    Config-Parse -Value 'ON'
    $stillMatches = $parsed -match 'DoAlarm parse match='
    $offOk = $offLog -match 'config-parse value=OFF'
    $persisted = $dump -match 'parse=OFF'
    $detail = "offLog=$offOk parserStillMatches=$stillMatches persisted=$persisted killed=$killed"
    if ($offOk -and $stillMatches -and $persisted -and $killed) {
        Record-Result -Id $tid -Status 'PASS' -Detail ($detail + ' Editor underline is the human half.')
    } elseif ($offOk -and $stillMatches -and $persisted) {
        Record-Result -Id $tid -Status 'RECORD' -Detail ($detail + ' am kill did not drop the pid')
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT07 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-07'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Config-AlertDefault -Level 'STANDARD'
    $fresh = Dump-Alert -Id ''
    $first = Add-PlainReminder -Title 'T_s5_std' -InSeconds 600 -NagMinutes 0 -Alert ''
    $std = Dump-Alert -Id "$($first.Id)"
    Set-AlertLevel -Id $first.Id -Level 'CRITICAL'
    $crit = Dump-Alert -Id "$($first.Id)"
    Config-AlertDefault -Level 'PROMINENT'
    $second = Add-PlainReminder -Title 'T_s5_prom' -InSeconds 700 -NagMinutes 0 -Alert ''
    $prom = Dump-Alert -Id "$($second.Id)"
    $firstAgain = Dump-Alert -Id "$($first.Id)"
    $killed = Kill-And-Relaunch
    $after = Dump-Alert -Id ''
    Config-AlertDefault -Level 'STANDARD'
    $defaultOk = $fresh -match 'default=STANDARD'
    $firstStd = $std -match 'level=STANDARD' -and $std -match 'effective=STANDARD'
    $overrode = $crit -match 'level=CRITICAL'
    $secondProm = $prom -match 'level=PROMINENT'
    $kept = $firstAgain -match 'level=CRITICAL'
    $persisted = $after -match 'default=PROMINENT'
    $detail = "default=$defaultOk first=$firstStd override=$overrode second=$secondProm kept=$kept persisted=$persisted killed=$killed"
    if ($defaultOk -and $firstStd -and $overrode -and $secondProm -and $kept -and $persisted) {
        if ($killed) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
        else { Record-Result -Id $tid -Status 'RECORD' -Detail ($detail + ' am kill did not drop the pid') }
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT09 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-09'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $add = Add-PlainReminder -Title 'T_s5_fire' -InSeconds 15 -NagMinutes 0 -Alert 'STANDARD'
    $fired = Wait-ForFire -Id $add.Id -WaitSeconds 80
    Start-Sleep -Seconds 2
    $dump = Get-NotifDump
    Save-Text -Name 'AT-09-notification.txt' -Text $dump
    $hasTitle = $dump -match 'T_s5_fire'
    $channel = $dump -match 'reminders'
    $done = $dump -match 'Mark Done'
    $noFsi = -not ($dump -match 'fullScreenIntent=.+ProminentAlertActivity')
    $detail = "id=$($add.Id) fired=$fired title=$hasTitle channel=$channel done=$done noFullScreen=$noFsi. Human: ringer on and DND off should sound like today's reminders."
    if ($fired -and $hasTitle -and $done -and $noFsi) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT11 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-11'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-AppOpMode -Op 'ACCESS_NOTIFICATION_POLICY' -Mode 'deny'
    $add = Add-PlainReminder -Title 'T_s5_crit' -InSeconds 15 -NagMinutes 0 -Alert 'CRITICAL'
    $dump = Dump-Alert -Id "$($add.Id)"
    $before = Get-LogLen
    $fired = Wait-ForFire -Id $add.Id -WaitSeconds 80
    $text = Get-LogcatText
    $slice = ''
    if ($text -and $text.Length -ge $before) { $slice = $text.Substring($before) }
    $notif = Get-NotifDump
    Save-Text -Name 'AT-11-notification.txt' -Text $notif
    $dndOff = $dump -match 'dnd=false'
    $fallback = $slice -match 'alert-fallback' -and $slice -match 'requested=CRITICAL' -and $slice -match 'reason=NO_DND'
    $shown = $notif -match 'T_s5_crit'
    $detail = "id=$($add.Id) dndFalse=$dndOff fallback=$fallback fired=$fired shown=$shown. DND-on sound breakthrough is the human half."
    if ($dndOff -and $fallback -and $fired -and $shown) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT13 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-13'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-AppOpMode -Op 'USE_FULL_SCREEN_INTENT' -Mode 'deny'
    $add = Add-PlainReminder -Title 'T_s5_fsi' -InSeconds 15 -NagMinutes 0 -Alert 'PROMINENT'
    $dump = Dump-Alert -Id "$($add.Id)"
    $before = Get-LogLen
    $fired = Wait-ForFire -Id $add.Id -WaitSeconds 80
    $text = Get-LogcatText
    $slice = ''
    if ($text -and $text.Length -ge $before) { $slice = $text.Substring($before) }
    $notif = Get-NotifDump
    Save-Text -Name 'AT-13-notification.txt' -Text $notif
    $fsiOff = $dump -match 'fsi=false'
    $fallback = $slice -match 'alert-fallback' -and $slice -match 'requested=PROMINENT'
    $shown = $notif -match 'T_s5_fsi'
    $noActivity = -not ($notif -match 'ProminentAlertActivity')
    $detail = "id=$($add.Id) fsiFalse=$fsiOff fallback=$fallback fired=$fired shown=$shown noActivity=$noActivity. Unlocked heads-up sound is the human half."
    if ($fsiOff -and $fallback -and $fired -and $shown -and $noActivity) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT14 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-14'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-AppOpMode -Op 'ACCESS_NOTIFICATION_POLICY' -Mode 'allow'
    $add = Add-PlainReminder -Title 'T_s5_boot' -InSeconds 20 -NagMinutes 0 -Alert 'CRITICAL'
    $killed = Kill-And-Relaunch
    $fired = Wait-ForFire -Id $add.Id -WaitSeconds 90
    $detail = "id=$($add.Id) killed=$killed fired=$fired. Reboot-without-unlock is human and must not add a PIN. The script uses a 20s due so the process-death half does not wait five minutes."
    if ($fired -and $killed) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } elseif ($fired) {
        Record-Result -Id $tid -Status 'RECORD' -Detail ($detail + ' am kill did not drop the pid')
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Record-ManualRows {
    [CmdletBinding()]
    param()
    $rows = @(
        @{ Id = 'Do-AT-01'; Detail = 'The 50-row parser table, US numeric rejection, year and month roll-forward, and the mapped repeat phrases run in DateParserTest on CI against 18:03 AWST on Wednesday 25 Nov 2026. Not a phone row.' },
        @{ Id = 'Do-AT-04'; Detail = 'Human. Parse Dates on. Type Collect passport next Monday 4pm. Underline and Set to on slot 0. Set to strips the words, Keep puts them back, the other Quick Times stay. Set and Set & Remove after the keyboard is down. Buy milk saves with no parse.' },
        @{ Id = 'Do-AT-05'; Detail = 'Human. Save Meeting in 1h 20m without Set to. The stored title still contains the words and dueAt is now plus 5 minutes. Re-open underlines the span.' },
        @{ Id = 'Do-AT-06-editor'; Detail = 'Human half of AT-06. With Parse Dates off the editor has no underline and no Set to, and slot 0 is the ordinary Quick Time. Turn it back on afterwards. The broadcast and process-death half is scripted.' },
        @{ Id = 'Do-AT-08'; Detail = 'Human. Overflow order is Standard Alert, Critical Alert with !!!, Prominent Alert with the alarm clock, check on the current one. Critical writes immediately. The editor bell is a draft until Save. Standard has no extra mark.' },
        @{ Id = 'Do-AT-09-sound'; Detail = 'Human half of AT-09. With the ringer on and Do Not Disturb off, a Standard fire sounds like today''s reminders. The channel, actions and lack of a full-screen intent are scripted.' },
        @{ Id = 'Do-AT-10'; Detail = 'Human. Grant Do Not Disturb access if the runner cannot. Critical fires once on reminders_critical with a Critical label, Mark Done and the three snoozes, then a +1 minute nag. With total silence DND it still sounds. Turn DND off afterwards. If access cannot be granted, mark the DND half NOT RUN.' },
        @{ Id = 'Do-AT-12'; Detail = 'Human. Grant full-screen and DND. Lock the phone with no new PIN. Prominent shows a full-screen alarm that keeps sounding, with Mark Done, three snoozes and Stop. Stop does not mark done; the next nag is one interval after Stop. Mark Done and snooze option 0 behave as in Spec 2.' },
        @{ Id = 'Do-AT-13-unlocked'; Detail = 'Human half of AT-13. With full-screen granted and the phone unlocked, Prominent arrives as a heads-up or banner that keeps sounding, not a full-screen takeover. The denied-FSI fallback is scripted. Restore full-screen afterwards.' },
        @{ Id = 'Do-AT-14-reboot'; Detail = 'Human. Reboot and do not unlock. A Standard or Critical reminder still appears on the lock screen. Prominent takes over the lock screen if full-screen is granted. Do not add a PIN. If a PIN would be required, mark NOT RUN. The process-death half is scripted.' },
        @{ Id = 'Do-AT-15'; Detail = 'CI. Unit tests cover the parser table, Room v3 migration, and alert fallback selection. Release has no debug receiver. No keystore. Confirm the PR checks are green.' }
    )
    foreach ($row in $rows) {
        if (-not (Should-Run -Id $row.Id)) { continue }
        Record-Result -Id $row.Id -Status 'MANUAL' -Detail $row.Detail
    }
}

function Write-ResultsFile {
    [CmdletBinding()]
    param([string]$Overall)
    $nl = [Environment]::NewLine
    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add('# Do Spec 5 phone results')
    [void]$lines.Add('')
    [void]$lines.Add(('Generated: {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')))
    [void]$lines.Add(('Ref: `{0}`' -f $ExpectedSha))
    [void]$lines.Add(('Device: Samsung Galaxy S24+ serial `{0}`' -f $Serial))
    [void]$lines.Add(('Evidence: `{0}`' -f $script:EvidenceDir))
    [void]$lines.Add(('Overall (automated rows): **{0}**' -f $Overall))
    [void]$lines.Add('')
    [void]$lines.Add('| ID | Status | Detail |')
    [void]$lines.Add('|---|---|---|')
    foreach ($row in $script:Results) {
        $detail = ([string]$row.Detail).Replace('|', '/')
        [void]$lines.Add(('| {0} | {1} | {2} |' -f $row.Id, $row.Status, $detail))
    }
    [void]$lines.Add('')
    [void]$lines.Add('MANUAL and RECORD rows do not fail the automated score. Restore Parse Dates on, default alert Standard, and do not leave Do Not Disturb or full-screen permission in a surprising state.')
    $text = [string]::Join($nl, $lines) + $nl
    $dir = Split-Path $ResultsPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    Set-Content -Path $ResultsPath -Value $text -Encoding UTF8
    if ($script:EvidenceDir) {
        Copy-Item $ResultsPath (Join-Path $script:EvidenceDir 'results-spec-05.md') -Force
    }
    Write-Log "Wrote $ResultsPath"
}

$script:EvidenceRoot = 'C:\Users\tdrev\do-qa\qa-evidence'
$script:EvidenceDir = Join-Path $script:EvidenceRoot ("Do-spec05-" + $Stamp)

try {
    if ($DryRun) {
        Write-Log 'DryRun is on: no git checkout, no install, no phone changes.'
        $script:EvidenceDir = Join-Path $env:TEMP ("Do-spec05-dryrun-" + $Stamp)
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
    Ensure-Checkout
    if ($DryRun) {
        Write-Log 'DryRun command plan: checkout, assembleDebug, install, PARSE, ADD parse, CONFIG_PARSE, DUMP_ALERT, SET_ALERT_LEVEL, CONFIG_ALERT_DEFAULT, Standard fire, Critical without DND, Prominent without FSI, process death.'
        Record-Result -Id 'Do-runner' -Status 'SKIP' -Detail 'DryRun only.'
        Record-ManualRows
        return
    }
    Invoke-BuildAndInstall
    $script:OriginalFsi = Get-AppOpMode -Op 'USE_FULL_SCREEN_INTENT'
    $script:OriginalDnd = Get-AppOpMode -Op 'ACCESS_NOTIFICATION_POLICY'
    Write-Log "Captured appops FSI=$($script:OriginalFsi) DND=$($script:OriginalDnd)"
    Start-DoLogcat
    Run-AT02
    Run-AT03
    Run-AT06
    Run-AT07
    Run-AT09
    Run-AT11
    Run-AT13
    Run-AT14
    Record-ManualRows
}
catch {
    Write-Log ("ERROR: " + $_.Exception.Message)
    Record-Result -Id 'Do-runner' -Status 'FAIL' -Detail $_.Exception.Message
    throw
}
finally {
    try { Finish-TestReminders } catch { Write-Log ("Cleanup failed: " + $_.Exception.Message) }
    try { Restore-Spec5Settings } catch { Write-Log ("Restore failed: " + $_.Exception.Message) }
    Stop-DoLogcat
    $autoFails = @($script:Results | Where-Object { $_.Status -eq 'FAIL' })
    $overall = 'PASS'
    if ($autoFails.Count -gt 0) { $overall = 'FAIL' }
    try { Write-ResultsFile -Overall $overall } catch { Write-Log ("Could not write results: " + $_.Exception.Message) }
    Write-Log "Done. Overall (automated rows)=$overall. Results: $ResultsPath"
}
