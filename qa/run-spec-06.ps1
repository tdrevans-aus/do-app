#Requires -Version 5.1
<#
.SYNOPSIS
  Do Spec 6 phone QA runner for Tim's Windows 11 PC (PowerShell 5.1).

.DESCRIPTION
  Clones or fetches tdrevans-aus/do-app into C:\Users\tdrev\do-qa\repo, checks out
  the commit in -Ref (default do/spec-6-health-check), builds the default USE
  debug APK, installs it on the Samsung Galaxy S24+ (R5CXC0FWWVN), and runs the
  Spec 6 acceptance tests that the debug broadcasts can reach.
  Evidence goes to C:\Users\tdrev\do-qa\qa-evidence\Do-spec06-<stamp>\.
  Results are written to C:\Users\tdrev\do-qa\results-spec-06.md.

  Due times and dumps that include a time are compared truncated to the local
  minute. A health-test fire must land 0 to 60 seconds after its scheduled time.
  A fire before the scheduled time is always a FAIL.

  Human ATs (the yellow menu mark, the health-screen layout, Samsung Fix and
  Learn more, the exact-alarm banner, and reboot receipts) are listed as MANUAL.
  This script does not replace run-spec-05.ps1.

  Do-runner.log in the evidence folder is the authoritative live log.

  ALWAYS restores the phone in a finally block: notification permission, any
  channel importance this run changed, battery-whitelist membership if it was
  changed (this run does not toggle it), background-restriction appops, the
  standby bucket, leftover T_s6_* and "Health check test" reminders (Repeat Off,
  then Mark Done), zen_mode, and the Sleeping / EVERY_NIGHT_DEFAULT_RULE schedule
  if this run snoozed it. Snooze uses OVERRIDE_DEACTIVATE; restore re-activates
  the rule. Restoring only zen_mode is not enough. The restore is logged.

  Never sets, changes or removes a screen-lock PIN, password or pattern.
  Lock-screen items stay manual. Never factory-resets, never changes the clock
  or time zone, never turns on silent mode, and never uses force-stop. If am kill
  leaves the process running, the row is RECORD, not FAIL.

.PARAMETER DryRun
  Print the commands without changing the phone or writing a checkout.

.PARAMETER Only
  Optional test ID filter, e.g. -Only AT-02,AT-05

.PARAMETER Ref
  Git commit or ref to check out in C:\Users\tdrev\do-qa\repo. Default is the
  Spec 6 branch. A short SHA, full SHA, or branch name is accepted.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string[]]$Only,
    [string]$Ref = 'do/spec-6-health-check'
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
$SetRepeatAction = 'com.evansappstudio.doapp.debug.SET_REPEAT'
$DumpHealthAction = 'com.evansappstudio.doapp.debug.DUMP_HEALTH'
$TestHealthAction = 'com.evansappstudio.doapp.debug.TEST_HEALTH'
$NoteLateAction = 'com.evansappstudio.doapp.debug.NOTE_LATE'
$DumpAlertAction = 'com.evansappstudio.doapp.debug.DUMP_ALERT'
$ResultsPath  = 'C:\Users\tdrev\do-qa\results-spec-06.md'
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
$script:Adb = $AdbPath
$script:OriginalNotif = $null
$script:OriginalFsi = $null
$script:OriginalDnd = $null
$script:OriginalBg = $null
$script:OriginalBgIn = $null
$script:OriginalStandby = $null
$script:OriginalZen = $null
$script:SleepingSnoozed = $false
$script:BatteryChanged = $false
$script:OriginalBatteryListed = $null
$script:ChannelsChanged = @{}

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

function Resume-App {
    [CmdletBinding()]
    param()
    Invoke-AdbShell -Command "am start -n $Pkg/.MainActivity" -AllowFail | Out-Null
    if (-not $DryRun) { Start-Sleep -Seconds 2 }
}

function Dump-Health {
    [CmdletBinding()]
    param()
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpHealthAction -n $Recv"
    if ($DryRun) { return 'DoAlarm health notif=OK channels=OK exact=OK:USE fsi=OK dnd=OK battery=WARN bgRestrict=OK samsung=CHECK standby=OK:Active:10 boot=OK unlocked=true lastLate=none test=none late=OK' }
    $m = Wait-ForMatch -Pattern 'DoAlarm health [^\r\n]+' -BeforeLen $before -WaitSeconds 20
    if ($m) { return $m.Value }
    return ''
}

