#Requires -Version 5.1
<#
.SYNOPSIS
  Do Spec 7 phone QA runner for Tim's Windows 11 PC (PowerShell 5.1).

.DESCRIPTION
  Clones or fetches tdrevans-aus/do-app into C:\Users\tdrev\do-qa\repo, checks out
  the commit in -Ref (default do/spec-7-unlock-and-backup), builds the default USE
  debug APK, installs it on the Samsung Galaxy S24+ (R5CXC0FWWVN), and runs the
  Spec 7 acceptance tests the debug broadcasts can reach.
  Evidence goes to C:\Users\tdrev\do-qa\qa-evidence\Do-spec07-<stamp>\.
  Results are written to C:\Users\tdrev\do-qa\results-spec-07.md.

  Due times and dumps that include a time are compared truncated to the local
  minute. A fire must land 0 to 60 seconds after its scheduled time. A fire
  before the scheduled time is always a FAIL.

  Before adb install -r, if the installed app's Room user_version is higher than
  this APK's database version, the app is uninstalled first and the log says
  test-phone data will not be restored.

  Human ATs (the unlock screen, the yellow badge and Backup row, the SAF
  pickers, and the release manifest) are listed as MANUAL. This script does not
  replace run-spec-05.ps1 or run-spec-06.ps1. It never calls launchBillingFlow
  and never uses a real or licence-tester purchase.

  Do-runner.log in the evidence folder is the authoritative live log.

  ALWAYS restores the phone in a finally block: leftover T_s7_* reminders
  (Repeat Off, then Mark Done), the fake unlock state (back to unlocked), any
  debug backup folder, leftover export files this script created, the snooze
  options if this run changed them, and the Sleeping / EVERY_NIGHT_DEFAULT_RULE
  schedule if this run snoozed it. Snooze uses OVERRIDE_DEACTIVATE; restore
  re-activates the rule. Restoring only zen_mode is not enough. The restore is
  logged.

  Never sets, changes or removes a screen-lock PIN, password or pattern.
  Never factory-resets, never changes the clock or time zone, never turns on
  silent mode, and never uses force-stop. If am kill leaves the process
  running, the row is RECORD, not FAIL. This script does not call am kill.

.PARAMETER DryRun
  Print the commands without changing the phone or writing a checkout.

.PARAMETER Only
  Optional test ID filter, e.g. -Only AT-02,AT-05

.PARAMETER Ref
  Git commit or ref to check out in C:\Users\tdrev\do-qa\repo. Default is the
  Spec 7 branch. A short SHA, full SHA, or branch name is accepted.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string[]]$Only,
    [string]$Ref = 'do/spec-7-unlock-and-backup'
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
$SetAlertAction = 'com.evansappstudio.doapp.debug.SET_ALERT_LEVEL'
$DumpRepeatAction = 'com.evansappstudio.doapp.debug.DUMP_REPEAT'
$DumpAlertAction = 'com.evansappstudio.doapp.debug.DUMP_ALERT'
$DumpHealthAction = 'com.evansappstudio.doapp.debug.DUMP_HEALTH'
$FakeAction   = 'com.evansappstudio.doapp.debug.FAKE_PURCHASE'
$DumpUnlockAction = 'com.evansappstudio.doapp.debug.DUMP_UNLOCK'
$ExportAction = 'com.evansappstudio.doapp.debug.EXPORT'
$ImportAction = 'com.evansappstudio.doapp.debug.IMPORT'
$DumpBackupAction = 'com.evansappstudio.doapp.debug.DUMP_BACKUP'
$ConfigFolderAction = 'com.evansappstudio.doapp.debug.CONFIG_BACKUP_FOLDER'
$AutoAction   = 'com.evansappstudio.doapp.debug.AUTO_BACKUP'
$SetTitleAction = 'com.evansappstudio.doapp.debug.SET_TITLE'
$ConfigSnoozeAction = 'com.evansappstudio.doapp.debug.CONFIG_SNOOZE'
$ResultsPath  = 'C:\Users\tdrev\do-qa\results-spec-07.md'
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
$script:AppFiles = $null
$script:MadeFiles = New-Object System.Collections.Generic.List[string]
$script:SleepingSnoozed = $false
$script:SnoozeChanged = $false

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
        [string[]]$AdbArgs,
        [switch]$AllowFail
    )
    $all = @('-s', $Serial) + @($AdbArgs)
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
    Invoke-Adb -AdbArgs @('shell', $Command) -AllowFail:$AllowFail
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

function Get-ApkDatabaseVersion {
    [CmdletBinding()]
    param()
    $path = Join-Path $RepoDir 'app\src\main\java\com\evansappstudio\doapp\data\AppDatabase.kt'
    $text = Get-Content -LiteralPath $path -Raw
    if ($text -match 'version\s*=\s*(\d+)') { return [int]$Matches[1] }
    throw 'Could not read the Room database version from AppDatabase.kt'
}

function Get-InstalledRoomVersion {
    [CmdletBinding()]
    param()
    $listed = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "run-as $Pkg ls databases" -AllowFail).Output
    if ($listed -notmatch 'do\.db') {
        Write-Log 'No installed do.db, so there is no Room user_version to compare.'
        return $null
    }
    $header = Join-Path $script:EvidenceDir 'installed-db-header.bin'
    $err = Join-Path $script:EvidenceDir 'installed-db-header.err'
    if (Test-Path $header) { Remove-Item -LiteralPath $header -Force }
    $proc = Start-Process -FilePath $script:Adb -ArgumentList @('-s', $Serial, 'exec-out', 'run-as', $Pkg, 'dd', 'if=databases/do.db', 'bs=100', 'count=1') -RedirectStandardOutput $header -RedirectStandardError $err -PassThru -Wait -WindowStyle Hidden
    if (-not (Test-Path $header)) { return $null }
    $bytes = [System.IO.File]::ReadAllBytes($header)
    if ($bytes.Length -lt 64) { return $null }
    if ([char]$bytes[0] -ne 'S' -or [char]$bytes[1] -ne 'Q') { return $null }
    $version = ([int]$bytes[60] -shl 24) -bor ([int]$bytes[61] -shl 16) -bor ([int]$bytes[62] -shl 8) -bor [int]$bytes[63]
    Write-Log "Installed Room user_version=$version (exit $($proc.ExitCode))"
    return $version
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
    $apkVersion = Get-ApkDatabaseVersion
    $installed = Get-InstalledRoomVersion
    if ($null -ne $installed -and $installed -gt $apkVersion) {
        Write-Log "Installed Room user_version $installed is newer than this APK's database version $apkVersion. Uninstalling first. Test-phone data will not be restored."
        Invoke-Adb -AdbArgs @('uninstall', $Pkg) | Out-Null
    } else {
        Write-Log "Room downgrade guard: installed=$installed apk=$apkVersion. install -r is safe."
    }
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
    $script:LogcatProc = Start-Process -FilePath $script:Adb -ArgumentList @('-s', $Serial, 'logcat', '-v', 'threadtime', '-s', 'DoAlarm:V') -RedirectStandardOutput $script:LogcatFile -RedirectStandardError (Join-Path $script:EvidenceDir 'Do-logcat-DoAlarm.err.txt') -PassThru -WindowStyle Hidden
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

function Get-Field {
    [CmdletBinding()]
    param([string]$Dump, [string]$Key)
    if (-not $Dump) { return '' }
    $m = [regex]::Match($Dump, "(?:^|\s)$([regex]::Escape($Key))=([^\s]+)")
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

function Add-Reminder {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [int]$InSeconds = 3600,
        [int]$NagMinutes = 0
    )
    $before = Get-LogLen
    $safe = $Title.Replace("'", '')
    Send-Broadcast -Command "am broadcast -a $AddAction -n $Recv --es title '$safe' --ei inSeconds $InSeconds --ei nagMinutes $NagMinutes"
    if ($DryRun) { return @{ Id = 0; DueAt = 0; Title = $Title } }
    $added = Wait-ForMatch -Pattern 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)' -BeforeLen $before -WaitSeconds 20
    $blocked = Wait-ForMatch -Pattern 'DoAlarm unlock blocked active=(\d+) cap=(\S+)' -BeforeLen $before -WaitSeconds 2
    if ($added) {
        $info = @{
            Id = (Remember-Id -Id $added.Groups[1].Value)
            DueAt = [int64]$added.Groups[2].Value
            Title = $Title
            Blocked = $false
        }
        Write-Log ("ADD {0} id={1} dueAt={2}" -f $Title, $info.Id, $info.DueAt)
        return $info
    }
    return @{ Id = 0; DueAt = 0; Title = $Title; Blocked = [bool]$blocked; BlockText = $(if ($blocked) { $blocked.Value } else { '' }) }
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
    $ids = @($script:CreatedIds)
    foreach ($id in $ids) {
        try { Finish-OneReminder -Id $id } catch { Write-Log ("Done failed for ${id}: " + $_.Exception.Message) }
    }
    Write-Log ("Marked done (Repeat Off first) for ids: " + ($(if ($ids.Count) { $ids -join ',' } else { 'none' })))
    $script:CreatedIds.Clear()
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
    if ($DryRun) { return 'DoAlarm unlock state=unlocked source=fake pending=false product=do_unlock price=none active=0 cap=5' }
    $m = Wait-ForMatch -Pattern 'DoAlarm unlock state=\S+ source=\S+[^\r\n]*' -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Get-BackupDump {
    [CmdletBinding()]
    param()
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpBackupAction -n $Recv"
    if ($DryRun) { return 'DoAlarm backup folder=none lastSuccess=none health=WARN' }
    $m = Wait-ForMatch -Pattern 'DoAlarm backup folder=\S+ lastSuccess=\S+ health=\S+' -BeforeLen $before -WaitSeconds 15
    if ($m) { return $m.Value }
    return ''
}