function Dump-Alert {
    [CmdletBinding()]
    param()
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpAlertAction -n $Recv"
    if ($DryRun) { return 'DoAlarm alert default=STANDARD fsi=true dnd=false parse=ON' }
    $m = Wait-ForMatch -Pattern 'DoAlarm alert [^\r\n]+' -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Send-NoteLate {
    [CmdletBinding()]
    param([int64]$LagMs)
    Send-Broadcast -Command "am broadcast -a $NoteLateAction -n $Recv --el lagMs $LagMs"
    if (-not $DryRun) { Start-Sleep -Milliseconds 800 }
}

function Add-PlainReminder {
    [CmdletBinding()]
    param([string]$Title, [int]$InSeconds, [int]$NagMinutes)
    $before = Get-LogLen
    $safe = $Title.Replace("'", '')
    $cmd = "am broadcast -a $AddAction -n $Recv --es title '$safe' --ei inSeconds $InSeconds --ei nagMinutes $NagMinutes"
    Send-Broadcast -Command $cmd
    if ($DryRun) { return @{ Id = 0; DueAt = 0 } }
    $m = Wait-ForMatch -Pattern 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)' -BeforeLen $before -WaitSeconds 20
    if (-not $m) { throw "Debug ADD did not log for $Title" }
    $info = @{
        Id = (Remember-Id -Id $m.Groups[1].Value)
        DueAt = [int64]$m.Groups[2].Value
        Title = $Title
    }
    Write-Log ("ADD {0} id={1} dueAt={2}" -f $Title, $info.Id, $info.DueAt)
    return $info
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

function Get-NotifGranted {
    [CmdletBinding()]
    param()
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "dumpsys package $Pkg" -AllowFail).Output
    $found = [regex]::Matches($text, 'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)')
    if ($found.Count -gt 0) { return $found[$found.Count - 1].Groups[1].Value -eq 'true' }
    return $true
}

function Set-NotifGranted {
    [CmdletBinding()]
    param([bool]$Granted)
    if ($Granted) {
        Invoke-AdbShell -Command "pm grant $Pkg android.permission.POST_NOTIFICATIONS" -AllowFail | Out-Null
    } else {
        Invoke-AdbShell -Command "pm revoke $Pkg android.permission.POST_NOTIFICATIONS" -AllowFail | Out-Null
    }
}

function Get-StandbyBucket {
    [CmdletBinding()]
    param()
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "am get-standby-bucket $Pkg" -AllowFail).Output
    return $text.Trim()
}

function ConvertTo-StandbyName {
    [CmdletBinding()]
    param([string]$Raw)
    $t = ''
    if ($Raw) { $t = $Raw.Trim().ToLower() }
    if ($t -match 'working') { return 'working_set' }
    if ($t -match 'frequent' -or $t -eq '30') { return 'frequent' }
    if ($t -match 'restricted' -or $t -eq '45') { return 'restricted' }
    if ($t -match 'never' -or $t -eq '50') { return 'never' }
    if ($t -match 'rare' -or $t -eq '40') { return 'rare' }
    if ($t -match 'active' -or $t -eq '10') { return 'active' }
    if ($t -match 'exempt' -or $t -eq '5') { return 'active' }
    return 'active'
}

function Set-StandbyBucket {
    [CmdletBinding()]
    param([string]$Bucket)
    Invoke-AdbShell -Command "am set-standby-bucket $Pkg $Bucket" -AllowFail | Out-Null
}

function Get-NotificationDump {
    [CmdletBinding()]
    param()
    ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'dumpsys notification --noredact' -AllowFail).Output
}

function Get-ChannelImportance {
    [CmdletBinding()]
    param([string]$Channel, [string]$Dump)
    if (-not $Dump) { return $null }
    $m = [regex]::Match($Dump, "mId='$Channel'[\s\S]{0,400}?mImportance=(\d+)")
    if ($m.Success) { return [int]$m.Groups[1].Value }
    $m2 = [regex]::Match($Dump, "id=$Channel\b[\s\S]{0,200}?importance=(\d+)")
    if ($m2.Success) { return [int]$m2.Groups[1].Value }
    return $null
}