function Get-HealthDump {
    [CmdletBinding()]
    param()
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpHealthAction -n $Recv"
    if ($DryRun) { return 'DoAlarm health backup=WARN' }
    $m = Wait-ForMatch -Pattern 'DoAlarm health [^\r\n]+' -BeforeLen $before -WaitSeconds 20
    if ($m) { return $m.Value }
    return ''
}

function Resolve-AppFiles {
    [CmdletBinding()]
    param()
    if ($script:AppFiles) { return $script:AppFiles }
    $pwdText = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "run-as $Pkg pwd" -AllowFail).Output
    $pwdText = $pwdText.Trim()
    if ($pwdText -match '^/') { $script:AppFiles = ($pwdText.TrimEnd('/') + '/files/qa-spec07') }
    else { $script:AppFiles = '/data/data/com.evansappstudio.doapp/files/qa-spec07' }
    Write-Log "Debug backup directory: $($script:AppFiles)"
    Invoke-AdbShell -Command "run-as $Pkg mkdir -p files/qa-spec07" -AllowFail | Out-Null
    return $script:AppFiles
}

function Read-AppFile {
    [CmdletBinding()]
    param([string]$AbsolutePath)
    $relative = $AbsolutePath
    if ($AbsolutePath -match '/files/(.+)$') { $relative = 'files/' + $Matches[1] }
    $text = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "run-as $Pkg cat $relative" -AllowFail).Output
    return $text
}

function Write-AppFile {
    [CmdletBinding()]
    param([string]$RelativePath, [string]$Text)
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $bytes = $utf8.GetBytes($Text)
    $b64 = [Convert]::ToBase64String($bytes)
    Invoke-AdbShell -Command "echo $b64 | base64 -d | run-as $Pkg sh -c 'cat > $RelativePath'" -AllowFail | Out-Null
}

function Remove-AppQaFiles {
    [CmdletBinding()]
    param()
    Invoke-AdbShell -Command "run-as $Pkg rm -rf files/qa-spec07" -AllowFail | Out-Null
    Write-Log 'Removed files/qa-spec07 created for this run.'
}

function Get-SettingsXml {
    [CmdletBinding()]
    param()
    ConvertTo-ScalarString -Value (Invoke-AdbShell -Command "run-as $Pkg cat shared_prefs/do_settings.xml" -AllowFail).Output
}

function Set-SnoozeOption {
    [CmdletBinding()]
    param([int]$Index, [string]$Kind, [int]$Value)
    $script:SnoozeChanged = $true
    Send-Broadcast -Command "am broadcast -a $ConfigSnoozeAction -n $Recv --ei option $Index --es kind $Kind --ei value $Value"
    if (-not $DryRun) { Start-Sleep -Milliseconds 500 }
}

function Restore-SnoozeDefaults {
    [CmdletBinding()]
    param()
    if (-not $script:SnoozeChanged) {
        Write-Log 'Snooze options were not changed.'
        return
    }
    Set-SnoozeOption -Index 0 -Kind 'FROM_NOW' -Value 60
    Set-SnoozeOption -Index 1 -Kind 'FROM_NOW' -Value 180
    Set-SnoozeOption -Index 2 -Kind 'FROM_NOW' -Value 1440
    Write-Log 'Restored the three snooze options to the factory defaults.'
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

function Restore-PhoneState {
    [CmdletBinding()]
    param()
    Write-Log 'Restoring phone state'
    if ($DryRun) { return }
    try { Set-FakeState -State 'unlocked' | Out-Null } catch { Write-Log $_.Exception.Message }
    try { Send-Broadcast -Command "am broadcast -a $ConfigFolderAction -n $Recv --es path ''" } catch { Write-Log $_.Exception.Message }
    try { Remove-AppQaFiles } catch { Write-Log $_.Exception.Message }
    try { Restore-SnoozeDefaults } catch { Write-Log $_.Exception.Message }
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
    } catch { Write-Log $_.Exception.Message }
    Write-Log 'Restore pass finished. Fake unlock is unlocked. No PIN, clock, time zone, silent mode or force-stop was used.'
}

function Run-AT02 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-02'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $first = Get-UnlockDump
    Save-Text -Name 'AT-02-fresh.txt' -Text $first
    $freshOk = ($first -match 'state=unlocked') -and ($first -match 'source=fake') -and ($first -match 'cap=5')
    $locked = Set-FakeState -State 'locked'
    $second = Get-UnlockDump
    Save-Text -Name 'AT-02-locked.txt' -Text ($locked + "`n" + $second)
    $lockedOk = ($second -match 'state=locked')
    $detail = "fresh=$first lockedDump=$second"
    if ($freshOk -and $lockedOk) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT03 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-03'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'locked' | Out-Null
    $made = @()
    foreach ($n in 1..5) {
        $row = Add-Reminder -Title "T_s7_$n" -InSeconds 3600 -NagMinutes 0
        $made += $row
    }
    $sixth = Add-Reminder -Title 'T_s7_blocked' -InSeconds 3600 -NagMinutes 0
    $fiveOk = @($made | Where-Object { $_.Id -gt 0 }).Count -eq 5
    $blockedOk = $sixth.Blocked -and ($sixth.BlockText -match 'active=5') -and ($sixth.BlockText -match 'cap=5') -and ($sixth.Id -eq 0)
    $doneId = $made[0].Id
    Finish-OneReminder -Id $doneId
    if (-not $DryRun) { Start-Sleep -Seconds 1 }
    $after = Add-Reminder -Title 'T_s7_6' -InSeconds 3600 -NagMinutes 0
    Set-FakeState -State 'unlocked' | Out-Null
    $seventh = Add-Reminder -Title 'T_s7_7' -InSeconds 3600 -NagMinutes 0
    $detail = "five=$fiveOk blocked=$($sixth.BlockText) afterDoneId=$($after.Id) seventh=$($seventh.Id)"
    if ($fiveOk -and $blockedOk -and $after.Id -gt 0 -and $seventh.Id -gt 0) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
    Finish-TrackedReminders
}

function Run-AT05 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-05'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'locked' | Out-Null
    foreach ($n in 1..5) { Add-Reminder -Title "T_s7_p$n" -InSeconds 3600 | Out-Null }
    $pending = Set-FakeState -State 'pending'
    $dump = Get-UnlockDump
    $sixth = Add-Reminder -Title 'T_s7_pblocked' -InSeconds 3600
    $unlocked = Set-FakeState -State 'unlocked'
    $seventh = Add-Reminder -Title 'T_s7_p7' -InSeconds 3600
    $detail = "pendingLine=$pending dump=$dump blocked=$($sixth.Blocked) unlock=$unlocked seventh=$($seventh.Id)"
    $ok = ($dump -match 'pending=true') -and ($dump -notmatch 'state=unlocked') -and $sixth.Blocked -and ($seventh.Id -gt 0)
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
    Finish-TrackedReminders
}