function Set-ChannelImportance {
    [CmdletBinding()]
    param([string]$Channel, [int]$Importance)
    $commands = @(
        "cmd notification set_importance $Pkg $Channel $Importance",
        "cmd notification set_channel_importance $Pkg $Channel $Importance"
    )
    foreach ($cmd in $commands) {
        $result = Invoke-AdbShell -Command $cmd -AllowFail
        $text = ConvertTo-ScalarString -Value $result.Output
        if ($result.ExitCode -eq 0 -and $text -notmatch 'Unknown command|No shell command|Error:') {
            $dump = Get-NotificationDump
            $now = Get-ChannelImportance -Channel $Channel -Dump $dump
            if ($now -eq $Importance) { return $true }
        }
    }
    return $false
}

function Test-BatteryWhitelisted {
    [CmdletBinding()]
    param()
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'dumpsys deviceidle whitelist' -AllowFail).Output
    if (-not $text.Trim()) {
        $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'cmd deviceidle whitelist' -AllowFail).Output
    }
    return ($text -match [regex]::Escape($Pkg))
}

function Get-ZenMode {
    [CmdletBinding()]
    param()
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'settings get global zen_mode' -AllowFail).Output
    return $text.Trim()
}

function Get-SleepingRuleText {
    [CmdletBinding()]
    param()
    $listed = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'cmd notification list_automatic_zen_rules' -AllowFail).Output
    if ($listed.Trim()) { return $listed }
    $dump = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'dumpsys notification' -AllowFail).Output
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
    Write-Log "Restoring DND rule ${RuleId}: OVERRIDE_DEACTIVATE, then re-activate. zen_mode alone is not enough."
    Invoke-AdbShell -Command "cmd notification set_automatic_zen_rule_state $RuleId OVERRIDE_DEACTIVATE" -AllowFail | Out-Null
    Invoke-AdbShell -Command "cmd notification set_automatic_zen_rule_state $RuleId enabled" -AllowFail | Out-Null
    Invoke-AdbShell -Command "cmd notification set_automatic_zen_rule_state $RuleId true" -AllowFail | Out-Null
}

function Get-HealthTestIds {
    [CmdletBinding()]
    param()
    $text = Get-LogcatText
    $ids = New-Object System.Collections.Generic.List[int64]
    if (-not $text) { return $ids }
    foreach ($m in [regex]::Matches($text, 'health-test (?:scheduled=\d+ id=|done id=|id=)(\d+)')) {
        $id = [int64]$m.Groups[1].Value
        if (-not $ids.Contains($id)) { $ids.Add($id) }
    }
    return $ids
}

function ConvertTo-LocalMinute {
    [CmdletBinding()]
    param([int64]$EpochMs)
    $dto = [DateTimeOffset]::FromUnixTimeMilliseconds($EpochMs).ToLocalTime()
    return Get-Date -Year $dto.Year -Month $dto.Month -Day $dto.Day -Hour $dto.Hour -Minute $dto.Minute -Second 0
}

function Test-SameLocalMinute {
    [CmdletBinding()]
    param([int64]$LeftMs, [int64]$RightMs)
    $left = ConvertTo-LocalMinute -EpochMs $LeftMs
    $right = ConvertTo-LocalMinute -EpochMs $RightMs
    return ($left -eq $right)
}

function Get-PhoneNowMs {
    [CmdletBinding()]
    param()
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'date +%s' -AllowFail).Output
    $sec = 0L
    if ($text.Trim() -match '^\d+') { $sec = [int64]$Matches[0] }
    return $sec * 1000
}