function Run-AT06 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-06'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'unlocked' | Out-Null
    $ids = @()
    foreach ($n in 1..5) {
        $row = Add-Reminder -Title "T_s7_r$n" -InSeconds 3600
        $ids += $row.Id
    }
    $short = Add-Reminder -Title 'T_s7_rfire' -InSeconds 25
    Set-FakeState -State 'locked' | Out-Null
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $DumpRepeatAction -n $Recv --el id $($ids[0]) --ei count 1"
    $still = Wait-ForMatch -Pattern ("DoAlarm repeat id={0} " -f $ids[0]) -BeforeLen $before -WaitSeconds 15
    $fire = Wait-ForMatch -Pattern ("DoAlarm fire id={0} scheduled=(\d+) actual=(\d+)" -f $short.Id) -BeforeLen $before -WaitSeconds 90
    $lag = -1
    $early = $false
    $inWindow = $false
    if ($fire) {
        $scheduled = [int64]$fire.Groups[1].Value
        $actual = [int64]$fire.Groups[2].Value
        $lag = $actual - $scheduled
        $early = $lag -lt 0
        $inWindow = ($lag -ge 0) -and ($lag -le $PhoneTolMs)
        $same = Test-SameLocalMinute -LeftMs $short.DueAt -RightMs $scheduled
        if (-not $same) { $inWindow = $false }
    }
    $seventh = Add-Reminder -Title 'T_s7_r7' -InSeconds 3600
    $detail = "still=$([bool]$still) fire=$([bool]$fire) lagMs=$lag early=$early seventhBlocked=$($seventh.Blocked)"
    if ($early) { Record-Result -Id $tid -Status 'FAIL' -Detail ("early fire. " + $detail); Finish-TrackedReminders; return }
    if ($still -and $inWindow -and $seventh.Blocked) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
    Finish-TrackedReminders
}

function Run-AT07 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-07'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'unlocked' | Out-Null
    $dir = Resolve-AppFiles
    Set-SnoozeOption -Index 0 -Kind 'AT_TIME' -Value 540
    $first = Add-Reminder -Title 'T_s7_keep_a' -InSeconds 3600
    $second = Add-Reminder -Title 'T_s7_keep_b' -InSeconds 4200
    Send-Broadcast -Command "am broadcast -a $SetRepeatAction -n $Recv --el id $($first.Id) --es unit DAY --ei interval 1 --es from DUE_DATE"
    Send-Broadcast -Command "am broadcast -a $SetAlertAction -n $Recv --el id $($first.Id) --es level CRITICAL"
    Send-Broadcast -Command "am broadcast -a $SetAlertAction -n $Recv --el id $($second.Id) --es level PROMINENT"
    if (-not $DryRun) { Start-Sleep -Seconds 1 }
    $exportPath = "$dir/keep.json"
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $exportPath"
    $exported = Wait-ForMatch -Pattern 'DoAlarm backup export path=\S+ reminders=(\d+) schema=1' -BeforeLen $before -WaitSeconds 20
    $dump = Get-BackupDump
    $script:MadeFiles.Add($exportPath)
    Set-SnoozeOption -Index 0 -Kind 'FROM_NOW' -Value 15
    Finish-OneReminder -Id $first.Id
    Finish-OneReminder -Id $second.Id
    $decoy = Add-Reminder -Title 'T_s7_decoy' -InSeconds 3600
    $importBefore = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path $exportPath --es mode replace"
    $imported = Wait-ForMatch -Pattern 'DoAlarm backup import mode=replace reminders=(\d+) schema=1(?: ids=([0-9,]+))?' -BeforeLen $importBefore -WaitSeconds 20
    $newIds = @()
    if ($imported -and $imported.Groups[2].Success) {
        foreach ($part in ($imported.Groups[2].Value -split ',')) {
            if ($part) { $newIds += (Remember-Id -Id $part) }
        }
    }
    $repeatOk = $true
    $minuteOk = $true
    $alertOk = $true
    foreach ($id in $newIds) {
        $rb = Get-LogLen
        Send-Broadcast -Command "am broadcast -a $DumpRepeatAction -n $Recv --el id $id --ei count 1"
        $rep = Wait-ForMatch -Pattern ("DoAlarm repeat id={0} rule=(\S+) from=\S+ dueAt=(\d+)" -f $id) -BeforeLen $rb -WaitSeconds 15
        $ab = Get-LogLen
        Send-Broadcast -Command "am broadcast -a $DumpAlertAction -n $Recv --el id $id"
        $al = Wait-ForMatch -Pattern ("DoAlarm alert id={0} level=(\S+)" -f $id) -BeforeLen $ab -WaitSeconds 15
        if (-not $rep) { $repeatOk = $false }
        else {
            $due = [int64]$rep.Groups[2].Value
            $sameA = Test-SameLocalMinute -LeftMs $first.DueAt -RightMs $due
            $sameB = Test-SameLocalMinute -LeftMs $second.DueAt -RightMs $due
            if (-not $sameA -and -not $sameB) { $minuteOk = $false }
        }
        if (-not $al) { $alertOk = $false }
    }
    $again = "$dir/keep-after.json"
    $eb = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $again"
    Wait-ForMatch -Pattern 'DoAlarm backup export path=\S+' -BeforeLen $eb -WaitSeconds 20 | Out-Null
    $json = Read-AppFile -AbsolutePath $again
    Save-Text -Name 'AT-07-after.json' -Text $json
    $prefs = Get-SettingsXml
    Save-Text -Name 'AT-07-settings.xml' -Text $prefs
    $titlesOk = ($json -match 'T_s7_keep_a') -and ($json -match 'T_s7_keep_b') -and ($json -notmatch 'T_s7_decoy')
    $snoozeOk = ($prefs -match 'AT_TIME') -and ($prefs -match '540')
    $successOk = ($dump -match 'health=OK') -and ($dump -match 'lastSuccess=\d')
    $alarmText = ConvertTo-ScalarString -Value (Invoke-AdbShell -Command 'dumpsys alarm' -AllowFail).Output
    $alarmOk = $true
    foreach ($id in $newIds) {
        if ($alarmText -notmatch [regex]::Escape("doapp://reminder/$id")) { $alarmOk = $false }
    }
    $detail = "export=$([bool]$exported) import=$([bool]$imported) titles=$titlesOk snooze=$snoozeOk minute=$minuteOk repeat=$repeatOk alert=$alertOk alarm=$alarmOk success=$successOk decoy=$($decoy.Id) new=$($newIds -join ',')"
    if ($exported -and $imported -and $titlesOk -and $snoozeOk -and $minuteOk -and $repeatOk -and $alertOk -and $alarmOk -and $successOk) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
    Finish-TrackedReminders
}

function Run-AT08 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-08'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'unlocked' | Out-Null
    $dir = Resolve-AppFiles
    $row = Add-Reminder -Title 'T_s7_uuid' -InSeconds 3600
    $path = "$dir/uuid.json"
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $path"
    $exported = Wait-ForMatch -Pattern 'DoAlarm backup export path=\S+' -BeforeLen $before -WaitSeconds 20
    $original = Read-AppFile -AbsolutePath $path
    $uuidMatch = [regex]::Match($original, '"uuid": "([^"]+)"')
    $uuid = if ($uuidMatch.Success) { $uuidMatch.Groups[1].Value } else { '' }
    Send-Broadcast -Command "am broadcast -a $SetTitleAction -n $Recv --el id $($row.Id) --es title T_s7_changed"
    if (-not $DryRun) { Start-Sleep -Milliseconds 600 }
    $ib = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path $path --es mode merge"
    $merged = Wait-ForMatch -Pattern 'DoAlarm backup import mode=merge reminders=1 schema=1' -BeforeLen $ib -WaitSeconds 20
    $after = "$dir/uuid-after.json"
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $after"
    if (-not $DryRun) { Start-Sleep -Seconds 1 }
    $afterText = Read-AppFile -AbsolutePath $after
    $oneRow = ([regex]::Matches($afterText, '"uuid"')).Count -eq 1
    $titleBack = $afterText -match 'T_s7_uuid' -and $afterText -notmatch 'T_s7_changed'
    $sameUuid = $uuid -and ($afterText -match [regex]::Escape($uuid))
    $newUuid = '11111111-2222-4333-8444-555555555555'
    $secondText = $original.Replace($uuid, $newUuid).Replace('T_s7_uuid', 'T_s7_uuid_new')
    $secondPath = "$dir/uuid-new.json"
    Write-AppFile -RelativePath 'files/qa-spec07/uuid-new.json' -Text $secondText
    $nb = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path $secondPath --es mode merge"
    $secondMerge = Wait-ForMatch -Pattern 'DoAlarm backup import mode=merge reminders=1 schema=1 ids=([0-9,]+)' -BeforeLen $nb -WaitSeconds 20
    if ($secondMerge) { Remember-Id -Id ($secondMerge.Groups[1].Value.Split(',')[0]) | Out-Null }
    $third = "$dir/uuid-third.json"
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $third"
    if (-not $DryRun) { Start-Sleep -Seconds 1 }
    $thirdText = Read-AppFile -AbsolutePath $third
    Save-Text -Name 'AT-08-third.json' -Text $thirdText
    $two = ([regex]::Matches($thirdText, '"uuid"')).Count -eq 2
    $untouched = $uuid -and ($thirdText -match [regex]::Escape($uuid)) -and ($thirdText -match 'T_s7_uuid')
    $detail = "uuid=$uuid oneRow=$oneRow titleBack=$titleBack same=$sameUuid two=$two untouched=$untouched merge1=$([bool]$merged) merge2=$([bool]$secondMerge) export=$([bool]$exported)"
    if ($exported -and $merged -and $oneRow -and $titleBack -and $sameUuid -and $two -and $untouched -and $secondMerge) {
        Record-Result -Id $tid -Status 'PASS' -Detail $detail
    } else {
        Record-Result -Id $tid -Status 'FAIL' -Detail $detail
    }
    Finish-TrackedReminders
}