function Get-Field {
    [CmdletBinding()]
    param([string]$Dump, [string]$Key)
    if (-not $Dump) { return '' }
    $m = [regex]::Match($Dump, "(?:^|\s)$Key=([^\s]+)")
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
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

function Capture-PhoneState {
    [CmdletBinding()]
    param()
    if ($DryRun) {
        Write-Log 'DryRun: would capture notification permission, channels, appops, standby, battery whitelist, zen_mode and the Sleeping rule'
        return
    }
    $script:OriginalNotif = Get-NotifGranted
    $script:OriginalFsi = Get-AppOpMode -Op 'USE_FULL_SCREEN_INTENT'
    $script:OriginalDnd = Get-AppOpMode -Op 'ACCESS_NOTIFICATION_POLICY'
    $script:OriginalBg = Get-AppOpMode -Op 'RUN_ANY_IN_BACKGROUND'
    $script:OriginalBgIn = Get-AppOpMode -Op 'RUN_IN_BACKGROUND'
    $script:OriginalStandby = Get-StandbyBucket
    $script:OriginalBatteryListed = Test-BatteryWhitelisted
    $script:OriginalZen = Get-ZenMode
    $rules = Get-SleepingRuleText
    Save-Text -Name 'sleeping-dnd-before.txt' -Text $rules
    Write-Log ("Captured notif={0} fsi={1} dnd={2} bg={3}/{4} standby={5} batteryListed={6} zen={7}" -f `
        $script:OriginalNotif, $script:OriginalFsi, $script:OriginalDnd, $script:OriginalBg, $script:OriginalBgIn, `
        $script:OriginalStandby, $script:OriginalBatteryListed, $script:OriginalZen)
    Write-Log 'Sleeping / EVERY_NIGHT_DEFAULT_RULE snapshot saved. This run does not set a screen-lock PIN.'
}

function Restore-PhoneState {
    [CmdletBinding()]
    param()
    Write-Log 'Restoring phone state'
    if ($DryRun) { return }
    try {
        if ($null -ne $script:OriginalNotif) { Set-NotifGranted -Granted $script:OriginalNotif }
    } catch { Write-Log $_.Exception.Message }
    foreach ($channel in @($script:ChannelsChanged.Keys)) {
        try {
            $importance = [int]$script:ChannelsChanged[$channel]
            Set-ChannelImportance -Channel $channel -Importance $importance | Out-Null
            Write-Log "Restored channel $channel importance=$importance"
        } catch { Write-Log $_.Exception.Message }
    }
    try { if ($script:OriginalFsi) { Set-AppOpMode -Op 'USE_FULL_SCREEN_INTENT' -Mode $script:OriginalFsi } } catch { Write-Log $_.Exception.Message }
    try { if ($script:OriginalDnd) { Set-AppOpMode -Op 'ACCESS_NOTIFICATION_POLICY' -Mode $script:OriginalDnd } } catch { Write-Log $_.Exception.Message }
    try { if ($script:OriginalBg) { Set-AppOpMode -Op 'RUN_ANY_IN_BACKGROUND' -Mode $script:OriginalBg } } catch { Write-Log $_.Exception.Message }
    try { if ($script:OriginalBgIn) { Set-AppOpMode -Op 'RUN_IN_BACKGROUND' -Mode $script:OriginalBgIn } } catch { Write-Log $_.Exception.Message }
    try {
        if ($script:OriginalStandby) {
            $name = ConvertTo-StandbyName -Raw $script:OriginalStandby
            Set-StandbyBucket -Bucket $name
            Write-Log "Restored standby bucket to $name (captured '$($script:OriginalStandby)')"
        }
    } catch { Write-Log $_.Exception.Message }
    if ($script:BatteryChanged) {
        Write-Log 'Battery whitelist was changed; restore it to the captured membership before finishing.'
    } else {
        Write-Log 'Battery optimisation whitelist was not changed.'
    }
    try {
        if ($script:SleepingSnoozed) {
            Restore-SleepingRule -RuleId 'EVERY_NIGHT_DEFAULT_RULE'
            $rules = Get-SleepingRuleText
            foreach ($m in [regex]::Matches($rules, 'id=([^\s,]+)')) {
                if ($m.Groups[1].Value -match 'Sleep|EVERY_NIGHT') {
                    Restore-SleepingRule -RuleId $m.Groups[1].Value
                }
            }
        } else {
            Write-Log 'Sleeping DND rule was not snoozed; left unchanged.'
        }
        $zenNow = Get-ZenMode
        if ($script:OriginalZen -and $zenNow -ne $script:OriginalZen) {
            Invoke-AdbShell -Command "settings put global zen_mode $($script:OriginalZen)" -AllowFail | Out-Null
            Write-Log "Restored zen_mode from $zenNow to $($script:OriginalZen)"
        } else {
            Write-Log "zen_mode left at '$zenNow' (captured '$($script:OriginalZen)')"
        }
    } catch { Write-Log $_.Exception.Message }
    try { Send-NoteLate -LagMs 0 } catch { Write-Log $_.Exception.Message }
    Write-Log 'Restore pass finished. No PIN, clock, time zone, silent mode or force-stop was used.'
}

function Finish-TestReminders {
    [CmdletBinding()]
    param()
    $ids = New-Object System.Collections.Generic.List[int64]
    foreach ($id in @($script:CreatedIds)) { if (-not $ids.Contains($id)) { $ids.Add($id) } }
    foreach ($id in @(Get-HealthTestIds)) { if (-not $ids.Contains($id)) { $ids.Add($id) } }
    foreach ($id in @($ids)) {
        try { Finish-OneReminder -Id $id } catch { Write-Log ("Done failed for ${id}: " + $_.Exception.Message) }
    }
    Write-Log ("Marked done (Repeat Off first) for ids: " + ($(if ($ids.Count) { $ids -join ',' } else { 'none' })))
}

function Run-AT02 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-02'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Send-NoteLate -LagMs 0
    Resume-App
    $dump = Dump-Health
    Save-Text -Name 'AT-02-dump.txt' -Text $dump
    $keys = @('notif=','channels=','exact=','fsi=','dnd=','battery=','bgRestrict=','samsung=','standby=','boot=','unlocked=','lastLate=','test=')
    $missing = @()
    foreach ($key in $keys) { if ($dump -notmatch [regex]::Escape($key)) { $missing += $key } }
    $exact = Get-Field -Dump $dump -Key 'exact'
    $samsung = Get-Field -Dump $dump -Key 'samsung'
    $late = Get-Field -Dump $dump -Key 'lastLate'
    $fsi = Get-Field -Dump $dump -Key 'fsi'
    $dnd = Get-Field -Dump $dump -Key 'dnd'
    $detail = "exact=$exact samsung=$samsung lastLate=$late fsi=$fsi dnd=$dnd missing=$($missing -join ',')"
    if ($missing.Count -eq 0 -and $exact -eq 'OK:USE' -and $samsung -eq 'CHECK' -and $late -eq 'none' -and ($fsi -eq 'OK' -or $fsi -eq 'WARN') -and ($dnd -eq 'OK' -or $dnd -eq 'WARN')) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
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
    Set-NotifGranted -Granted $false
    Resume-App
    $blocked = Dump-Health
    Set-NotifGranted -Granted $true
    Resume-App
    $ok = Dump-Health
    $blockedNotif = (Get-Field -Dump $blocked -Key 'notif') -eq 'BLOCKED'
    $okNotif = (Get-Field -Dump $ok -Key 'notif') -eq 'OK'
    $detail = "revoked=$blockedNotif regranted=$okNotif. Human: the yellow mark follows the banner, and the banner is gone once notifications are allowed unless another Warning remains."
    if ($blockedNotif -and $okNotif) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT04 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-04'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $dump0 = Get-NotificationDump
    $original = Get-ChannelImportance -Channel 'reminders' -Dump $dump0
    $summaryOriginal = Get-ChannelImportance -Channel 'reminders_summary' -Dump $dump0
    if ($null -eq $original) { $original = 4 }
    if ($null -eq $summaryOriginal) { $summaryOriginal = 2 }
    $script:ChannelsChanged['reminders'] = $original
    $script:ChannelsChanged['reminders_summary'] = $summaryOriginal
    $summarySet = Set-ChannelImportance -Channel 'reminders_summary' -Importance 0
    Resume-App
    $summaryDump = Dump-Health
    Set-ChannelImportance -Channel 'reminders_summary' -Importance $summaryOriginal | Out-Null
    $muted = Set-ChannelImportance -Channel 'reminders' -Importance 0
    Resume-App
    $mutedDump = Dump-Health
    Set-ChannelImportance -Channel 'reminders' -Importance $original | Out-Null
    Resume-App
    $restored = Dump-Health
    if (-not $muted) {
        Record-Result -Id $tid -Status 'MANUAL' -Detail 'The S24+ did not accept a channel importance change via cmd notification. Mute Reminders by hand, confirm channels=BLOCKED, confirm reminders_summary alone stays OK, then restore high importance. Fix opens the Reminders channel or app notification settings.'
        return
    }
    $summaryOk = (Get-Field -Dump $summaryDump -Key 'channels') -eq 'OK'
    $blocked = (Get-Field -Dump $mutedDump -Key 'channels') -eq 'BLOCKED'
    $back = (Get-Field -Dump $restored -Key 'channels') -eq 'OK'
    $detail = "summarySet=$summarySet summaryStaysOk=$summaryOk muted=$blocked restored=$back original=$original. Human: Fix opens the Reminders channel or app notification settings."
    if ((-not $summarySet -or $summaryOk) -and $blocked -and $back) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT05 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-05'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-AppOpMode -Op 'RUN_ANY_IN_BACKGROUND' -Mode 'ignore'
    Set-AppOpMode -Op 'RUN_IN_BACKGROUND' -Mode 'ignore'
    Resume-App
    $blocked = Dump-Health
    Set-AppOpMode -Op 'RUN_ANY_IN_BACKGROUND' -Mode $(if ($script:OriginalBg) { $script:OriginalBg } else { 'allow' })
    Set-AppOpMode -Op 'RUN_IN_BACKGROUND' -Mode $(if ($script:OriginalBgIn) { $script:OriginalBgIn } else { 'allow' })
    Resume-App
    $ok = Dump-Health
    $isBlocked = (Get-Field -Dump $blocked -Key 'bgRestrict') -eq 'BLOCKED'
    $isOk = (Get-Field -Dump $ok -Key 'bgRestrict') -eq 'OK'
    $detail = "restricted=$isBlocked restored=$isOk"
    if ($isBlocked -and $isOk) {
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
    Set-StandbyBucket -Bucket 'restricted'
    Resume-App
    $restricted = Dump-Health
    Set-StandbyBucket -Bucket 'rare'
    Resume-App
    $rare = Dump-Health
    $restore = ConvertTo-StandbyName -Raw $script:OriginalStandby
    Set-StandbyBucket -Bucket $restore
    $standbyRestricted = Get-Field -Dump $restricted -Key 'standby'
    $standbyRare = Get-Field -Dump $rare -Key 'standby'
    $warn = $standbyRestricted -match '^WARN:Restricted:'
    $ok = $standbyRare -match '^OK:Rare:'
    $detail = "restricted=$standbyRestricted rare=$standbyRare restore=$restore"
    if ($warn -and $ok) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
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
    $listed = Test-BatteryWhitelisted
    Resume-App
    $dump = Dump-Health
    $battery = Get-Field -Dump $dump -Key 'battery'
    $agrees = ($listed -and $battery -eq 'OK') -or ((-not $listed) -and $battery -eq 'WARN')
    $detail = "whitelisted=$listed battery=$battery. The runner does not toggle the exemption. Human: Fix opens the system Battery optimisation list, not a one-tap dialog, and the app does not show ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS."
    if ($agrees) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT08 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-08'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Resume-App
    $dump = Dump-Health
    $exact = Get-Field -Dump $dump -Key 'exact'
    $detail = "exact=$exact. Human: on this USE build the list banner stays hidden. A SCHEDULE build is not installed by this runner."
    if ($exact -eq 'OK:USE') {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
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
    $alert = Dump-Alert
    $health = Dump-Health
    $alertFsi = $alert -match 'fsi=true'
    $alertFsiKnown = $alert -match 'fsi=(true|false)'
    $alertDnd = $alert -match 'dnd=true'
    $alertDndKnown = $alert -match 'dnd=(true|false)'
    $healthFsi = Get-Field -Dump $health -Key 'fsi'
    $healthDnd = Get-Field -Dump $health -Key 'dnd'
    $fsiAgrees = $alertFsiKnown -and (($alertFsi -and $healthFsi -eq 'OK') -or ((-not $alertFsi) -and $healthFsi -eq 'WARN'))
    $dndAgrees = $alertDndKnown -and (($alertDnd -and $healthDnd -eq 'OK') -or ((-not $alertDnd) -and $healthDnd -eq 'WARN'))
    Set-AppOpMode -Op 'USE_FULL_SCREEN_INTENT' -Mode 'deny'
    Resume-App
    $denied = Dump-Health
    $add = Add-PlainReminder -Title 'T_s6_std' -InSeconds 20 -NagMinutes 0
    $fired = $false
    if (-not $DryRun) {
        $deadline = (Get-Date).AddSeconds(80)
        do {
            $text = Get-LogcatText
            if ($text -and $text -match ("DoAlarm fire id=" + $add.Id + " scheduled=(\d+) actual=(\d+)")) { $fired = $true }
            if ($fired) { break }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
    }
    $fsiWarn = (Get-Field -Dump $denied -Key 'fsi') -eq 'WARN'
    $detail = "fsiAgrees=$fsiAgrees dndAgrees=$dndAgrees fsiWarn=$fsiWarn standardFired=$fired id=$($add.Id). Human: Fix opens the matching system screen. Badge is visual."
    if ($fsiAgrees -and $dndAgrees -and $fsiWarn -and $fired) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Run-AT10 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-10'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Send-NoteLate -LagMs 60001
    Resume-App
    $on = Dump-Health
    Send-NoteLate -LagMs 0
    Resume-App
    $off = Dump-Health
    $late = Get-Field -Dump $on -Key 'lastLate'
    $warn = (Get-Field -Dump $on -Key 'late') -eq 'WARN'
    $cleared = (Get-Field -Dump $off -Key 'lastLate') -eq 'none'
    $clearOk = (Get-Field -Dump $off -Key 'late') -eq 'OK'
    $detail = "lastLate=$late late=$warn cleared=$cleared clearStatus=$clearOk. Human: the yellow mark is on the menu and the health row mentions a late fire. The 60000 ms boundary is a unit test."
    if ($late -eq '60001' -and $warn -and $cleared -and $clearOk) {
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
    $phoneNow = Get-PhoneNowMs
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $TestHealthAction -n $Recv"
    $first = Wait-ForMatch -Pattern 'DoAlarm health-test scheduled=(\d+) id=(\d+)' -BeforeLen $before -WaitSeconds 20
    if (-not $first) {
        Record-Result -Id $tid -Status 'FAIL' -Detail 'TEST_HEALTH did not log a scheduled time'
        return
    }
    $scheduled = [int64]$first.Groups[1].Value
    $id = [int64]$first.Groups[2].Value
    Remember-Id -Id $id | Out-Null
    $secondBefore = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $TestHealthAction -n $Recv"
    $second = Wait-ForMatch -Pattern 'DoAlarm health-test scheduled=(\d+) id=(\d+)' -BeforeLen $secondBefore -WaitSeconds 20
    $secondId = 0L
    $secondScheduled = 0L
    if ($second) {
        $secondScheduled = [int64]$second.Groups[1].Value
        $secondId = [int64]$second.Groups[2].Value
    }
    $fire = Wait-ForMatch -Pattern ("DoAlarm health-test id=$id scheduled=(\d+) actual=(\d+)") -BeforeLen $before -WaitSeconds 100
    $done = Wait-ForMatch -Pattern ("DoAlarm health-test done id=$id") -BeforeLen $before -WaitSeconds 15
    $actual = 0L
    $fireScheduled = $scheduled
    if ($fire) {
        $fireScheduled = [int64]$fire.Groups[1].Value
        $actual = [int64]$fire.Groups[2].Value
    }
    $lag = $actual - $fireScheduled
    $early = $fire -and ($lag -lt 0)
    $inWindow = $fire -and ($lag -ge 0) -and ($lag -le $PhoneTolMs)
    $expected = $phoneNow + 60000
    $sameMinute = Test-SameLocalMinute -LeftMs $scheduled -RightMs $expected
    $aboutAMinute = [math]::Abs($scheduled - $expected) -lt 90000
    $oneId = $second -and ($secondId -eq $id) -and ($secondScheduled -eq $scheduled)
    $ids = @(Get-HealthTestIds)
    $detail = "id=$id second=$secondId scheduled=$scheduled actual=$actual lagMs=$lag sameMinute=$sameMinute aboutAMinute=$aboutAMinute oneId=$oneId done=$([bool]$done) ids=$($ids -join ',')"
    if ($early) {
        Record-Result -Id $tid -Status 'FAIL' -Detail ("early fire. " + $detail)
        return
    }
    if ($inWindow -and $sameMinute -and $aboutAMinute -and $oneId -and $done -and $ids.Count -eq 1) {
        Record-Result -Id $tid -Status 'PASS' -Detail ($detail + ' Human: the on-screen button is the same path. The list should not keep Health check test.')
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
    Set-NotifGranted -Granted $false
    $killed = Kill-And-Relaunch
    $dump = Dump-Health
    $blocked = (Get-Field -Dump $dump -Key 'notif') -eq 'BLOCKED'
    $detail = "killed=$killed notifBlocked=$blocked"
    if ($blocked -and $killed) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } elseif ($blocked) {
        Record-Result -Id $tid -Status 'RECORD' -Detail ($detail + ' am kill did not drop the pid; the resume dump still matched.')
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
}

function Record-ManualRows {
    [CmdletBinding()]
    param()
    $rows = @(
        @{ Id = 'Do-AT-01'; Status = 'MANUAL'; Detail = 'CI. HealthEvaluatorTest covers every section 3B status, the 60000 ms late boundary, Samsung-only row presence, the silent summary channel, and the battery Fix action. checkAlarmManifests rejects REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and a release debug receiver. Not a phone row.' },
        @{ Id = 'Do-AT-12'; Status = 'MANUAL'; Detail = 'Human. With every detected item OK and no late record, the menu button has no yellow mark. The menu still has Health check. The screen lists every section 3B item, including the Samsung Check row on this S24+. Readable at the default font. Compare the yellow triangle with due-0.jpg to due-3.jpg, not a pixel match. Battery optimisation may already be a Warning on a fresh install; record that starting state.' },
        @{ Id = 'Do-AT-13'; Status = 'MANUAL'; Detail = 'Human. On the S24+ the Samsung row is Check and does not by itself light the badge. The explanation names Sleeping apps, Deep sleeping apps, Never sleeping apps and Put unused apps to sleep, and keeps the Settings, Battery and device care, Battery, Background usage limits path. Fix opens Battery or Background usage limits when that screen resolves, otherwise system Settings. Learn more opens https://dontkillmyapp.com/samsung. Whether Do is already on a Samsung list is a record, not a pass or fail.' },
        @{ Id = 'Do-AT-15'; Status = 'NOT RUN'; Detail = 'Human, same PIN rule as Spec 1. Do not create a screen-lock PIN, password or pattern. With at least one active reminder, reboot and unlock. DUMP_HEALTH should show boot=OK and the reminder should still have an alarm-clock alarm. If this cannot be run without creating a PIN, the pre-unlock half stays NOT RUN. Do not fail solely because Samsung skipped LOCKED_BOOT_COMPLETED when the unlock receipt arrived and alarms were restored. Log what the device sent.' },
        @{ Id = 'Do-AT-16'; Status = 'MANUAL'; Detail = 'CI. assembleDebug, testDebugUnitTest, lintDebug and checkAlarmManifests for the default USE mode and for -PexactAlarmMode=SCHEDULE. Release has no debug receiver and no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. No keystore.' }
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
    [void]$lines.Add('# Do Spec 6 phone results')
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
    [void]$lines.Add('MANUAL, RECORD and NOT RUN rows do not fail the automated score. Restore notification permission, channels, appops, the standby bucket, zen_mode and the Sleeping rule if it was snoozed. Do not leave a Health check test reminder active. Do not add a PIN.')
    $text = [string]::Join($nl, $lines) + $nl
    $dir = Split-Path $ResultsPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    Set-Content -Path $ResultsPath -Value $text -Encoding UTF8
    if ($script:EvidenceDir) {
        Copy-Item $ResultsPath (Join-Path $script:EvidenceDir 'results-spec-06.md') -Force
    }
    Write-Log "Wrote $ResultsPath"
}

$script:EvidenceRoot = 'C:\Users\tdrev\do-qa\qa-evidence'
$script:EvidenceDir = Join-Path $script:EvidenceRoot ("Do-spec06-" + $Stamp)

try {
    if ($DryRun) {
        Write-Log 'DryRun is on: no git checkout, no install, no phone changes.'
        $script:EvidenceDir = Join-Path $env:TEMP ("Do-spec06-dryrun-" + $Stamp)
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
        Write-Log 'DryRun command plan: checkout, assembleDebug, install, DUMP_HEALTH, revoke notifications, channel importance, background appops, standby bucket, battery read, FSI deny with a Standard fire, NOTE_LATE, TEST_HEALTH, resume.'
        Record-Result -Id 'Do-runner' -Status 'SKIP' -Detail 'DryRun only.'
        Record-ManualRows
        return
    }
    Invoke-BuildAndInstall
    Capture-PhoneState
    Start-DoLogcat
    Run-AT02
    Run-AT03
    Run-AT04
    Run-AT05
    Run-AT06
    Run-AT07
    Run-AT08
    Run-AT09
    Run-AT10
    Run-AT11
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
    try { Restore-PhoneState } catch { Write-Log ("Restore failed: " + $_.Exception.Message) }
    Stop-DoLogcat
    $autoFails = @($script:Results | Where-Object { $_.Status -eq 'FAIL' })
    $overall = 'PASS'
    if ($autoFails.Count -gt 0) { $overall = 'FAIL' }
    try { Write-ResultsFile -Overall $overall } catch { Write-Log ("Could not write results: " + $_.Exception.Message) }
    Write-Log "Done. Overall (automated rows)=$overall. Results: $ResultsPath"
}