function Run-AT09 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-09'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Set-FakeState -State 'unlocked' | Out-Null
    $dir = Resolve-AppFiles
    $row = Add-Reminder -Title 'T_s7_badkeep' -InSeconds 3600
    $good = "$dir/good.json"
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $good"
    if (-not $DryRun) { Start-Sleep -Seconds 1 }
    $goodText = Read-AppFile -AbsolutePath $good
    $truncPath = "$dir/trunc.json"
    $schemaPath = "$dir/schema99.json"
    Write-AppFile -RelativePath 'files/qa-spec07/trunc.json' -Text '{ "schemaVersion": 1, "appId":'
    $schemaText = $goodText.Replace('"schemaVersion": 1', '"schemaVersion": 99')
    Write-AppFile -RelativePath 'files/qa-spec07/schema99.json' -Text $schemaText
    $b1 = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path $truncPath --es mode replace"
    $err1 = Wait-ForMatch -Pattern 'DoAlarm backup import error reason=\S+' -BeforeLen $b1 -WaitSeconds 15
    $ok1 = Wait-ForMatch -Pattern 'DoAlarm backup import mode=' -BeforeLen $b1 -WaitSeconds 2
    $b2 = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ImportAction -n $Recv --es path $schemaPath --es mode replace"
    $err2 = Wait-ForMatch -Pattern 'DoAlarm backup import error reason=schema' -BeforeLen $b2 -WaitSeconds 15
    $ok2 = Wait-ForMatch -Pattern 'DoAlarm backup import mode=' -BeforeLen $b2 -WaitSeconds 2
    $check = "$dir/bad-after.json"
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $check"
    if (-not $DryRun) { Start-Sleep -Seconds 1 }
    $after = Read-AppFile -AbsolutePath $check
    $kept = $after -match 'T_s7_badkeep'
    $detail = "truncError=$([bool]$err1) truncSuccess=$([bool]$ok1) schemaError=$([bool]$err2) schemaSuccess=$([bool]$ok2) kept=$kept id=$($row.Id)"
    if ($err1 -and -not $ok1 -and $err2 -and -not $ok2 -and $kept) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
    Finish-TrackedReminders
}

function Run-AT10 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-10'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    Send-Broadcast -Command "am broadcast -a $ConfigFolderAction -n $Recv --es clearSuccess true"
    if (-not $DryRun) { Start-Sleep -Milliseconds 800 }
    $before = Get-HealthDump
    Save-Text -Name 'AT-10-before.txt' -Text $before
    $warn = (Get-Field -Dump $before -Key 'backup') -eq 'WARN'
    $dir = Resolve-AppFiles
    $path = "$dir/health.json"
    $eb = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $ExportAction -n $Recv --es path $path"
    $exported = Wait-ForMatch -Pattern 'DoAlarm backup export path=\S+ reminders=\d+ schema=1' -BeforeLen $eb -WaitSeconds 20
    $after = Get-HealthDump
    $backupDump = Get-BackupDump
    Save-Text -Name 'AT-10-after.txt' -Text ($after + "`n" + $backupDump)
    $ok = (Get-Field -Dump $after -Key 'backup') -eq 'OK'
    $timeSet = $backupDump -match 'lastSuccess=\d'
    $detail = "beforeBackup=$(Get-Field -Dump $before -Key 'backup') after=$(Get-Field -Dump $after -Key 'backup') dump=$backupDump export=$([bool]$exported). Human: the yellow badge follows the Warning rows. Battery optimisation may already be a Warning; do not fail the product only for that. Fix on the Backup row opens the Backup screen."
    if ($warn -and $ok -and $exported -and $timeSet) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Run-AT11 {
    [CmdletBinding()]
    param()
    $tid = 'Do-AT-11'
    if (-not (Should-Run -Id $tid)) { return }
    if ($DryRun) { Record-Result -Id $tid -Status 'SKIP' -Detail 'DryRun'; return }
    $dir = Resolve-AppFiles
    Send-Broadcast -Command "am broadcast -a $ConfigFolderAction -n $Recv --es path $dir --es clearSuccess true"
    if (-not $DryRun) { Start-Sleep -Milliseconds 500 }
    $before = Get-LogLen
    Send-Broadcast -Command "am broadcast -a $AutoAction -n $Recv"
    $auto = Wait-ForMatch -Pattern 'DoAlarm backup auto path=(\S+) reminders=\d+ schema=1' -BeforeLen $before -WaitSeconds 20
    $dump = Get-BackupDump
    $fileOk = $false
    if ($auto) {
        $text = Read-AppFile -AbsolutePath $auto.Groups[1].Value
        Save-Text -Name 'AT-11-auto.json' -Text $text
        $fileOk = $text -match '"schemaVersion": 1'
    }
    Send-Broadcast -Command "am broadcast -a $ConfigFolderAction -n $Recv --es path ''"
    if (-not $DryRun) { Start-Sleep -Milliseconds 500 }
    $cleared = Get-BackupDump
    $detail = "auto=$([bool]$auto) file=$fileOk dump=$dump cleared=$cleared"
    $ok = $auto -and $fileOk -and ($dump -match 'folder=set') -and ($dump -match 'health=OK') -and ($cleared -match 'folder=none')
    if ($ok) { Record-Result -Id $tid -Status 'PASS' -Detail $detail }
    else { Record-Result -Id $tid -Status 'FAIL' -Detail $detail }
}

function Record-ManualRows {
    [CmdletBinding()]
    param()
    $rows = @(
        @{ Id = 'Do-AT-01'; Status = 'MANUAL'; Detail = 'CI. Unit tests cover the cap, acknowledgement, pending, revoke, offline cache, schema 1 round trip, merge, replace, bad files, the backup health row, and the Auto Backup rules. checkAlarmManifests rejects a release debug receiver, FAKE_PURCHASE, FakeUnlockBilling, and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.' },
        @{ Id = 'Do-AT-04'; Status = 'MANUAL'; Detail = 'Human. With the fake locked, Settings shows Unlock Do. A sixth save opens the unlock screen. Buy, Restore purchases, and the no-ads / no-account / no-subscription sentence are visible. Price unavailable keeps Buy disabled. No real billing flow is started.' },
        @{ Id = 'Do-AT-12'; Status = 'MANUAL'; Detail = 'Human. Export uses the create-document picker. Choose backup folder uses the tree picker. Restore from file uses the open-document picker, shows a preview, and offers Replace all, Merge, and a per-reminder selection. Cancel leaves data unchanged.' },
        @{ Id = 'Do-AT-13'; Status = 'MANUAL'; Detail = 'CI. The release output has no .debug package, no FAKE_PURCHASE, and no FakeUnlockBilling. checkAlarmManifests passes for USE and for -PexactAlarmMode=SCHEDULE. No keystore.' }
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
    [void]$lines.Add('# Do Spec 7 phone results')
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
    [void]$lines.Add('MANUAL and RECORD rows do not fail the automated score. Restore leftover T_s7 reminders, the fake unlock state, the debug backup folder, and the Sleeping rule if it was snoozed. Do not add a PIN.')
    $text = [string]::Join($nl, $lines) + $nl
    $dir = Split-Path $ResultsPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    Set-Content -Path $ResultsPath -Value $text -Encoding UTF8
    if ($script:EvidenceDir) {
        Copy-Item $ResultsPath (Join-Path $script:EvidenceDir 'results-spec-07.md') -Force
    }
    Write-Log "Wrote $ResultsPath"
}

$script:EvidenceRoot = 'C:\Users\tdrev\do-qa\qa-evidence'
$script:EvidenceDir = Join-Path $script:EvidenceRoot ("Do-spec07-" + $Stamp)

try {
    if ($DryRun) {
        Write-Log 'DryRun is on: no git checkout, no install, no phone changes.'
        $script:EvidenceDir = Join-Path $env:TEMP ("Do-spec07-dryrun-" + $Stamp)
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
        Write-Log 'DryRun command plan: checkout, assembleDebug, downgrade guard, install, DUMP_UNLOCK, cap, pending, revoke fire, export/replace, merge, bad file, health, auto-backup.'
        Record-Result -Id 'Do-runner' -Status 'SKIP' -Detail 'DryRun only.'
        Record-ManualRows
        return
    }
    Invoke-BuildAndInstall
    Start-DoLogcat
    Run-AT02
    Run-AT03
    Run-AT05
    Run-AT06
    Run-AT10
    Run-AT07
    Run-AT08
    Run-AT09
    Run-AT11
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
