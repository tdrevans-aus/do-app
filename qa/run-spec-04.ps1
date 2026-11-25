#Requires -Version 5.1
<#
.SYNOPSIS
  Do Spec 4 phone QA runner for Tim's Windows 11 PC (PowerShell 5.1).

.DESCRIPTION
  Clones or fetches tdrevans-aus/do-app into C:\Users\tdrev\do-qa\repo, checks out
  the commit in -Ref (default 8241170, PR #4), builds the default USE debug APK,
  installs it on the Samsung Galaxy S24+ (R5CXC0FWWVN), and runs the automatable
  Spec 4 acceptance tests (Repeats). Evidence goes to
  C:\Users\tdrev\do-qa\qa-evidence\Do-spec04-<stamp>\.
  Results are written to C:\Users\tdrev\do-qa\results-spec-04.md.

  Human ATs (1 picker/labels, 10 editor prompt, 12 reboot half, 13 phone DST,
  15 direct boot) are listed as MANUAL and are in spec-04-manual-checklist.md.
  This script does not replace run-spec-01.ps1, run-spec-02.ps1 or run-spec-03.ps1.

  Do-runner.log in the evidence folder is the authoritative live log. Write-Log
  appends to it and flushes immediately. Write-Host may be buffered if the
  console is redirected (for example to run-console.log); do not treat that
  file as complete until the process exits.

  ALWAYS restores the phone in a finally block, including factory Quick Times
  slots, Auto postpone, factory snooze options, and the repeat prompt (PROMPT).
  Marks leftover T_s4_* test reminders Done. Restores automatic time zone.
  Does not set a PIN or lock screen, does not factory-reset, and does not
  touch other apps' data. Never uses force-stop; if am kill leaves the
  process running the row is RECORD, not FAIL.

  SHIFT_DUE and SET_REPEAT write through, unlike the editor. Far-future next
  occurrences (daily +1 day, monthly, yearly, every 8 hours) are scored from
  DUMP_REPEAT / repeat-advance / dumpsys alarm, not by waiting for the fire.

.PARAMETER DryRun
  Print the commands without changing the phone or writing a checkout.

.PARAMETER Only
  Optional test ID filter, e.g. -Only AT-02,AT-05,AT-08

.PARAMETER Ref
  Git commit or ref to check out in C:\Users\tdrev\do-qa\repo. Default is
  8241170 (PR #4). A short SHA, full SHA, or branch name is accepted.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string[]]$Only,
    [string]$Ref = '8241170'
)

$ErrorActionPreference = 'Stop'

# --- constants --------------------------------------------------------------
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
$SnoozeAction = 'com.evansappstudio.doapp.debug.SNOOZE'
$ConfigAction = 'com.evansappstudio.doapp.debug.CONFIG_SNOOZE'
$ConfigQatAction = 'com.evansappstudio.doapp.debug.CONFIG_QAT'
$ConfigPostponeAction = 'com.evansappstudio.doapp.debug.CONFIG_POSTPONE'
$DumpQatAction = 'com.evansappstudio.doapp.debug.DUMP_QAT'
$ApplyQatAction = 'com.evansappstudio.doapp.debug.APPLY_QAT'
$SetRepeatAction = 'com.evansappstudio.doapp.debug.SET_REPEAT'
$DumpRepeatAction = 'com.evansappstudio.doapp.debug.DUMP_REPEAT'
$ShiftDueAction = 'com.evansappstudio.doapp.debug.SHIFT_DUE'
$ConfigRepeatPromptAction = 'com.evansappstudio.doapp.debug.CONFIG_REPEAT_PROMPT'
$QatTolMs     = 2000
$ResultsPath  = 'C:\Users\tdrev\do-qa\results-spec-04.md'
$PhoneTolMs   = 60000
$SnoozeTolMs  = 2000
$Stamp        = Get-Date -Format 'yyyyMMdd-HHmmss'

# --- filter -----------------------------------------------------------------
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
            if ($part.Trim()) { $OnlySet += Normalize-TestId $part }
        }
    }
}

function Should-Run {
    [CmdletBinding()]
    param([string]$Id)
    if ($OnlySet.Count -eq 0) { return $true }
    return ($OnlySet -contains (Normalize-TestId $Id))
}

# --- logging ----------------------------------------------------------------
$script:EvidenceDir = $null
$script:LogcatProc  = $null
$script:LogcatFile  = $null
$script:Results     = New-Object System.Collections.Generic.List[object]
$script:Originals   = @{}
$script:PhoneTouched = $false
$script:Adb = $AdbPath

function Write-Log {
    [CmdletBinding()]
    param([string]$Message)
    $line = '[Do-QA {0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message
    Write-Host $line
    if ($script:EvidenceDir) {
        if (-not (Test-Path $script:EvidenceDir)) { New-Item -ItemType Directory -Path $script:EvidenceDir -Force | Out-Null }
        $logPath = Join-Path $script:EvidenceDir 'Do-runner.log'
        # Append + close so Do-runner.log is current even if Write-Host is buffered.
        $enc = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::AppendAllText($logPath, ($line + [Environment]::NewLine), $enc)
    }
}

# --- collection / scalar guards (PS 5.1) -----------------------------------
# Returning an Object[] with "return ,$list" emits the array as one pipeline
# object. Wrapping that again in @() nests it. Then foreach ($f in $fires)
# yields the inner list, $f.Scheduled is Object[], and [int64]$Scheduled throws.
# These helpers always flatten to a List and coerce Int64 inside the body
# (typed [int64] params throw before we can pick the first element).

function New-QaList {
    return New-Object System.Collections.Generic.List[object]
}

function ConvertTo-ScalarString {
    [CmdletBinding()]
    param($Value)
    if ($null -eq $Value) { return '' }
    if ($Value -is [string]) { return $Value }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $parts = New-Object System.Collections.Generic.List[string]
        foreach ($x in $Value) {
            if ($null -ne $x) { [void]$parts.Add([string]$x) }
        }
        return [string]::Join("`n", $parts)
    }
    return [string]$Value
}

function ConvertTo-QaList {
    [CmdletBinding()]
    param($Value)
    $list = New-QaList
    function Walk($item) {
        if ($null -eq $item) { return }
        if ($item -is [hashtable] -or $item -is [System.Collections.IDictionary] -or $item -is [pscustomobject]) {
            [void]$list.Add($item)
            return
        }
        if ($item -is [string] -or $item -is [ValueType]) {
            [void]$list.Add($item)
            return
        }
        if ($item -is [System.Collections.IEnumerable]) {
            foreach ($x in $item) { Walk $x }
            return
        }
        [void]$list.Add($item)
    }
    Walk $Value
    return $list
}

function Get-FlatFires {
    [CmdletBinding()]
    param($Raw)
    $list = New-QaList
    function Walk($item) {
        if ($null -eq $item) { return }
        $isMap = ($item -is [hashtable] -or $item -is [System.Collections.IDictionary] -or $item -is [pscustomobject])
        if ($isMap) {
            $has = $false
            if ($item -is [hashtable] -and $item.ContainsKey('Scheduled')) { $has = $true }
            elseif ($item.PSObject.Properties.Name -contains 'Scheduled') { $has = $true }
            if ($has) { [void]$list.Add($item); return }
        }
        if ($item -is [System.Collections.IEnumerable] -and $item -isnot [string] -and -not $isMap) {
            foreach ($x in $item) { Walk $x }
        }
    }
    Walk $Raw
    return $list
}

function ConvertTo-Int64Scalar {
    [CmdletBinding()]
    param($Value, [string]$Name = 'value')
    if ($null -eq $Value) {
        throw "Expected a single Int64 for $Name, got null"
    }
    if ($Value -is [hashtable] -or $Value -is [System.Collections.IDictionary] -or $Value -is [pscustomobject]) {
        $prop = $null
        if ($Value -is [hashtable] -and $Value.ContainsKey($Name)) { $prop = $Value[$Name] }
        elseif ($Value.PSObject.Properties[$Name]) { $prop = $Value.$Name }
        if ($null -ne $prop) { return (ConvertTo-Int64Scalar $prop -Name $Name) }
        throw "Expected a single Int64 for $Name, got a hashtable/object without that property"
    }
    if ($Value -is [string]) {
        $t = $Value.Trim()
        if (-not $t) { throw "Expected a single Int64 for $Name, got empty string" }
        return [int64]$t
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $flat = New-Object System.Collections.Generic.List[object]
        foreach ($x in $Value) {
            if ($null -eq $x) { continue }
            if ($x -is [System.Collections.IEnumerable] -and $x -isnot [string] -and $x -isnot [hashtable] -and $x -isnot [System.Collections.IDictionary]) {
                foreach ($y in $x) { if ($null -ne $y) { [void]$flat.Add($y) } }
            } else {
                [void]$flat.Add($x)
            }
        }
        if ($flat.Count -eq 0) { throw "Expected a single Int64 for $Name, got an empty array" }
        if ($flat.Count -gt 1) {
            Write-Log ("WARN: {0} was an array of {1} values; using the first ({2})" -f $Name, $flat.Count, $flat[0])
        }
        return (ConvertTo-Int64Scalar $flat[0] -Name $Name)
    }
    try { return [int64]$Value }
    catch { throw "Expected a single Int64 for $Name, got $($Value.GetType().FullName)" }
}

function Normalize-MatchText {
    [CmdletBinding()]
    param($Value)
    $s = ConvertTo-ScalarString $Value
    if (-not $s) { return '' }
    $s = $s.Trim([char]0xFEFF)
    $s = $s -replace "`r", ''
    $s = [regex]::Replace($s, "[\u00A0\u2007\u202F\u200B\u200C\u200D\uFEFF]", " ")
    $s = [regex]::Replace($s, "\s+", " ")
    return $s.Trim()
}

function Test-TextContains {
    [CmdletBinding()]
    param($Haystack, [string]$Needle)
    $h = Normalize-MatchText $Haystack
    $n = Normalize-MatchText $Needle
    if (-not $n) { return $false }
    return $h.ToLowerInvariant().Contains($n.ToLowerInvariant())
}

function Parse-AlarmTriggerMs {
    [CmdletBinding()]
    param($Entry)
    $text = ConvertTo-ScalarString $Entry
    if (-not $text) { return $null }
    # Android 17 often has triggerTime=2026-11-24 22:52:45.731 (formatted).
    # A short \d+ match would steal the year 2026 and make clockDelta look
    # like a raw epoch. Only accept 10+ digit epochs. origWhen may be
    # "origWhen 1795..." (space) on the Alarm{ line.
    $tm = [regex]::Match($text, 'triggerTime\s*[=:]\s*(\d{10,})')
    if ($tm.Success) { return [int64]$tm.Groups[1].Value }
    $tm = [regex]::Match($text, '(?i)\borigWhen\s*[=:]?\s*(\d{10,})')
    if ($tm.Success) { return [int64]$tm.Groups[1].Value }
    $tm = [regex]::Match($text, '(?i)(?<!whenElapsed)\bwhen\s*[=:]?\s*(\d{10,})')
    if ($tm.Success) { return [int64]$tm.Groups[1].Value }
    return $null
}

function Resolve-DeepIdleState {
    [CmdletBinding()]
    param($Raw)
    $s = ConvertTo-ScalarString $Raw
    $s = $s.Trim([char]0xFEFF)
    $s = $s -replace "`r", ''
    $lines = @($s -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count -eq 0) { return '' }
    $last = $lines[$lines.Count - 1]
    if ($last -match '(?i)(IDLE_MAINTENANCE|IDLE_PENDING|IDLE|ACTIVE|OVERRIDE)') {
        return $Matches[1].ToUpper()
    }
    return $last.ToUpper()
}

function Test-IsDeepIdle {
    [CmdletBinding()]
    param($State)
    $s = Resolve-DeepIdleState $State
    return ($s -eq 'IDLE' -or $s -eq 'IDLE_MAINTENANCE')
}

function Exit-ForcedDeepIdle {
    Invoke-AdbShell 'dumpsys deviceidle unforce' -AllowFail | Out-Null
    Invoke-AdbShell 'dumpsys battery reset' -AllowFail | Out-Null
}

function Invoke-Maybe {
    [CmdletBinding()]
    param(
        [string]$Label,
        [scriptblock]$Action,
        [switch]$MutatesPhone
    )
    Write-Log $Label
    if ($DryRun) { return }
    if ($MutatesPhone) { $script:PhoneTouched = $true }
    & $Action
}

# --- native commands (PS 5.1 treats native stderr as errors under Stop) -------
function Invoke-Git {
    $ErrorActionPreference = 'Continue'
    & git @args 2>&1 | ForEach-Object { Write-Host "$_" }
}
function Invoke-Git-Out {
    $ErrorActionPreference = 'Continue'
    $o = & git @args 2>$null
    return $o
}

# --- adb --------------------------------------------------------------------
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
    if (-not $AllowFail -and $code -ne 0) {
        throw "adb exited $code : $text"
    }
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
    # Call adb with a string[] directly. Do not splat --flags into Invoke-Adb:
    # in PS 5.1, @('shell', ..., '--noredact') is bound as the named parameter
    # -noredact, and adb then sees one token 'shell dumpsys notification --noredact'
    # (the 67-byte stub 'adb.exe: unknown command ...').
    $all = @('-s', $Serial) + @($ArgList)
    Write-Log ("adb " + ($all -join ' '))
    if ($DryRun) { return @{ ExitCode = 0; Output = '' } }
    $ErrorActionPreference = 'Continue'
    $out = & $script:Adb @all 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $text = ($out -join "`n")
    if (-not $AllowFail -and $code -ne 0) {
        throw "adb exited $code : $text"
    }
    return @{ ExitCode = $code; Output = $text }
}

function Test-IsAdbErrorText {
    [CmdletBinding()]
    param($Text)
    $s = ConvertTo-ScalarString $Text
    if ([string]::IsNullOrWhiteSpace($s) -or $s.Length -lt 200) { return $true }
    return [bool]($s -match '(?i)unknown command|adb\.exe:|error: closed|device offline|no devices')
}

function Save-Text {
    [CmdletBinding()]
    param([string]$Name, [string]$Text)
    if (-not $script:EvidenceDir) { return }
    $path = Join-Path $script:EvidenceDir $Name
    if ($null -eq $Text) { $Text = '' }
    Set-Content -Path $path -Value $Text -Encoding UTF8
}

function Save-AdbDump {
    [CmdletBinding()]
    param([string]$Name, [string[]]$DumpArgs)
    $r = Invoke-AdbArgList -AllowFail -ArgList $DumpArgs
    Save-Text $Name $r.Output
    return $r.Output
}

# --- results ----------------------------------------------------------------
function Record-Result {
    [CmdletBinding()]
    param(
        [string]$Id,
        [ValidateSet('PASS','FAIL','RECORD','MANUAL','SKIP','NOT RUN')]
        [string]$Status,
        [string]$Detail
    )
    $nid = Normalize-TestId $Id
    Write-Log ("{0}  {1}  {2}" -f $nid, $Status, $Detail)
    $script:Results.Add([pscustomobject]@{ Id = $nid; Status = $Status; Detail = $Detail })
}

# --- git / build ------------------------------------------------------------
function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return $env:JAVA_HOME
    }
    $globs = @(
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        'C:\Program Files\Microsoft\jdk-17*',
        'C:\Program Files\Java\jdk-17*',
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Eclipse Adoptium\jdk-21*',
        'C:\Program Files\Java\jdk-21*'
    )
    foreach ($g in $globs) {
        $hits = @(Get-Item -Path $g -ErrorAction SilentlyContinue)
        if ($hits.Count -gt 0) { return $hits[0].FullName }
    }
    return $null
}

function Ensure-Checkout {
    if ($DryRun) {
        Write-Log "DryRun: would clone/fetch $RepoUrl into $RepoDir and checkout $ExpectedSha"
        return
    }
    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) {
        throw "git is not on PATH. Install Git for Windows, then re-run."
    }
    if (-not (Test-Path (Join-Path $RepoDir '.git'))) {
        if (Test-Path $RepoDir) {
            $others = @(Get-ChildItem -Force $RepoDir)
            if ($others.Count -gt 0) {
                throw "$RepoDir exists and is not a git clone. Move it aside and re-run."
            }
        }
        Write-Log "Cloning $RepoUrl into $RepoDir"
        Invoke-Git clone $RepoUrl $RepoDir
        if ($LASTEXITCODE -ne 0) { throw "git clone failed ($LASTEXITCODE)" }
    }
    Push-Location $RepoDir
    try {
        Invoke-Git fetch origin
        if ($LASTEXITCODE -ne 0) { throw "git fetch failed ($LASTEXITCODE)" }
        # Prefer the PR head ref, then the raw SHA.
        Invoke-Git fetch origin pull/2/head:refs/remotes/origin/pr-2
        Invoke-Git checkout --detach $ExpectedSha
        if ($LASTEXITCODE -ne 0) { throw "git checkout $ExpectedSha failed ($LASTEXITCODE)" }
        $head = (Invoke-Git-Out rev-parse HEAD).Trim()
        $resolved = (Invoke-Git-Out rev-parse --verify $ExpectedSha).Trim()
        if (-not $resolved) { throw "Could not resolve $ExpectedSha after checkout." }
        if ($head -ne $resolved) {
            throw "HEAD is $head, expected $resolved (from -Ref $ExpectedSha). Refusing to continue."
        }
        Write-Log ("Checkout OK: {0} (requested -Ref {1})" -f $head, $ExpectedSha)
    } finally {
        Pop-Location
    }
}

function Invoke-BuildAndInstall {
    if ($DryRun) {
        Write-Log "DryRun: would run gradlew.bat assembleDebug and adb install -r"
        return
    }
    $javaHome = Find-JavaHome
    if ($javaHome) {
        $env:JAVA_HOME = $javaHome
        Write-Log "JAVA_HOME=$javaHome"
    } else {
        $java = Get-Command java -ErrorAction SilentlyContinue
        if (-not $java) { throw "JDK not found. Install JDK 17 or set JAVA_HOME." }
        Write-Log "JAVA_HOME unset; using java on PATH"
    }
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_SDK_ROOT = $AndroidHome

    $wrapper = Join-Path $RepoDir 'gradlew.bat'
    if (-not (Test-Path $wrapper)) { throw "gradlew.bat not found in $RepoDir" }

    Push-Location $RepoDir
    try {
        Write-Log "Running gradlew.bat assembleDebug"
        $ErrorActionPreference = 'Continue'
        & cmd.exe /c "`"$wrapper`" assembleDebug --no-daemon 2>&1" | ForEach-Object { Write-Host "$_" }
        if ($LASTEXITCODE -ne 0) { throw "gradlew assembleDebug failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    $apk = Join-Path $RepoDir 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw "Debug APK not found at $apk" }
    Copy-Item $apk (Join-Path $script:EvidenceDir 'app-debug.apk') -Force

    $dev = Invoke-Adb 'get-state' -AllowFail
    if ($dev.Output.Trim() -ne 'device') {
        throw "adb device $Serial is not authorised (state='$($dev.Output)'). Plug in the S24+, accept the RSA prompt, and re-run."
    }

    Invoke-Adb 'install' '-r' $apk | Out-Null
    Invoke-AdbShell "pm grant $Pkg android.permission.POST_NOTIFICATIONS" -AllowFail | Out-Null
    # A freshly installed app is in the stopped state until first launch.
    Invoke-AdbShell "am start -n $Pkg/.MainActivity" -AllowFail | Out-Null
    Start-Sleep -Seconds 3
}

# --- logcat / reminders -----------------------------------------------------
function Start-DoLogcat {
    if ($DryRun) { return }
    Invoke-Adb 'logcat' '-c' -AllowFail | Out-Null
    $script:LogcatFile = Join-Path $script:EvidenceDir 'Do-logcat-DoAlarm.txt'
    $arg = "-s $Serial logcat -v threadtime -s DoAlarm:D *:S"
    $script:LogcatProc = Start-Process -FilePath $script:Adb -ArgumentList @('-s', $Serial, 'logcat', '-v', 'threadtime', '-s', 'DoAlarm:D', '*:S') -RedirectStandardOutput $script:LogcatFile -RedirectStandardError (Join-Path $script:EvidenceDir 'Do-logcat-DoAlarm.err.txt') -PassThru -WindowStyle Hidden
    Write-Log "logcat pid $($script:LogcatProc.Id)"
}

function Stop-DoLogcat {
    if ($script:LogcatProc -and -not $script:LogcatProc.HasExited) {
        try { Stop-Process -Id $script:LogcatProc.Id -Force -ErrorAction SilentlyContinue } catch { }
    }
    $script:LogcatProc = $null
}

function Get-LogcatText {
    if ($script:LogcatFile -and (Test-Path $script:LogcatFile)) {
        return Get-Content -Path $script:LogcatFile -Raw -ErrorAction SilentlyContinue
    }
    return ''
}

function Get-DebugAdd {
    [CmdletBinding()]
    param([string]$Title, [int]$WaitSeconds = 15)
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $pattern = 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)'
    do {
        $text = Get-LogcatText
        $matches_ = [regex]::Matches($text, $pattern)
        foreach ($m in $matches_) {
            # Use the last add; callers start from a known point when possible.
        }
        if ($matches_.Count -gt 0) {
            $m = $matches_[$matches_.Count - 1]
            return @{
                Id        = [int64]$m.Groups[1].Value
                DueAt     = [int64]$m.Groups[2].Value
                NagMinutes = [int]$m.Groups[3].Value
            }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Get-FiresForId {
    [CmdletBinding()]
    param($Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $text = Get-LogcatText
    $pattern = "DoAlarm fire id=$id scheduled=(\d+) actual=(\d+)"
    $list = New-QaList
    if ($text) {
        foreach ($m in [regex]::Matches($text, $pattern)) {
            $sched = [int64]$m.Groups[1].Value
            $act   = [int64]$m.Groups[2].Value
            [void]$list.Add(@{ Scheduled = $sched; Actual = $act; Delta = ($act - $sched) })
        }
    }
    return $list
}

function Test-TimingOk {
    [CmdletBinding()]
    param($Scheduled, $Actual)
    $sched = ConvertTo-Int64Scalar $Scheduled -Name 'Scheduled'
    $act = ConvertTo-Int64Scalar $Actual -Name 'Actual'
    $delta = $act - $sched
    if ($delta -lt 0) { return $false }
    if ($delta -gt $PhoneTolMs) { return $false }
    return $true
}

function Add-Reminder {
    [CmdletBinding()]
    param([string]$Title, [int]$InSeconds, [int]$NagMinutes)
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    $cmd = "am broadcast -a $AddAction -n $Recv --es title $Title --ei inSeconds $InSeconds --ei nagMinutes $NagMinutes"
    Invoke-AdbShell $cmd -AllowFail | Out-Null
    if ($DryRun) { return @{ Id = 0; DueAt = 0; NagMinutes = $NagMinutes } }
    $deadline = (Get-Date).AddSeconds(20)
    $pattern = 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)'
    do {
        $text = Get-LogcatText
        if ($text -and $text.Length -ge $beforeLen) {
            $slice = $text.Substring($beforeLen)
            $m = [regex]::Match($slice, $pattern)
            if ($m.Success) {
                $info = @{
                    Id         = [int64]$m.Groups[1].Value
                    DueAt      = [int64]$m.Groups[2].Value
                    NagMinutes = [int]$m.Groups[3].Value
                    Title      = $Title
                }
                Write-Log ("ADD {0} id={1} dueAt={2} nag={3}" -f $Title, $info.Id, $info.DueAt, $info.NagMinutes)
                return $info
            }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw "Debug ADD did not log id/dueAt for $Title. Check $script:LogcatFile"
}

function Done-Reminder {
    [CmdletBinding()]
    param($Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    Invoke-AdbShell "am broadcast -a $DoneAction -n $Recv --el id $id" -AllowFail | Out-Null
}

function ConvertFrom-UnixMs {
    [CmdletBinding()]
    param($EpochMs)
    $ms = ConvertTo-Int64Scalar $EpochMs -Name 'EpochMs'
    $origin = [DateTime]::SpecifyKind([DateTime]'1970-01-01', 'Utc')
    return $origin.AddMilliseconds($ms).ToLocalTime()
}

function ConvertTo-UnixMs {
    [CmdletBinding()]
    param([datetime]$When)
    $origin = [DateTime]::SpecifyKind([DateTime]'1970-01-01', 'Utc')
    return [int64]($When.ToUniversalTime() - $origin).TotalMilliseconds
}

function Wait-UntilMs {
    [CmdletBinding()]
    param($EpochMs, [int]$ExtraSeconds = 65)
    if ($DryRun) { return }
    $ms = ConvertTo-Int64Scalar $EpochMs -Name 'EpochMs'
    $target = ConvertFrom-UnixMs ($ms + ($ExtraSeconds * 1000))
    $now = Get-Date
    if ($target -gt $now) {
        $secs = [int]([Math]::Ceiling(($target - $now).TotalSeconds))
        Write-Log "sleep ${secs}s (until fire window)"
        Start-Sleep -Seconds $secs
    }
}

function Wait-ForFireCount {
    [CmdletBinding()]
    param($Id, [int]$Count, $UntilEpochMs)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    if ($DryRun) { return (New-QaList) }
    $until = ConvertTo-Int64Scalar $UntilEpochMs -Name 'UntilEpochMs'
    $deadline = ConvertFrom-UnixMs $until
    do {
        $fires = Get-FlatFires (Get-FiresForId $id)
        if ($fires.Count -ge $Count) { return $fires }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    return (Get-FlatFires (Get-FiresForId $id))
}

function Get-NextNagAfter {
    [CmdletBinding()]
    param($DueAt, [int]$NagMinutes, $NowMs)
    # Same rule as NagSchedule.nextAnchorStrictlyAfter: first dueAt + k * nagMinutes
    # strictly after $NowMs. After a missed fire at "now", the next nag is the next
    # grid slot, not dueAt + nagMinutes (already in the past when APPLY_QAT moves
    # the target back an hour).
    $due = ConvertTo-Int64Scalar $DueAt -Name 'DueAt'
    $now = ConvertTo-Int64Scalar $NowMs -Name 'NowMs'
    if ($NagMinutes -le 0) { return $null }
    $interval = [int64]$NagMinutes * [int64]60000
    if ($now -lt $due) { return $due }
    $elapsed = $now - $due
    $steps = [int64][Math]::Floor([double]$elapsed / [double]$interval)
    $boundary = $due + ($steps * $interval)
    if ($boundary -gt $now) { return $boundary }
    return ($boundary + $interval)
}

function Test-HasPendingDoAlarm {
    [CmdletBinding()]
    param($Dump)
    # True only when a current pending Alarm{...} belongs to $Pkg.
    # dumpsys also mentions the package in Alarm Stats and in Add/Remove/Deliver
    # history long after every reminder is Done; a raw package substring match
    # is a false leftover WARN (pretest 2026-11-25 12:55, nothing was pending).
    $Dump = ConvertTo-ScalarString $Dump
    if (-not $Dump) { return $false }
    $pkgEsc = [regex]::Escape($Pkg)
    $m = [regex]::Match($Dump, '(?ms)^\s*\d+ pending alarms:.*?(?=^\s*(?:Pending user blocked|Pending alarms per uid|Alarm Stats:)|\z)')
    $slice = if ($m.Success) { $m.Groups[0].Value } else { $Dump }
    return [bool]($slice -match ('Alarm\{[^}\r\n]*' + $pkgEsc))
}

function Save-AlarmDump {
    [CmdletBinding()]
    param([string]$Tag)
    $name = $Tag
    if ($name -notlike 'Do-*') { $name = "Do-$name" }
    if ($name -notlike '*-dumpsys-alarm.txt') {
        $name = ($name -replace '\.txt$', '') + '-dumpsys-alarm.txt'
    }
    return Save-AdbDump $name @('shell', 'dumpsys', 'alarm')
}

function Invoke-NotifDump {
    $r = Invoke-AdbArgList -AllowFail -ArgList @('shell', 'dumpsys', 'notification', '--noredact')
    if ($r.ExitCode -ne 0 -or (Test-IsAdbErrorText $r.Output)) {
        $r = Invoke-AdbArgList -AllowFail -ArgList @('shell', 'dumpsys', 'notification')
    }
    return $r
}

function Save-NotifDump {
    [CmdletBinding()]
    param([string]$Tag)
    $r = Invoke-NotifDump
    Save-Text "Do-$Tag-dumpsys-notification.txt" $r.Output
    return $r.Output
}

function Test-AlarmClockPresent {
    [CmdletBinding()]
    param($Dump, $Id)
    # Returns @{ Present = $true/$false; TriggerTime = [int64] or $null }.
    # Match an alarm-clock alarm that belongs to $Pkg, not some other app.
    # Android 12-16 often has alarmClock= or AlarmClockInfo on the entry.
    # Android 17 dumpsys uses a nested "Alarm clock:" block, tag
    # *walarm*:com.evansappstudio.doapp.ALARM, and exactAllowReason=policy_permission.
    $empty = @{ Present = $false; TriggerTime = $null }
    $Dump = ConvertTo-ScalarString $Dump
    if ($PSBoundParameters.ContainsKey('Id') -and $null -ne $Id -and "$Id" -ne '') {
        $Id = ConvertTo-Int64Scalar $Id -Name 'Id'
    } else {
        $Id = [int64]0
    }
    if (-not $Dump) { return $empty }

    $pkgEsc = [regex]::Escape($Pkg)
    $lines = $Dump -split '\r?\n'
    $entries = New-Object System.Collections.Generic.List[object]
    $buf = New-Object System.Collections.Generic.List[string]
    foreach ($line in $lines) {
        $starts = $false
        if ($line -match '^\s*Alarm clock:') {
            $starts = $false
        } elseif ($line -match '^\s*(RTC_WAKEUP|RTC |ELAPSED_WAKEUP|ELAPSED )\b') {
            $starts = $true
        } elseif ($line -match '^\s*Alarm\{') {
            $starts = $true
        } elseif ($line -match '^\s*Batch\{') {
            $starts = $true
        }
        if ($starts -and $buf.Count -gt 0) {
            $entries.Add($buf.ToArray())
            $buf.Clear()
        }
        [void]$buf.Add($line)
    }
    if ($buf.Count -gt 0) { $entries.Add($buf.ToArray()) }

    foreach ($entryLines in $entries) {
        $entry = [string]::Join("`n", $entryLines)
        $ours = $false
        if ($entry -match ("\*walarm\*:" + $pkgEsc)) { $ours = $true }
        if ($entry -match ("(?i)package\s*[=:]\s*" + $pkgEsc)) { $ours = $true }
        if ($entry -match ($pkgEsc + '\.ALARM')) { $ours = $true }
        if ($entry -match ($pkgEsc + '/')) { $ours = $true }
        if ($entry -match ('RTC_WAKEUP.*' + $pkgEsc)) { $ours = $true }
        if (-not $ours) { continue }

        if ($Id -gt 0) {
            $idEsc = [regex]::Escape([string]$Id)
            $mentionsThisId = $false
            $mentionsOtherId = $false
            if ($entry -match ("doapp://reminder/" + $idEsc + '\b')) { $mentionsThisId = $true }
            if ($entry -match ("(?i)\bid\s*[=:]\s*" + $idEsc + '\b')) { $mentionsThisId = $true }
            $idHits = [regex]::Matches($entry, 'doapp://reminder/(\d+)')
            foreach ($hit in $idHits) {
                if ($hit.Groups[1].Value -eq [string]$Id) { $mentionsThisId = $true }
                else { $mentionsOtherId = $true }
            }
            if ($mentionsOtherId -and -not $mentionsThisId) { continue }
        }

        $isRecord = $false
        if ($entry -match '\bRTC_WAKEUP\b') { $isRecord = $true }
        if ($entry -match '(?i)type\s*=\s*RTC_WAKEUP') { $isRecord = $true }
        if ($entry -match 'Alarm\{') { $isRecord = $true }
        if (-not $isRecord) { continue }

        $isClock = $false
        if ($entry -match '(?m)^\s*Alarm clock:') { $isClock = $true }
        if ($entry -match 'alarmClock\s*=') { $isClock = $true }
        if ($entry -match 'AlarmClockInfo') { $isClock = $true }
        if (-not $isClock) { continue }

        $trigger = Parse-AlarmTriggerMs $entry
        return @{ Present = $true; TriggerTime = $trigger }
    }
    return $empty
}

# --- restore ---------------------------------------------------------------
function Capture-Originals {
    if ($DryRun) { return }
    $script:Originals = @{
        AutoTime       = (Invoke-AdbShell 'settings get global auto_time' -AllowFail).Output.Trim()
        AutoTimeZone   = (Invoke-AdbShell 'settings get global auto_time_zone' -AllowFail).Output.Trim()
        TimeZone       = (Invoke-AdbShell 'getprop persist.sys.timezone' -AllowFail).Output.Trim()
        LowPower       = (Invoke-AdbShell 'settings get global low_power' -AllowFail).Output.Trim()
        StandbyBucket  = (Invoke-AdbShell "am get-standby-bucket $Pkg" -AllowFail).Output.Trim()
        Inactive       = (Invoke-AdbShell "am get-inactive $Pkg" -AllowFail).Output.Trim()
    }
    $lines = $script:Originals.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }
    Save-Text 'Do-original-phone-settings.txt' ($lines -join "`n")
    Write-Log ("Originals: " + ($lines -join '; '))
}

function Restore-Phone {
    Write-Log 'Restoring phone state'
    if ($DryRun) {
        Write-Log 'DryRun: dumpsys deviceidle unforce; battery reset; auto_time/auto_time_zone=1; restore zone; low_power if it was off; standby bucket reset'
        return
    }
    Invoke-AdbShell 'dumpsys deviceidle unforce' -AllowFail | Out-Null
    Invoke-AdbShell 'dumpsys battery reset' -AllowFail | Out-Null
    Invoke-AdbShell 'settings put global auto_time 1' -AllowFail | Out-Null
    Invoke-AdbShell 'settings put global auto_time_zone 1' -AllowFail | Out-Null
    $origZone = $script:Originals['TimeZone']
    if ($origZone) {
        Invoke-AdbShell "service call alarm 3 s16 $origZone" -AllowFail | Out-Null
    }
    $origLp = $script:Originals['LowPower']
    if ($origLp -eq '0' -or $origLp -eq 'null' -or [string]::IsNullOrWhiteSpace($origLp)) {
        Invoke-AdbShell 'settings put global low_power 0' -AllowFail | Out-Null
    } elseif ($origLp) {
        Invoke-AdbShell "settings put global low_power $origLp" -AllowFail | Out-Null
    }
    Invoke-AdbShell "am set-inactive $Pkg false" -AllowFail | Out-Null
    $bucket = $script:Originals['StandbyBucket']
    if ($bucket -and $bucket -notmatch 'Error|Exception|Unknown') {
        Invoke-AdbShell "am set-standby-bucket $Pkg active" -AllowFail | Out-Null
        if ($bucket -match '^\d+$' -or $bucket -match 'active|working|frequent|rare|restricted') {
            Invoke-AdbShell "am set-standby-bucket $Pkg $bucket" -AllowFail | Out-Null
        }
    } else {
        Invoke-AdbShell "am set-standby-bucket $Pkg active" -AllowFail | Out-Null
    }
    $after = @(
        'auto_time=' + (Invoke-AdbShell 'settings get global auto_time' -AllowFail).Output.Trim()
        'auto_time_zone=' + (Invoke-AdbShell 'settings get global auto_time_zone' -AllowFail).Output.Trim()
        'timezone=' + (Invoke-AdbShell 'getprop persist.sys.timezone' -AllowFail).Output.Trim()
        'low_power=' + (Invoke-AdbShell 'settings get global low_power' -AllowFail).Output.Trim()
        'deep=' + (Invoke-AdbShell 'dumpsys deviceidle get deep' -AllowFail).Output.Trim()
    ) -join "; "
    Write-Log "Restore after: $after"
    Save-Text 'Do-restore-phone-settings.txt' $after
}


# --- Spec 2 helpers ---------------------------------------------------------
function Get-SnoozesForId {
    [CmdletBinding()]
    param($Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $text = Get-LogcatText
    $pattern = "DoAlarm snooze id=$id newDue=(\d+) option=(\d+)"
    $list = New-QaList
    if ($text) {
        foreach ($m in [regex]::Matches($text, $pattern)) {
            $due = [int64]$m.Groups[1].Value
            $opt = [int]$m.Groups[2].Value
            [void]$list.Add(@{ NewDue = $due; Option = $opt })
        }
    }
    return $list
}

function Get-LatestSnooze {
    [CmdletBinding()]
    param($Id, [int]$WaitSeconds = 15)
    $Id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    do {
        $text = Get-LogcatText
        if ($text -and $text.Length -ge $beforeLen) {
            $slice = $text.Substring($beforeLen)
        } else {
            $slice = $text
        }
        if ($slice) {
            $pattern = "DoAlarm snooze id=$Id newDue=(\d+) option=(\d+)"
            $matches_ = [regex]::Matches($slice, $pattern)
            if ($matches_.Count -gt 0) {
                $m = $matches_[$matches_.Count - 1]
                return @{ NewDue = [int64]$m.Groups[1].Value; Option = [int]$m.Groups[2].Value }
            }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Snooze-Reminder {
    [CmdletBinding()]
    param($Id, [int]$Option)
    $Id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    $cmd = "am broadcast -a $SnoozeAction -n $Recv --el id $Id --ei option $Option"
    Invoke-AdbShell $cmd -AllowFail | Out-Null
    if ($DryRun) { return @{ NewDue = 0; Option = $Option; ActionMs = 0 } }
    $deadline = (Get-Date).AddSeconds(20)
    $pattern = "DoAlarm snooze id=$Id newDue=(\d+) option=(\d+)"
    $actionMs = ConvertTo-UnixMs (Get-Date)
    do {
        $text = Get-LogcatText
        if ($text -and $text.Length -ge $beforeLen) {
            $slice = $text.Substring($beforeLen)
            $m = [regex]::Match($slice, $pattern)
            if ($m.Success) {
                $info = @{
                    NewDue   = [int64]$m.Groups[1].Value
                    Option   = [int]$m.Groups[2].Value
                    ActionMs = $actionMs
                }
                Write-Log ("SNOOZE id={0} newDue={1} option={2}" -f $Id, $info.NewDue, $info.Option)
                return $info
            }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw "Debug SNOOZE did not log newDue for id=$Id option=$Option. Check $script:LogcatFile"
}

function Config-Snooze {
    [CmdletBinding()]
    param([int]$Option, [string]$Kind, [int]$Value)
    $cmd = "am broadcast -a $ConfigAction -n $Recv --ei option $Option --es kind $Kind --ei value $Value"
    Invoke-AdbShell $cmd -AllowFail | Out-Null
    if ($DryRun) { return $true }
    $deadline = (Get-Date).AddSeconds(15)
    $pattern = "DoAlarm config-snooze option=$Option kind=$Kind value=$Value"
    do {
        $text = Get-LogcatText
        if ($text -and $text.Contains($pattern)) {
            Write-Log $pattern
            $script:SnoozeOptionsTouched = $true
            return $true
        }
        if ($text -and $text.Contains('DoAlarm config-snooze bad args')) {
            throw "CONFIG_SNOOZE rejected option=$Option kind=$Kind value=$Value"
        }
        if ($text -and $text.Contains('DoAlarm config-snooze user locked')) {
            throw "CONFIG_SNOOZE failed: user locked"
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw "CONFIG_SNOOZE did not log option=$Option kind=$Kind value=$Value"
}

function Restore-SnoozeDefaults {
    if ($DryRun) { return }
    if (-not $script:SnoozeOptionsTouched) { return }
    Write-Log 'Restoring factory snooze options (+1 hour, +3 hours, +1 day)'
    try { Config-Snooze -Option 0 -Kind 'FROM_NOW' -Value 60 | Out-Null } catch { Write-Log ("restore option 0 failed: " + $_.Exception.Message) }
    try { Config-Snooze -Option 1 -Kind 'FROM_NOW' -Value 180 | Out-Null } catch { Write-Log ("restore option 1 failed: " + $_.Exception.Message) }
    try { Config-Snooze -Option 2 -Kind 'FROM_NOW' -Value 1440 | Out-Null } catch { Write-Log ("restore option 2 failed: " + $_.Exception.Message) }
    $script:SnoozeOptionsTouched = $false
}

function Get-NotifDump {
    return (Invoke-NotifDump).Output
}

function Test-ActionTitlesPresent {
    [CmdletBinding()]
    param($Dump, [string[]]$Titles, $Id)
    $hay = ConvertTo-ScalarString $Dump
    $missing = New-Object System.Collections.Generic.List[string]
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($t in @($Titles)) {
        if ([string]::IsNullOrWhiteSpace([string]$t)) { continue }
        if (Test-TextContains $hay $t) { [void]$found.Add($t) }
        else { [void]$missing.Add($t) }
    }
    return @{
        Present = (($missing.Count -eq 0) -and ($found.Count -gt 0))
        Missing = @($missing)
        Found   = @($found)
        Hit     = (@($found) -join ', ')
    }
}

function Save-Screenshot {
    [CmdletBinding()]
    param([string]$Name)
    $remote = "/data/local/tmp/Do-qa-shot.png"
    $local = Join-Path $script:EvidenceDir $Name
    if (Test-Path $local) { Remove-Item $local -Force -ErrorAction SilentlyContinue }
    $cap = Invoke-AdbArgList -AllowFail -ArgList @('shell', 'screencap', '-p', $remote)
    $pull = Invoke-AdbArgList -AllowFail -ArgList @('pull', $remote, $local)
    Write-Log ("screenshot $Name capExit=$($cap.ExitCode) pullExit=$($pull.ExitCode) pullOut=$($pull.Output)")
    Invoke-AdbShell "rm -f $remote" -AllowFail | Out-Null
    if ((Test-Path $local) -and ((Get-Item $local).Length -gt 100)) {
        Write-Log "screenshot $Name"
        return $local
    }
    # Fallback: exec-out writes PNG bytes to the local file (avoids /data/local/tmp + pull).
    if ($DryRun) { return $null }
    try {
        $ErrorActionPreference = 'Continue'
        $tmpRaw = Join-Path $script:EvidenceDir ($Name + '.raw')
        $p = Start-Process -FilePath $script:Adb -ArgumentList @('-s', $Serial, 'exec-out', 'screencap', '-p') -RedirectStandardOutput $tmpRaw -RedirectStandardError (Join-Path $script:EvidenceDir ($Name + '.err.txt')) -Wait -PassThru -WindowStyle Hidden
        if ((Test-Path $tmpRaw) -and ((Get-Item $tmpRaw).Length -gt 100)) {
            Move-Item $tmpRaw $local -Force
            Write-Log "screenshot $Name via exec-out ($((Get-Item $local).Length) bytes)"
            return $local
        }
        Write-Log ("screenshot $Name FAILED cap=$($cap.Output) exec-out exit=$($p.ExitCode)")
    } catch {
        Write-Log ("screenshot $Name FAILED: " + $_.Exception.Message)
    }
    return $null
}

function Capture-ExpandedShade {
    [CmdletBinding()]
    param([string]$Name)
    Invoke-AdbShell 'cmd statusbar expand-notifications' -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    $path = Save-Screenshot $Name
    Invoke-AdbShell 'cmd statusbar collapse' -AllowFail | Out-Null
    return $path
}

function Capture-LockScreen {
    [CmdletBinding()]
    param([string]$Name)
    # No PIN is set or changed. A secure lock may show redacted content; that is accepted.
    Invoke-AdbShell 'input keyevent KEYCODE_SLEEP' -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    Invoke-AdbShell 'input keyevent KEYCODE_WAKEUP' -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    $path = Save-Screenshot $Name
    Invoke-AdbShell 'input keyevent KEYCODE_WAKEUP' -AllowFail | Out-Null
    return $path
}

function Get-PidOfPkg {
    $r = Invoke-AdbShell "pidof $Pkg" -AllowFail
    return ($r.Output -replace '\s+', ' ').Trim()
}

function Wait-ForFirstFire {
    [CmdletBinding()]
    param($Add)
    if ($DryRun) { return (New-QaList) }
    $due = ConvertTo-Int64Scalar $Add.DueAt -Name 'DueAt'
    $id = ConvertTo-Int64Scalar $Add.Id -Name 'Id'
    Wait-UntilMs $due
    return (Get-FlatFires (Wait-ForFireCount -Id $id -Count 1 -UntilEpochMs ($due + $PhoneTolMs + 5000)))
}

function Assert-NewDue {
    [CmdletBinding()]
    param($NewDue, $Expected, [string]$Label)
    $due = ConvertTo-Int64Scalar $NewDue -Name 'NewDue'
    $exp = ConvertTo-Int64Scalar $Expected -Name 'Expected'
    $delta = $due - $exp
    if ($delta -lt 0) { $delta = -$delta }
    if ($delta -le $SnoozeTolMs) { return $true }
    Write-Log ("$Label newDue=$due expected=$exp absDelta=${delta}ms (limit ${SnoozeTolMs}ms)")
    return $false
}

# --- Spec 3 helpers ---------------------------------------------------------
$script:QatTouched = $false
$script:FactorySlots = @(
    @{ Kind = 'AT_TIME';  Amount = 570;  Unit = 'MINUTE'; Label12 = '9:30 am';  Label24 = '09:30' },
    @{ Kind = 'AT_TIME';  Amount = 720;  Unit = 'MINUTE'; Label12 = '12:00 pm'; Label24 = '12:00' },
    @{ Kind = 'AT_TIME';  Amount = 1110; Unit = 'MINUTE'; Label12 = '6:30 pm';  Label24 = '18:30' },
    @{ Kind = 'AT_TIME';  Amount = 1320; Unit = 'MINUTE'; Label12 = '10:00 pm'; Label24 = '22:00' },
    @{ Kind = 'FROM_NOW'; Amount = 10;   Unit = 'MINUTE'; Label12 = '+10 min';  Label24 = '+10 min' },
    @{ Kind = 'FROM_NOW'; Amount = 1;    Unit = 'HOUR';   Label12 = '+1 hr';    Label24 = '+1 hr' },
    @{ Kind = 'FROM_NOW'; Amount = 3;    Unit = 'HOUR';   Label12 = '+3 hr';    Label24 = '+3 hr' },
    @{ Kind = 'FROM_NOW'; Amount = 1;    Unit = 'DAY';    Label12 = '+1 day';   Label24 = '+1 day' },
    @{ Kind = 'FROM_NOW'; Amount = -10;  Unit = 'MINUTE'; Label12 = '-10 min';  Label24 = '-10 min' },
    @{ Kind = 'FROM_NOW'; Amount = -1;   Unit = 'HOUR';   Label12 = '-1 hr';    Label24 = '-1 hr' },
    @{ Kind = 'FROM_NOW'; Amount = -3;   Unit = 'HOUR';   Label12 = '-3 hr';    Label24 = '-3 hr' },
    @{ Kind = 'FROM_NOW'; Amount = -1;   Unit = 'DAY';    Label12 = '-1 day';   Label24 = '-1 day' }
)

function Parse-QatConfigLine {
    [CmdletBinding()]
    param([string]$Line)
    $m = [regex]::Match($Line, 'DoAlarm qat-config postpone=(\w+) slot=(\d+) kind=(AT_TIME|FROM_NOW) amount=(-?\d+)(?: unit=(MINUTE|HOUR|DAY|WEEK))? label=(.+)\s*$')
    if (-not $m.Success) { return $null }
    return @{
        Postpone = $m.Groups[1].Value
        Slot     = [int]$m.Groups[2].Value
        Kind     = $m.Groups[3].Value
        Amount   = [int]$m.Groups[4].Value
        Unit     = $m.Groups[5].Value
        Label    = $m.Groups[6].Value.Trim()
    }
}

function Parse-QatApplyLine {
    [CmdletBinding()]
    param([string]$Line)
    $m = [regex]::Match($Line, 'DoAlarm qat id=(\d+) newDue=(\d+) slot=(\d+)')
    if (-not $m.Success) { return $null }
    return @{
        Id     = [int64]$m.Groups[1].Value
        NewDue = [int64]$m.Groups[2].Value
        Slot   = [int]$m.Groups[3].Value
    }
}

function Get-LocalWallMs {
    [CmdletBinding()]
    param([int]$Hour, [int]$Minute, [int]$AddDays = 0, [datetime]$From = ([datetime]::MinValue))
    if ($From -eq [datetime]::MinValue) { $From = Get-Date }
    $base = $From.Date.AddDays($AddDays).AddHours($Hour).AddMinutes($Minute)
    return ConvertTo-UnixMs $base
}

function Get-NowMs {
    return ConvertTo-UnixMs (Get-Date)
}

function Add-DurationMs {
    [CmdletBinding()]
    param([int64]$BaseMs, [int]$Amount, [string]$Unit)
    $local = ConvertFrom-UnixMs $BaseMs
    switch ($Unit) {
        'MINUTE' { return ConvertTo-UnixMs ($local.AddMinutes($Amount)) }
        'HOUR'   { return ConvertTo-UnixMs ($local.AddHours($Amount)) }
        'DAY'    { return ConvertTo-UnixMs ($local.AddDays($Amount)) }
        'WEEK'   { return ConvertTo-UnixMs ($local.AddDays(7 * $Amount)) }
        default  { throw "unknown unit $Unit" }
    }
}

function Get-ExpectedAtTime {
    [CmdletBinding()]
    param([int]$MinutesFromMidnight, [int64]$DraftDueAt, [int64]$NowMs, [string]$Day = '')
    $nowLocal = ConvertFrom-UnixMs $NowMs
    $dueLocal = ConvertFrom-UnixMs $DraftDueAt
    $hour = [int][Math]::Floor($MinutesFromMidnight / 60)
    $minute = $MinutesFromMidnight % 60
    if ($Day -eq 'TODAY') {
        return Get-LocalWallMs -Hour $hour -Minute $minute -From $nowLocal
    }
    if ($Day -eq 'TOMORROW') {
        return Get-LocalWallMs -Hour $hour -Minute $minute -AddDays 1 -From $nowLocal
    }
    if ($Day -eq 'DUE') {
        return Get-LocalWallMs -Hour $hour -Minute $minute -From $dueLocal
    }
    $date = $dueLocal.Date
    if ($date -lt $nowLocal.Date) { $date = $nowLocal.Date }
    for ($i = 0; $i -lt 5; $i++) {
        $candidate = ConvertTo-UnixMs ($date.AddHours($hour).AddMinutes($minute))
        if ($candidate -gt $NowMs) { return $candidate }
        $date = $date.AddDays(1)
    }
    return ConvertTo-UnixMs ($date.AddHours($hour).AddMinutes($minute))
}

function Get-ExpectedFromNow {
    [CmdletBinding()]
    param([int64]$DraftDueAt, [int64]$NowMs, [int]$Amount, [string]$Unit, [string]$Postpone = 'AUTO', [string]$Base = '')
    $baseMs = $DraftDueAt
    if ($Base -eq 'NOW') { $baseMs = $NowMs }
    elseif ($Base -eq 'DUE') { $baseMs = $DraftDueAt }
    elseif ($DraftDueAt -le $NowMs) {
        switch ($Postpone) {
            'FROM_DUE' { $baseMs = $DraftDueAt }
            'FROM_NOW' { $baseMs = $NowMs }
            default {
                $fromDue = Add-DurationMs -BaseMs $DraftDueAt -Amount $Amount -Unit $Unit
                if ($fromDue -gt $NowMs) { $baseMs = $DraftDueAt } else { $baseMs = $NowMs }
            }
        }
    }
    return (Add-DurationMs -BaseMs $baseMs -Amount $Amount -Unit $Unit)
}

function Add-ReminderDueAt {
    [CmdletBinding()]
    param([string]$Title, [int64]$DueAtMs, [int]$NagMinutes)
    $now = Get-NowMs
    $inSeconds = [int][Math]::Round(($DueAtMs - $now) / 1000.0)
    return Add-Reminder -Title $Title -InSeconds $inSeconds -NagMinutes $NagMinutes
}

function Config-Qat {
    [CmdletBinding()]
    param([int]$Slot, [string]$Kind, [int]$Amount, [string]$Unit = 'MINUTE')
    $script:QatTouched = $true
    $cmd = "am broadcast -a $ConfigQatAction -n $Recv --ei slot $Slot --es kind $Kind --ei amount $Amount --es unit $Unit"
    Invoke-AdbShell $cmd -AllowFail | Out-Null
}

function Config-Postpone {
    [CmdletBinding()]
    param([string]$Value)
    $script:QatTouched = $true
    $cmd = "am broadcast -a $ConfigPostponeAction -n $Recv --es value $Value"
    Invoke-AdbShell $cmd -AllowFail | Out-Null
}

function Dump-Qat {
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Invoke-AdbShell "am broadcast -a $DumpQatAction -n $Recv" -AllowFail | Out-Null
    if ($DryRun) { return @() }
    $deadline = (Get-Date).AddSeconds(15)
    $pattern = 'DoAlarm qat-config postpone=\w+ slot=\d+'
    do {
        $text = Get-LogcatText
        if ($text -and $text.Length -ge $beforeLen) {
            $slice = $text.Substring($beforeLen)
            $hits = [regex]::Matches($slice, 'DoAlarm qat-config postpone=\w+ slot=\d+ kind=(?:AT_TIME|FROM_NOW) amount=-?\d+(?: unit=(?:MINUTE|HOUR|DAY|WEEK))? label=.+')
            if ($hits.Count -ge 12) {
                $rows = New-QaList
                foreach ($h in $hits) {
                    $parsed = Parse-QatConfigLine $h.Value
                    if ($parsed) { [void]$rows.Add($parsed) }
                }
                return $rows
            }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw "DUMP_QAT did not log 12 qat-config lines. Check $script:LogcatFile"
}

function Apply-Qat {
    [CmdletBinding()]
    param($Id, [int]$Slot, [int]$Count = 1, [string]$From = '', [string]$Day = '')
    $Id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    $cmd = "am broadcast -a $ApplyQatAction -n $Recv --el id $Id --ei slot $Slot"
    if ($Count -ne 1) { $cmd = "$cmd --ei count $Count" }
    if ($From) { $cmd = "$cmd --es from $From" }
    if ($Day) { $cmd = "$cmd --es day $Day" }
    $actionMs = Get-NowMs
    Invoke-AdbShell $cmd -AllowFail | Out-Null
    if ($DryRun) { return @{ NewDue = 0; Slot = $Slot; ActionMs = $actionMs } }
    $deadline = (Get-Date).AddSeconds(20)
    $pattern = "DoAlarm qat id=$Id newDue=(\d+) slot=(\d+)"
    do {
        $text = Get-LogcatText
        if ($text -and $text.Length -ge $beforeLen) {
            $slice = $text.Substring($beforeLen)
            $m = [regex]::Match($slice, $pattern)
            if ($m.Success) {
                $info = @{
                    NewDue   = [int64]$m.Groups[1].Value
                    Slot     = [int]$m.Groups[2].Value
                    ActionMs = $actionMs
                    Id       = $Id
                }
                Write-Log ("APPLY_QAT id={0} newDue={1} slot={2} count={3}" -f $Id, $info.NewDue, $info.Slot, $Count)
                return $info
            }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw "APPLY_QAT did not log newDue for id=$Id slot=$Slot. Check $script:LogcatFile"
}

function Test-FactoryDump {
    [CmdletBinding()]
    param($Rows)
    $list = @(ConvertTo-QaList $Rows)
    if ($list.Count -lt 12) { return @{ Ok = $false; Detail = "dump rows=$($list.Count)" } }
    $postpone = [string]$list[0].Postpone
    $missing = New-QaList
    for ($i = 0; $i -lt 12; $i++) {
        $row = $list | Where-Object { [int]$_.Slot -eq $i } | Select-Object -Last 1
        $fac = $script:FactorySlots[$i]
        if (-not $row) { [void]$missing.Add("slot $i missing"); continue }
        $labelOk = ($row.Label -eq $fac.Label12) -or ($row.Label -eq $fac.Label24)
        if ($row.Kind -ne $fac.Kind -or [int]$row.Amount -ne $fac.Amount) {
            [void]$missing.Add("slot $i kind/amount $($row.Kind) $($row.Amount)")
        } elseif ($fac.Kind -eq 'FROM_NOW' -and $row.Unit -and $row.Unit -ne $fac.Unit) {
            [void]$missing.Add("slot $i unit $($row.Unit)")
        } elseif (-not $labelOk) {
            [void]$missing.Add("slot $i label '$($row.Label)'")
        }
    }
    $ok = ($postpone -eq 'AUTO') -and ($missing.Count -eq 0)
    return @{ Ok = $ok; Postpone = $postpone; Missing = $missing; Detail = "postpone=$postpone missing=$($missing -join '; ')" }
}

function Restore-QatDefaults {
    if (-not $script:QatTouched) { return }
    Write-Log 'Restoring factory Quick Times slots and Auto postpone'
    try { Config-Postpone -Value 'AUTO' } catch { Write-Log ("restore postpone failed: " + $_.Exception.Message) }
    for ($i = 0; $i -lt 12; $i++) {
        $fac = $script:FactorySlots[$i]
        try { Config-Qat -Slot $i -Kind $fac.Kind -Amount $fac.Amount -Unit $fac.Unit } catch {
            Write-Log ("restore slot $i failed: " + $_.Exception.Message)
        }
    }
    $script:QatTouched = $false
}
# --- Spec 4 helpers ---------------------------------------------------------
function Add-CalendarMs {
    [CmdletBinding()]
    param($BaseMs, [int]$Days = 0, [int]$Months = 0, [int]$Years = 0)
    $dt = ConvertFrom-UnixMs $BaseMs
    if ($Years -ne 0) { $dt = $dt.AddYears($Years) }
    if ($Months -ne 0) { $dt = $dt.AddMonths($Months) }
    if ($Days -ne 0) { $dt = $dt.AddDays($Days) }
    return (ConvertTo-UnixMs $dt)
}

function Get-DateWallMs {
    [CmdletBinding()]
    param([int]$Year, [int]$Month, [int]$Day, [int]$Hour, [int]$Minute)
    $dt = Get-Date -Year $Year -Month $Month -Day $Day -Hour $Hour -Minute $Minute -Second 0
    return (ConvertTo-UnixMs $dt)
}

function Register-TestReminder {
    [CmdletBinding()]
    param($Id)
    if ($null -eq $script:CreatedIds) {
        $script:CreatedIds = New-Object 'System.Collections.Generic.List[int64]'
    }
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    if ($id -gt 0 -and -not $script:CreatedIds.Contains($id)) {
        [void]$script:CreatedIds.Add($id)
    }
}

function Finish-TestReminders {
    if ($DryRun) { return }
    if ($script:CreatedIds) {
        foreach ($id in @($script:CreatedIds)) {
            try { Done-Reminder $id } catch { Write-Log ("cleanup Done id=$id failed: " + $_.Exception.Message) }
        }
    }
    try { Config-RepeatPrompt -Value 'PROMPT' } catch { Write-Log ("restore repeat prompt failed: " + $_.Exception.Message) }
}

function Wait-ForLogSlice {
    [CmdletBinding()]
    param([int]$BeforeLen, [string]$Pattern, [int]$WaitSeconds = 15)
    if ($DryRun) { return '' }
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $text = Get-LogcatText
        if ($text -and $text.Length -ge $BeforeLen) {
            $slice = $text.Substring($BeforeLen)
            if ([regex]::IsMatch($slice, $Pattern)) { return $slice }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    $text = Get-LogcatText
    if ($text -and $text.Length -ge $BeforeLen) { return $text.Substring($BeforeLen) }
    return ''
}

function Set-Repeat {
    [CmdletBinding()]
    param(
        $Id,
        [int]$Interval = 1,
        [string]$Unit = 'OFF',
        [string]$From = 'DUE_DATE',
        [string]$WeekDays = '',
        [string]$MonthOn = ''
    )
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    $cmd = "am broadcast -a $SetRepeatAction -n $Recv --el id $id --ei interval $Interval --es unit $Unit --es from $From"
    if ($WeekDays) { $cmd = "$cmd --es weekDays $WeekDays" }
    if ($MonthOn) { $cmd = "$cmd --es monthOn $MonthOn" }
    Invoke-AdbShell $cmd -AllowFail | Out-Null
    if ($DryRun) {
        return @{ Id = $id; Rule = $Unit; From = $From; DueAt = 0; PatternDue = $null; Next = $null }
    }
    $slice = Wait-ForLogSlice -BeforeLen $beforeLen -Pattern "DoAlarm repeat id=$id " -WaitSeconds 15
    $parsed = Parse-RepeatLine -Text $slice -Id $id
    if ($null -eq $parsed) { throw "SET_REPEAT did not log a repeat line for id=$id" }
    Write-Log ("SET_REPEAT id={0} rule={1} from={2} dueAt={3} patternDue={4}" -f $parsed.Id, $parsed.Rule, $parsed.From, $parsed.DueAt, $parsed.PatternDue)
    return $parsed
}

function Parse-RepeatLine {
    [CmdletBinding()]
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $Text = ConvertTo-ScalarString $Text
    if (-not $Text) { return $null }
    $pattern = "DoAlarm repeat id=$id rule=(.+?) from=(\S+) dueAt=(\d+) patternDue=(null|\d+)(?: next=(\S+))?"
    $m = [regex]::Match($Text, $pattern)
    if (-not $m.Success) { return $null }
    $pat = $m.Groups[4].Value
    $patternDue = $null
    if ($pat -ne 'null') { $patternDue = [int64]$pat }
    $next = $null
    if ($m.Groups[5].Success -and $m.Groups[5].Value) { $next = $m.Groups[5].Value }
    return @{
        Id         = $id
        Rule       = $m.Groups[1].Value
        From       = $m.Groups[2].Value
        DueAt      = [int64]$m.Groups[3].Value
        PatternDue = $patternDue
        Next       = $next
    }
}

function Parse-RepeatNextLines {
    [CmdletBinding()]
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $list = New-QaList
    $Text = ConvertTo-ScalarString $Text
    if ($Text) {
        foreach ($m in [regex]::Matches($Text, "DoAlarm repeat-next id=$id i=(\d+) at=(\d+)")) {
            [void]$list.Add(@{ Index = [int]$m.Groups[1].Value; At = [int64]$m.Groups[2].Value })
        }
    }
    return $list
}

function Dump-Repeat {
    [CmdletBinding()]
    param($Id, [int]$Count = 6)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    $cmd = "am broadcast -a $DumpRepeatAction -n $Recv --el id $id --ei count $Count"
    Invoke-AdbShell $cmd -AllowFail | Out-Null
    if ($DryRun) {
        return @{ Id = $id; Rule = ''; From = ''; DueAt = 0; PatternDue = $null; Next = $null; Sequence = (New-QaList) }
    }
    $slice = Wait-ForLogSlice -BeforeLen $beforeLen -Pattern "DoAlarm repeat id=$id " -WaitSeconds 15
    # Give repeat-next lines a moment to flush after the header.
    Start-Sleep -Milliseconds 600
    $slice = Wait-ForLogSlice -BeforeLen $beforeLen -Pattern "DoAlarm repeat id=$id " -WaitSeconds 2
    $header = Parse-RepeatLine -Text $slice -Id $id
    if ($null -eq $header) { throw "DUMP_REPEAT did not log a repeat line for id=$id" }
    $seq = Parse-RepeatNextLines -Text $slice -Id $id
    $header.Sequence = $seq
    Write-Log ("DUMP_REPEAT id={0} rule={1} from={2} dueAt={3} patternDue={4} next={5} seq={6}" -f $header.Id, $header.Rule, $header.From, $header.DueAt, $header.PatternDue, $header.Next, $seq.Count)
    return $header
}

function Shift-Due {
    [CmdletBinding()]
    param(
        $Id,
        [string]$Mode,
        [int64]$DueAt = 0,
        [int]$InSeconds = [int]::MinValue
    )
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    $cmd = "am broadcast -a $ShiftDueAction -n $Recv --el id $id --es mode $Mode"
    if ($DueAt -ne 0) {
        $cmd = "$cmd --el dueAt $DueAt"
    } elseif ($InSeconds -ne [int]::MinValue) {
        $cmd = "$cmd --ei inSeconds $InSeconds"
    } else {
        throw "Shift-Due requires -DueAt or -InSeconds"
    }
    Invoke-AdbShell $cmd -AllowFail | Out-Null
    if ($DryRun) {
        return @{ Id = $id; NewDue = $DueAt; Mode = $Mode; PatternDue = $null }
    }
    $slice = Wait-ForLogSlice -BeforeLen $beforeLen -Pattern "DoAlarm shift-due id=$id " -WaitSeconds 15
    $m = [regex]::Match((ConvertTo-ScalarString $slice), "DoAlarm shift-due id=$id newDue=(\d+) mode=(\S+) patternDue=(null|\d+)")
    if (-not $m.Success) { throw "SHIFT_DUE did not log for id=$id mode=$Mode" }
    $pat = $m.Groups[3].Value
    $patternDue = $null
    if ($pat -ne 'null') { $patternDue = [int64]$pat }
    $info = @{
        Id         = $id
        NewDue     = [int64]$m.Groups[1].Value
        Mode       = $m.Groups[2].Value
        PatternDue = $patternDue
    }
    Write-Log ("SHIFT_DUE id={0} newDue={1} mode={2} patternDue={3}" -f $info.Id, $info.NewDue, $info.Mode, $info.PatternDue)
    return $info
}

function Config-RepeatPrompt {
    [CmdletBinding()]
    param([string]$Value)
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Invoke-AdbShell "am broadcast -a $ConfigRepeatPromptAction -n $Recv --es value $Value" -AllowFail | Out-Null
    if ($DryRun) { return }
    $slice = Wait-ForLogSlice -BeforeLen $beforeLen -Pattern 'DoAlarm config-repeat-prompt value=' -WaitSeconds 10
    if (-not (Test-TextContains $slice "DoAlarm config-repeat-prompt value=$Value")) {
        Write-Log "WARN: CONFIG_REPEAT_PROMPT $Value did not log a confirming line"
    }
}

function Get-RepeatAdvance {
    [CmdletBinding()]
    param($Id, [int]$BeforeLen = 0, [int]$WaitSeconds = 15)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    if ($DryRun) { return @{ Id = $id; NewDue = 0; PatternDue = $null; From = '' } }
    $slice = Wait-ForLogSlice -BeforeLen $BeforeLen -Pattern "DoAlarm repeat-advance id=$id " -WaitSeconds $WaitSeconds
    $m = [regex]::Match((ConvertTo-ScalarString $slice), "DoAlarm repeat-advance id=$id newDue=(\d+) patternDue=(null|\d+) from=(\S+)")
    if (-not $m.Success) { return $null }
    $pat = $m.Groups[2].Value
    $patternDue = $null
    if ($pat -ne 'null') { $patternDue = [int64]$pat }
    return @{
        Id         = $id
        NewDue     = [int64]$m.Groups[1].Value
        PatternDue = $patternDue
        From       = $m.Groups[3].Value
    }
}

function Test-SequenceEquals {
    [CmdletBinding()]
    param($Sequence, [int64[]]$Expected, [string]$Label)
    $rows = ConvertTo-QaList $Sequence
    if ($rows.Count -ne $Expected.Length) {
        Write-Log ("$Label count $($rows.Count) != $($Expected.Length)")
        return $false
    }
    for ($i = 0; $i -lt $Expected.Length; $i++) {
        $at = ConvertTo-Int64Scalar $rows[$i].At -Name 'At'
        $exp = [int64]$Expected[$i]
        if ($at -ne $exp) {
            Write-Log ("$Label i=$i at=$at expected=$exp")
            return $false
        }
    }
    return $true
}

function New-Spec4Reminder {
    [CmdletBinding()]
    param([string]$Title, [int]$InSeconds, [int]$NagMinutes = 0)
    $add = Add-Reminder -Title $Title -InSeconds $InSeconds -NagMinutes $NagMinutes
    Register-TestReminder $add.Id
    return $add
}

function Assert-MsClose {
    [CmdletBinding()]
    param($Actual, $Expected, [int]$TolMs = 2000, [string]$Label = 'value')
    $a = ConvertTo-Int64Scalar $Actual -Name 'Actual'
    $e = ConvertTo-Int64Scalar $Expected -Name 'Expected'
    $delta = [Math]::Abs($a - $e)
    if ($delta -gt $TolMs) {
        Write-Log ("$Label actual=$a expected=$e delta=${delta}ms (limit $TolMs)")
        return $false
    }
    return $true
}

# --- tests ------------------------------------------------------------------
function Run-AT02 {
    $tid = 'Do-AT-02'
    if (-not (Should-Run $tid)) { return }
    $add = New-Spec4Reminder -Title 'T_s4_02_daily' -InSeconds 120 -NagMinutes 0
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    Set-Repeat -Id $add.Id -Interval 1 -Unit 'DAY' -From 'DUE_DATE' | Out-Null
    $dump = Dump-Repeat -Id $add.Id -Count 4
    $due = [int64]$add.DueAt
    $exp = @($due, (Add-CalendarMs $due -Days 1), (Add-CalendarMs $due -Days 2), (Add-CalendarMs $due -Days 3))
    $dailyOk = (Test-SequenceEquals $dump.Sequence $exp 'daily') -and ($dump.From -eq 'DUE_DATE') -and (Test-TextContains $dump.Rule '1 DAY')

    $presetOk = $true
    $notes = New-Object System.Collections.Generic.List[string]
    [void]$notes.Add("daily seq ok=$dailyOk")

    $wd = New-Spec4Reminder -Title 'T_s4_02_weekdays' -InSeconds 180 -NagMinutes 0
    Set-Repeat -Id $wd.Id -Interval 1 -Unit 'WEEK' -From 'DUE_DATE' -WeekDays 'MON,TUE,WED,THU,FRI' | Out-Null
    $wdDump = Dump-Repeat -Id $wd.Id -Count 4
    $wdDue = ConvertFrom-UnixMs $wd.DueAt
    $wdTimes = New-Object System.Collections.Generic.List[int64]
    [void]$wdTimes.Add([int64]$wd.DueAt)
    $cursor = $wdDue
    while ($wdTimes.Count -lt 4) {
        $cursor = $cursor.AddDays(1)
        if ($cursor.DayOfWeek -ne [DayOfWeek]::Saturday -and $cursor.DayOfWeek -ne [DayOfWeek]::Sunday) {
            [void]$wdTimes.Add((ConvertTo-UnixMs $cursor))
        }
    }
    $wdOk = Test-SequenceEquals $wdDump.Sequence @($wdTimes.ToArray()) 'weekdays'
    if (-not $wdOk) { $presetOk = $false }
    [void]$notes.Add("weekdays ok=$wdOk")

    $wk = New-Spec4Reminder -Title 'T_s4_02_weekly' -InSeconds 180 -NagMinutes 0
    $wkDay = (ConvertFrom-UnixMs $wk.DueAt).DayOfWeek
    $wkToken = @{ Monday='MON'; Tuesday='TUE'; Wednesday='WED'; Thursday='THU'; Friday='FRI'; Saturday='SAT'; Sunday='SUN' }[$wkDay.ToString()]
    Set-Repeat -Id $wk.Id -Interval 1 -Unit 'WEEK' -From 'DUE_DATE' -WeekDays $wkToken | Out-Null
    $wkDump = Dump-Repeat -Id $wk.Id -Count 3
    $wkExp = @([int64]$wk.DueAt, (Add-CalendarMs $wk.DueAt -Days 7), (Add-CalendarMs $wk.DueAt -Days 14))
    $wkOk = Test-SequenceEquals $wkDump.Sequence $wkExp 'weekly'
    if (-not $wkOk) { $presetOk = $false }
    [void]$notes.Add("weekly ok=$wkOk")

    $mo = New-Spec4Reminder -Title 'T_s4_02_monthly' -InSeconds 180 -NagMinutes 0
    $moDay = (ConvertFrom-UnixMs $mo.DueAt).Day
    Set-Repeat -Id $mo.Id -Interval 1 -Unit 'MONTH' -From 'DUE_DATE' -MonthOn ("DOM:{0}" -f $moDay) | Out-Null
    $moDump = Dump-Repeat -Id $mo.Id -Count 3
    $moExp = @([int64]$mo.DueAt, (Add-CalendarMs $mo.DueAt -Months 1), (Add-CalendarMs $mo.DueAt -Months 2))
    $moOk = Test-SequenceEquals $moDump.Sequence $moExp 'monthly'
    if (-not $moOk) { $presetOk = $false }
    [void]$notes.Add("monthly ok=$moOk")

    $yr = New-Spec4Reminder -Title 'T_s4_02_yearly' -InSeconds 180 -NagMinutes 0
    Set-Repeat -Id $yr.Id -Interval 1 -Unit 'YEAR' -From 'DUE_DATE' | Out-Null
    $yrDump = Dump-Repeat -Id $yr.Id -Count 3
    $yrExp = @([int64]$yr.DueAt, (Add-CalendarMs $yr.DueAt -Years 1), (Add-CalendarMs $yr.DueAt -Years 2))
    $yrOk = Test-SequenceEquals $yrDump.Sequence $yrExp 'yearly'
    if (-not $yrOk) { $presetOk = $false }
    [void]$notes.Add("yearly ok=$yrOk")

    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Done-Reminder $add.Id
    $adv = Get-RepeatAdvance -Id $add.Id -BeforeLen $beforeLen -WaitSeconds 15
    $nextDay = Add-CalendarMs $due -Days 1
    $advOk = $false
    if ($adv) { $advOk = ($adv.NewDue -eq $nextDay) -and ($adv.From -eq 'DUE_DATE') }
    $alarmDump = Save-AlarmDump "AT-02-after-done"
    $clock = Test-AlarmClockPresent -Dump $alarmDump -Id $add.Id
    $armed = [bool]$clock.Present
    $detail = "id=$($add.Id) dailyOk=$dailyOk presets=$presetOk advance=$advOk newDue=$($adv.NewDue) expected=$nextDay armed=$armed. $($notes -join '; ')"
    if ($dailyOk -and $presetOk -and $advOk -and $armed) {
        Record-Result $tid 'PASS' $detail
    } else {
        Record-Result $tid 'FAIL' $detail
    }
}

function Run-AT03 {
    $tid = 'Do-AT-03'
    if (-not (Should-Run $tid)) { return }
    $add = New-Spec4Reminder -Title 'T_s4_03' -InSeconds 180 -NagMinutes 0
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    # Next Wednesday 19:00 local, or today if today is Wednesday and 19:00 is still usable as a pattern anchor.
    $now = Get-Date
    $wed = Get-Date -Hour 19 -Minute 0 -Second 0
    while ($wed.DayOfWeek -ne [DayOfWeek]::Wednesday) { $wed = $wed.AddDays(1) }
    if ($wed -lt $now.AddDays(-1)) { $wed = $wed.AddDays(7) }
    $wedMs = ConvertTo-UnixMs $wed
    Shift-Due -Id $add.Id -Mode 'THIS' -DueAt $wedMs | Out-Null
    Set-Repeat -Id $add.Id -Interval 2 -Unit 'WEEK' -From 'DUE_DATE' -WeekDays 'WED' | Out-Null
    $dump = Dump-Repeat -Id $add.Id -Count 3
    $exp0 = [int64]$wedMs
    $exp1 = Add-CalendarMs $exp0 -Days 14
    $exp2 = Add-CalendarMs $exp0 -Days 28
    $seqOk = Test-SequenceEquals $dump.Sequence @($exp0, $exp1, $exp2) 'every-2-weeks'
    $thuMs = Add-CalendarMs $exp0 -Days 1
    Shift-Due -Id $add.Id -Mode 'THIS' -DueAt $thuMs | Out-Null
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Done-Reminder $add.Id
    $adv = Get-RepeatAdvance -Id $add.Id -BeforeLen $beforeLen -WaitSeconds 15
    $advOk = $false
    if ($adv) { $advOk = ($adv.NewDue -eq $exp1) }
    $detail = "id=$($add.Id) wed=$exp0 thu=$thuMs seqOk=$seqOk advance=$advOk newDue=$($adv.NewDue) expected=$exp1"
    if ($seqOk -and $advOk) { Record-Result $tid 'PASS' $detail } else { Record-Result $tid 'FAIL' $detail }
}

function Run-AT04 {
    $tid = 'Do-AT-04'
    if (-not (Should-Run $tid)) { return }
    $add = New-Spec4Reminder -Title 'T_s4_04' -InSeconds 180 -NagMinutes 0
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    $now = Get-Date
    $wed = Get-Date -Hour $now.Hour -Minute $now.Minute -Second 0
    while ($wed.DayOfWeek -ne [DayOfWeek]::Wednesday) { $wed = $wed.AddDays(1) }
    # Keep the reminder's own local time of day, on a Wednesday.
    $dueLocal = ConvertFrom-UnixMs $add.DueAt
    $wed = Get-Date -Year $wed.Year -Month $wed.Month -Day $wed.Day -Hour $dueLocal.Hour -Minute $dueLocal.Minute -Second $dueLocal.Second
    $wedMs = ConvertTo-UnixMs $wed
    Shift-Due -Id $add.Id -Mode 'THIS' -DueAt $wedMs | Out-Null
    Set-Repeat -Id $add.Id -Interval 1 -Unit 'WEEK' -From 'DUE_DATE' -WeekDays 'MON,WED,FRI' | Out-Null
    $dump = Dump-Repeat -Id $add.Id -Count 4
    $fri = Add-CalendarMs $wedMs -Days 2
    $mon = Add-CalendarMs $wedMs -Days 5
    $nextWed = Add-CalendarMs $wedMs -Days 7
    $ok = Test-SequenceEquals $dump.Sequence @($wedMs, $fri, $mon, $nextWed) 'mwf'
    $detail = "id=$($add.Id) wed=$wedMs fri=$fri mon=$mon nextWed=$nextWed ok=$ok"
    if ($ok) { Record-Result $tid 'PASS' $detail } else { Record-Result $tid 'FAIL' $detail }
}

function Invoke-MonthDumpCheck {
    [CmdletBinding()]
    param(
        [string]$Title,
        [int64]$StartMs,
        [int]$Interval,
        [string]$MonthOn,
        [int64[]]$Expected,
        [int]$Count = 0
    )
    $add = New-Spec4Reminder -Title $Title -InSeconds 180 -NagMinutes 0
    Shift-Due -Id $add.Id -Mode 'THIS' -DueAt $StartMs | Out-Null
    Set-Repeat -Id $add.Id -Interval $Interval -Unit 'MONTH' -From 'DUE_DATE' -MonthOn $MonthOn | Out-Null
    if ($Count -le 0) { $Count = $Expected.Length }
    $dump = Dump-Repeat -Id $add.Id -Count $Count
    $ok = Test-SequenceEquals $dump.Sequence $Expected $Title
    return @{ Ok = $ok; Id = $add.Id; Dump = $dump }
}

function Run-AT05 {
    $tid = 'Do-AT-05'
    if (-not (Should-Run $tid)) { return }
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    $bits = New-Object System.Collections.Generic.List[string]
    $allOk = $true

    $m20 = Get-DateWallMs 2026 3 20 9 0
    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_3rdfri' -StartMs $m20 -Interval 1 -MonthOn 'NTH:3,FRIDAY' -Expected @($m20, (Get-DateWallMs 2026 4 17 9 0), (Get-DateWallMs 2026 5 15 9 0))
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("3rdFri=$($r.Ok)")

    $m1 = Get-DateWallMs 2026 3 1 9 0
    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_lastmon' -StartMs $m1 -Interval 1 -MonthOn 'NTH:LAST,MONDAY' -Expected @($m1, (Get-DateWallMs 2026 4 27 9 0)) -Count 2
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("lastMon=$($r.Ok)")

    $oct15 = Get-DateWallMs 2026 10 15 9 0
    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_lastmon2' -StartMs $oct15 -Interval 1 -MonthOn 'NTH:LAST,MONDAY' -Expected @($oct15, (Get-DateWallMs 2026 11 30 9 0)) -Count 2
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("lastMonNov=$($r.Ok)")

    $jan15 = Get-DateWallMs 2026 1 15 9 0
    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_lastwd' -StartMs $jan15 -Interval 1 -MonthOn 'NTH:LAST,WEEKDAY' -Expected @($jan15, (Get-DateWallMs 2026 2 27 9 0)) -Count 2
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("lastWeekdayFeb=$($r.Ok)")

    $dec15 = Get-DateWallMs 2026 12 15 9 0
    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_lastwd2' -StartMs $dec15 -Interval 1 -MonthOn 'NTH:LAST,WEEKDAY' -Expected @($dec15, (Get-DateWallMs 2027 1 29 9 0)) -Count 2
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("lastWeekdayJan=$($r.Ok)")

    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_firstday' -StartMs $jan15 -Interval 1 -MonthOn 'NTH:1,DAY' -Expected @($jan15, (Get-DateWallMs 2026 2 1 9 0)) -Count 2
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("firstDay=$($r.Ok)")

    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_lastday' -StartMs $jan15 -Interval 1 -MonthOn 'NTH:LAST,DAY' -Expected @($jan15, (Get-DateWallMs 2026 2 28 9 0)) -Count 2
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("lastDay=$($r.Ok)")

    $jan31 = Get-DateWallMs 2026 1 31 17 30
    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_dom31' -StartMs $jan31 -Interval 1 -MonthOn 'DOM:31' -Expected @($jan31, (Get-DateWallMs 2026 2 28 17 30), (Get-DateWallMs 2026 3 31 17 30))
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("dom31=$($r.Ok)")

    $oct25 = Get-DateWallMs 2026 10 25 17 30
    $r = Invoke-MonthDumpCheck -Title 'T_s4_05_every3' -StartMs $oct25 -Interval 3 -MonthOn 'DOM:25' -Expected @($oct25, (Get-DateWallMs 2027 1 25 17 30), (Get-DateWallMs 2027 4 25 17 30))
    if (-not $r.Ok) { $allOk = $false }
    [void]$bits.Add("every3=$($r.Ok)")

    $detail = ($bits -join '; ')
    if ($allOk) { Record-Result $tid 'PASS' $detail } else { Record-Result $tid 'FAIL' $detail }
}

function Run-AT06 {
    $tid = 'Do-AT-06'
    if (-not (Should-Run $tid)) { return }
    $add = New-Spec4Reminder -Title 'T_s4_06a' -InSeconds 120 -NagMinutes 0
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    $today930 = Get-Date -Hour 9 -Minute 30 -Second 0
    $dueA = ConvertTo-UnixMs $today930
    Shift-Due -Id $add.Id -Mode 'THIS' -DueAt $dueA | Out-Null
    Set-Repeat -Id $add.Id -Interval 3 -Unit 'MONTH' -From 'COMPLETION' | Out-Null
    $dump = Dump-Repeat -Id $add.Id -Count 6
    $noNext = ($dump.Next -eq 'completion') -and ($dump.Sequence.Count -eq 0) -and ($dump.From -eq 'COMPLETION')
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    $doneAt = Get-Date
    Done-Reminder $add.Id
    $adv = Get-RepeatAdvance -Id $add.Id -BeforeLen $beforeLen -WaitSeconds 15
    $expA = ConvertTo-UnixMs ($doneAt.Date.AddMonths(3).AddHours(9).AddMinutes(30))
    $okA = $false
    if ($adv) { $okA = ($adv.NewDue -eq $expA) -and ($adv.From -eq 'COMPLETION') }

    $add2 = New-Spec4Reminder -Title 'T_s4_06b' -InSeconds 120 -NagMinutes 0
    $today1600 = Get-Date -Hour 16 -Minute 0 -Second 0
    Shift-Due -Id $add2.Id -Mode 'THIS' -DueAt (ConvertTo-UnixMs $today1600) | Out-Null
    Set-Repeat -Id $add2.Id -Interval 3 -Unit 'MONTH' -From 'COMPLETION' | Out-Null
    $before2 = Get-LogcatText
    $beforeLen2 = 0
    if ($before2) { $beforeLen2 = $before2.Length }
    $doneAt2 = Get-Date
    Done-Reminder $add2.Id
    $adv2 = Get-RepeatAdvance -Id $add2.Id -BeforeLen $beforeLen2 -WaitSeconds 15
    $expB = ConvertTo-UnixMs ($doneAt2.Date.AddMonths(3).AddHours(16))
    $okB = $false
    if ($adv2) { $okB = ($adv2.NewDue -eq $expB) -and ($adv2.From -eq 'COMPLETION') }

    $detail = "noNext=$noNext a id=$($add.Id) newDue=$($adv.NewDue) expected=$expA ok=$okA; b id=$($add2.Id) newDue=$($adv2.NewDue) expected=$expB ok=$okB"
    if ($noNext -and $okA -and $okB) { Record-Result $tid 'PASS' $detail } else { Record-Result $tid 'FAIL' $detail }
}

function Run-AT07 {
    $tid = 'Do-AT-07'
    if (-not (Should-Run $tid)) { return }
    $add = New-Spec4Reminder -Title 'T_s4_07' -InSeconds 120 -NagMinutes 1
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    Set-Repeat -Id $add.Id -Interval 1 -Unit 'DAY' -From 'DUE_DATE' | Out-Null
    $due = [int64]$add.DueAt
    $fires = Wait-ForFireCount -Id $add.Id -Count 1 -UntilEpochMs ($due + $PhoneTolMs + 5000)
    $fire1 = $false
    if ($fires.Count -ge 1) { $fire1 = Test-TimingOk $fires[0].Scheduled $fires[0].Actual }
    $nagAt = $due + 60000
    $fires2 = Wait-ForFireCount -Id $add.Id -Count 2 -UntilEpochMs ($nagAt + $PhoneTolMs + 5000)
    $nagOk = $false
    if ($fires2.Count -ge 2) { $nagOk = Test-TimingOk $fires2[1].Scheduled $fires2[1].Actual }
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Done-Reminder $add.Id
    $adv = Get-RepeatAdvance -Id $add.Id -BeforeLen $beforeLen -WaitSeconds 15
    $nextDue = Add-CalendarMs $due -Days 1
    $advOk = $false
    if ($adv) { $advOk = ($adv.NewDue -eq $nextDue) }
    Start-Sleep -Seconds 5
    $notif = Save-NotifDump 'AT-07-after-done'
    $notifGone = -not (Test-TextContains $notif ("Reminder id=$($add.Id)"))
    # Wait the remainder of 2 minutes after Done and confirm no further fire on the old anchor.
    Write-Log 'AT-07 waiting 120s to confirm no nags on the old anchor'
    Start-Sleep -Seconds 120
    $after = Get-FlatFires (Get-FiresForId $add.Id)
    $extra = 0
    foreach ($f in $after) {
        if ([int64]$f.Scheduled -gt $nagAt -and [int64]$f.Scheduled -lt $nextDue) { $extra++ }
    }
    $alarmDump = Save-AlarmDump 'AT-07-next'
    $clock = Test-AlarmClockPresent -Dump $alarmDump -Id $add.Id
    $armed = [bool]$clock.Present
    $detail = "id=$($add.Id) fire1=$fire1 nagOk=$nagOk advOk=$advOk newDue=$($adv.NewDue) expected=$nextDue notifGone=$notifGone extraOldNags=$extra armed=$armed (next-day fire not waited; dumpsys stands in)"
    if ($fire1 -and $nagOk -and $advOk -and $armed -and ($extra -eq 0)) {
        Record-Result $tid 'PASS' $detail
    } else {
        Record-Result $tid 'FAIL' $detail
    }
}

function Run-AT08 {
    $tid = 'Do-AT-08'
    if (-not (Should-Run $tid)) { return }
    $add = New-Spec4Reminder -Title 'T_s4_08' -InSeconds 120 -NagMinutes 0
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    Set-Repeat -Id $add.Id -Interval 1 -Unit 'DAY' -From 'DUE_DATE' | Out-Null
    $due = [int64]$add.DueAt
    $fires = Wait-ForFireCount -Id $add.Id -Count 1 -UntilEpochMs ($due + $PhoneTolMs + 5000)
    $fireOk = $false
    if ($fires.Count -ge 1) { $fireOk = Test-TimingOk $fires[0].Scheduled $fires[0].Actual }
    $snoozeAt = Get-Date
    $snooze = Snooze-Reminder -Id $add.Id -Option 0
    $expSnooze = [int64](ConvertTo-UnixMs $snoozeAt) + (60 * 60 * 1000)
    $snoozeOk = $false
    if ($snooze) { $snoozeOk = Assert-MsClose $snooze.NewDue $expSnooze -TolMs $SnoozeTolMs -Label 'snooze' }
    $dump = Dump-Repeat -Id $add.Id -Count 2
    $ruleOk = (Test-TextContains $dump.Rule '1 DAY') -and ($dump.PatternDue -eq $due)
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Done-Reminder $add.Id
    $adv = Get-RepeatAdvance -Id $add.Id -BeforeLen $beforeLen -WaitSeconds 15
    $expNext = Add-CalendarMs $due -Days 1
    $advOk = $false
    if ($adv) { $advOk = ($adv.NewDue -eq $expNext) }
    $detail = "id=$($add.Id) fireOk=$fireOk snoozeOk=$snoozeOk snoozedTo=$($snooze.NewDue) ruleOk=$ruleOk advOk=$advOk newDue=$($adv.NewDue) expected=$expNext"
    if ($fireOk -and $snoozeOk -and $ruleOk -and $advOk) {
        Record-Result $tid 'PASS' $detail
    } else {
        Record-Result $tid 'FAIL' $detail
    }
}

function Run-AT09 {
    $tid = 'Do-AT-09'
    if (-not (Should-Run $tid)) { return }
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    $oct27 = Get-DateWallMs 2026 10 27 17 30
    $oct28 = Get-DateWallMs 2026 10 28 17 30
    $dec28 = Get-DateWallMs 2026 12 28 17 30
    $dec27 = Get-DateWallMs 2026 12 27 17 30

    $all = New-Spec4Reminder -Title 'T_s4_09_all' -InSeconds 180 -NagMinutes 0
    Shift-Due -Id $all.Id -Mode 'THIS' -DueAt $oct27 | Out-Null
    Set-Repeat -Id $all.Id -Interval 2 -Unit 'MONTH' -From 'DUE_DATE' -MonthOn 'DOM:27' | Out-Null
    Shift-Due -Id $all.Id -Mode 'ALL' -DueAt $oct28 | Out-Null
    $dumpAll = Dump-Repeat -Id $all.Id -Count 2
    $seqAll = Test-SequenceEquals $dumpAll.Sequence @($oct28, $dec28) 'all-future'
    $ruleAll = Test-TextContains $dumpAll.Rule 'DOM:28'
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Done-Reminder $all.Id
    $advAll = Get-RepeatAdvance -Id $all.Id -BeforeLen $beforeLen -WaitSeconds 15
    $advAllOk = $false
    if ($advAll) { $advAllOk = ($advAll.NewDue -eq $dec28) }

    $this = New-Spec4Reminder -Title 'T_s4_09_this' -InSeconds 180 -NagMinutes 0
    Shift-Due -Id $this.Id -Mode 'THIS' -DueAt $oct27 | Out-Null
    Set-Repeat -Id $this.Id -Interval 2 -Unit 'MONTH' -From 'DUE_DATE' -MonthOn 'DOM:27' | Out-Null
    Shift-Due -Id $this.Id -Mode 'THIS' -DueAt $oct28 | Out-Null
    $before2 = Get-LogcatText
    $beforeLen2 = 0
    if ($before2) { $beforeLen2 = $before2.Length }
    Done-Reminder $this.Id
    $advThis = Get-RepeatAdvance -Id $this.Id -BeforeLen $beforeLen2 -WaitSeconds 15
    $advThisOk = $false
    if ($advThis) { $advThisOk = ($advThis.NewDue -eq $dec27) }

    $detail = "all seq=$seqAll rule=$ruleAll adv=$advAllOk newDue=$($advAll.NewDue) exp=$dec28; this adv=$advThisOk newDue=$($advThis.NewDue) exp=$dec27"
    if ($seqAll -and $ruleAll -and $advAllOk -and $advThisOk) {
        Record-Result $tid 'PASS' $detail
    } else {
        Record-Result $tid 'FAIL' $detail
    }
}

function Run-AT11 {
    $tid = 'Do-AT-11'
    if (-not (Should-Run $tid)) { return }
    $add = New-Spec4Reminder -Title 'T_s4_11' -InSeconds 300 -NagMinutes 0
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    Set-Repeat -Id $add.Id -Interval 1 -Unit 'DAY' -From 'DUE_DATE' | Out-Null
    $due = [int64]$add.DueAt
    Set-Repeat -Id $add.Id -Unit 'OFF' | Out-Null
    $dump = Dump-Repeat -Id $add.Id -Count 2
    $offOk = ($dump.Rule -eq 'OFF') -and ($dump.DueAt -eq $due) -and ($null -eq $dump.PatternDue)
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Done-Reminder $add.Id
    $adv = Get-RepeatAdvance -Id $add.Id -BeforeLen $beforeLen -WaitSeconds 8
    $noAdvance = ($null -eq $adv)
    Start-Sleep -Seconds 2
    $alarmDump = Save-AlarmDump 'AT-11-after-done'
    $clock = Test-AlarmClockPresent -Dump $alarmDump -Id $add.Id
    $noAlarm = -not ([bool]$clock.Present)
    $detail = "id=$($add.Id) dueUnchanged=$offOk rule=$($dump.Rule) dueAt=$($dump.DueAt) expectedDue=$due noAdvance=$noAdvance noAlarm=$noAlarm"
    if ($offOk -and $noAdvance -and $noAlarm) {
        Record-Result $tid 'PASS' $detail
    } else {
        Record-Result $tid 'FAIL' $detail
    }
}

function Run-AT12-Clock {
    $tid = 'Do-AT-12-clock'
    if (-not (Should-Run $tid) -and -not (Should-Run 'Do-AT-12')) { return }
    # Missed occurrence via debug add already in the past (25 hours), no TIME_SET.
    $add = New-Spec4Reminder -Title 'T_s4_12' -InSeconds -90000 -NagMinutes 0
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    Set-Repeat -Id $add.Id -Interval 1 -Unit 'DAY' -From 'DUE_DATE' | Out-Null
    $due = [int64]$add.DueAt
    $fires = Wait-ForFireCount -Id $add.Id -Count 1 -UntilEpochMs ((ConvertTo-UnixMs (Get-Date)) + $PhoneTolMs + 5000)
    $missed = $fires.Count -ge 1
    $dump = Dump-Repeat -Id $add.Id -Count 2
    $i0ok = $false
    if ($dump.Sequence.Count -ge 1) {
        $i0 = ConvertTo-Int64Scalar $dump.Sequence[0].At -Name 'At'
        $i0ok = ($i0 -eq $due)
    }
    $before = Get-LogcatText
    $beforeLen = 0
    if ($before) { $beforeLen = $before.Length }
    Done-Reminder $add.Id
    $adv = Get-RepeatAdvance -Id $add.Id -BeforeLen $beforeLen -WaitSeconds 15
    $step = Add-CalendarMs $due -Days 1
    $advOk = $false
    $stillPast = $false
    if ($adv) {
        $advOk = ($adv.NewDue -eq $step)
        $stillPast = ($adv.NewDue -lt (ConvertTo-UnixMs (Get-Date)))
    }
    $fires2 = Wait-ForFireCount -Id $add.Id -Count 2 -UntilEpochMs ((ConvertTo-UnixMs (Get-Date)) + $PhoneTolMs + 5000)
    $second = $fires2.Count -ge 2
    $detail = "id=$($add.Id) missedNow=$missed dumpI0=$i0ok due=$due advOk=$advOk newDue=$($adv.NewDue) expected=$step stillPast=$stillPast immediateSecondFire=$second"
    if ($missed -and $i0ok -and $advOk -and $stillPast -and $second) {
        Record-Result $tid 'PASS' $detail
    } else {
        Record-Result $tid 'FAIL' $detail
    }
}

function Run-AT14 {
    $tid = 'Do-AT-14'
    if (-not (Should-Run $tid)) { return }
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    $h = New-Spec4Reminder -Title 'T_s4_14_hour' -InSeconds 120 -NagMinutes 0
    Set-Repeat -Id $h.Id -Interval 8 -Unit 'HOUR' -From 'DUE_DATE' | Out-Null
    $dumpH = Dump-Repeat -Id $h.Id -Count 4
    $h0 = [int64]$h.DueAt
    $hExp = @($h0, ($h0 + 8 * 3600000), ($h0 + 16 * 3600000), ($h0 + 24 * 3600000))
    $hSeq = Test-SequenceEquals $dumpH.Sequence $hExp 'hour'
    $beforeH = Get-LogcatText
    $beforeHLen = 0
    if ($beforeH) { $beforeHLen = $beforeH.Length }
    Done-Reminder $h.Id
    $advH = Get-RepeatAdvance -Id $h.Id -BeforeLen $beforeHLen -WaitSeconds 15
    $hAdv = $false
    if ($advH) { $hAdv = ($advH.NewDue -eq ($h0 + 8 * 3600000)) }
    $alarmH = Save-AlarmDump 'AT-14-hour'
    $clockH = Test-AlarmClockPresent -Dump $alarmH -Id $h.Id
    $hArmed = [bool]$clockH.Present

    $m = New-Spec4Reminder -Title 'T_s4_14_min' -InSeconds 120 -NagMinutes 0
    Set-Repeat -Id $m.Id -Interval 15 -Unit 'MINUTE' -From 'DUE_DATE' | Out-Null
    $dumpM = Dump-Repeat -Id $m.Id -Count 3
    $m0 = [int64]$m.DueAt
    $mExp = @($m0, ($m0 + 15 * 60000), ($m0 + 30 * 60000))
    $mSeq = Test-SequenceEquals $dumpM.Sequence $mExp 'minute'
    $beforeM = Get-LogcatText
    $beforeMLen = 0
    if ($beforeM) { $beforeMLen = $beforeM.Length }
    Done-Reminder $m.Id
    $advM = Get-RepeatAdvance -Id $m.Id -BeforeLen $beforeMLen -WaitSeconds 15
    $mAdv = $false
    if ($advM) { $mAdv = ($advM.NewDue -eq ($m0 + 15 * 60000)) }

    $c = New-Spec4Reminder -Title 'T_s4_14_comp' -InSeconds 120 -NagMinutes 0
    $today930 = ConvertTo-UnixMs (Get-Date -Hour 9 -Minute 30 -Second 0)
    Shift-Due -Id $c.Id -Mode 'THIS' -DueAt $today930 | Out-Null
    Set-Repeat -Id $c.Id -Interval 8 -Unit 'HOUR' -From 'COMPLETION' | Out-Null
    $beforeC = Get-LogcatText
    $beforeCLen = 0
    if ($beforeC) { $beforeCLen = $beforeC.Length }
    $doneAt = Get-Date
    Done-Reminder $c.Id
    $advC = Get-RepeatAdvance -Id $c.Id -BeforeLen $beforeCLen -WaitSeconds 15
    $expC = ConvertTo-UnixMs ($doneAt.Date.AddHours(9).AddMinutes(30).AddHours(8))
    $cOk = $false
    if ($advC) { $cOk = ($advC.NewDue -eq $expC) }

    $d = New-Spec4Reminder -Title 'T_s4_14_kill' -InSeconds 90 -NagMinutes 0
    Set-Repeat -Id $d.Id -Interval 1 -Unit 'DAY' -From 'DUE_DATE' | Out-Null
    $dueD = [int64]$d.DueAt
    $fires = Wait-ForFireCount -Id $d.Id -Count 1 -UntilEpochMs ($dueD + $PhoneTolMs + 5000)
    $fired = $fires.Count -ge 1
    $pidBefore = Get-PidOfPkg
    Invoke-AdbShell "am kill $Pkg" -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    $pidAfter = Get-PidOfPkg
    $killed = [string]::IsNullOrWhiteSpace($pidAfter)
    $beforeD = Get-LogcatText
    $beforeDLen = 0
    if ($beforeD) { $beforeDLen = $beforeD.Length }
    Done-Reminder $d.Id
    $advD = Get-RepeatAdvance -Id $d.Id -BeforeLen $beforeDLen -WaitSeconds 20
    $expD = Add-CalendarMs $dueD -Days 1
    $dAdv = $false
    if ($advD) { $dAdv = ($advD.NewDue -eq $expD) }
    $alarmD = Save-AlarmDump 'AT-14-kill'
    $clockD = Test-AlarmClockPresent -Dump $alarmD -Id $d.Id
    $dArmed = [bool]$clockD.Present

    $coreOk = $hSeq -and $hAdv -and $hArmed -and $mSeq -and $mAdv -and $cOk -and $fired -and $dAdv -and $dArmed
    $detail = "hour seq=$hSeq adv=$hAdv armed=$hArmed; min seq=$mSeq adv=$mAdv; completion=$cOk newDue=$($advC.NewDue) exp=$expC; fire=$fired pidBefore=$pidBefore pidAfterKill=$pidAfter killed=$killed adv=$dAdv armed=$dArmed (next 8h / 15min / +1 day fires not waited; dumpsys stands in)"
    if ($coreOk -and $killed) {
        Record-Result $tid 'PASS' $detail
    } elseif ($coreOk -and -not $killed) {
        Record-Result $tid 'RECORD' ($detail + ' am kill did not drop the pid; DONE still advanced.')
    } else {
        Record-Result $tid 'FAIL' $detail
    }
}


function Run-AT-WeekStart {
    $tid = 'Do-AT-weekstart'
    if (-not (Should-Run $tid)) { return }
    if ($DryRun) { Record-Result $tid 'SKIP' 'DryRun'; return }
    # Factory Week Starts On is Monday. There is no CONFIG_WEEK_START hook, so
    # this phone-auto row only proves the Monday boundary. Saturday and Sunday
    # are covered by RepeatMathTest.everyNWeeksFollowsEachWeekStart on the host.
    # Fixture (same as the unit test): Sunday 29 Nov 2026 19:00, every 2 weeks
    # on Monday. Monday start -> 7 Dec 19:00, not 30 Nov (Sat/Sun start).
    $add = New-Spec4Reminder -Title 'T_s4_ws' -InSeconds 180 -NagMinutes 0
    $sun = Get-DateWallMs 2026 11 29 19 0
    $dec7 = Get-DateWallMs 2026 12 7 19 0
    $nov30 = Get-DateWallMs 2026 11 30 19 0
    Shift-Due -Id $add.Id -Mode 'THIS' -DueAt $sun | Out-Null
    Set-Repeat -Id $add.Id -Interval 2 -Unit 'WEEK' -From 'DUE_DATE' -WeekDays 'MON' | Out-Null
    $dump = Dump-Repeat -Id $add.Id -Count 2
    $seqOk = Test-SequenceEquals $dump.Sequence @($sun, $dec7) 'weekstart-monday'
    $notSatSun = $true
    if ($dump.Sequence.Count -ge 2) {
        $at1 = ConvertTo-Int64Scalar $dump.Sequence[1].At -Name 'At'
        if ($at1 -eq $nov30) { $notSatSun = $false }
    }
    $detail = "id=$($add.Id) factory Week Starts On=Monday every-2-weeks On MON from Sun 2026-11-29 19:00; next expected 2026-12-07 19:00 (not 2026-11-30, which is the Sat/Sun-start landing); seqOk=$seqOk notSatSunLanding=$notSatSun"
    if ($seqOk -and $notSatSun) { Record-Result $tid 'PASS' $detail } else { Record-Result $tid 'FAIL' $detail }
}

function Run-AT16 {
    $tid = 'Do-AT-16'
    if (-not (Should-Run $tid)) { return }
    Record-Result $tid 'PASS' 'Host already verified at 8241170: 48 unit tests (13 RepeatMathTest including everyNWeeksFollowsEachWeekStart for Monday/Saturday/Sunday, Due 26.6.1 menu labels, Sydney DST and the 27th/28th fixture, 1 RepeatMigrationTest, 16 QuickTimesTest including +10 min. / +3 hrs labels), lintDebug 0 Errors, USE and SCHEDULE debug+unsigned release, release has no DebugReceiver or .debug.SET_REPEAT/DUMP_REPEAT/SHIFT_DUE/CONFIG_REPEAT_PROMPT, CI green (run 45595662234). See /workspace/do-app/qa/results-pr4-8241170-host.md.'
}

function Record-ManualRows {
    $rows = @(
        @{ Id = 'Do-AT-01'; Detail = 'Picker, labels and list, and Week Starts On in settings (Monday, Saturday, Sunday; factory Monday). Menu must match Due 26.6.1: Repeat heading, Repeat Off first, then for a Wednesday 25 Nov 5:30 pm due the presets Daily 5:30 pm, Weekdays 5:30 pm, Wednesdays 5:30 pm, 25th each month at 5:30 pm, Every 25 Nov at 5:30 pm, then Custom.... See spec-04-manual-checklist.md.' },
        @{ Id = 'Do-AT-10'; Detail = 'Editor this/all-future prompt, Always only this occurrence, warning icon, Restore warnings and prompts / CONFIG_REPEAT_PROMPT, and no prompt on from-completion or title-only saves. See spec-04-manual-checklist.md.' },
        @{ Id = 'Do-AT-12-reboot'; Detail = 'Reboot half of AT-12: daily due in 5 minutes, adb reboot, do not unlock, confirm lock-screen fire; after unlock the row is not done, the rule is intact, and dumpsys alarm shows the next nag or still-pending due. Do not add a PIN. If a PIN would be required, mark NOT RUN. Clock-jump half is scripted as Do-AT-12-clock.' },
        @{ Id = 'Do-AT-13'; Detail = 'Phone DST / timezone check is optional and must restore automatic time zone. Perth has no DST; skipping the phone check is allowed because unit tests in RepeatMathTest cover Sydney gap/overlap, elapsed hours, and AWST-to-Sydney/Tokyo wall-time rewrite. Default: NOT RUN on the phone. See spec-04-manual-checklist.md.' },
        @{ Id = 'Do-AT-15'; Detail = 'Direct boot Done on a repeating reminder. Do not add a PIN on Tim''s phone without his ok. If this cannot be run without creating a PIN, mark NOT RUN. See spec-04-manual-checklist.md.' }
    )
    foreach ($r in $rows) {
        if ($OnlySet.Count -gt 0 -and -not (Should-Run $r.Id) -and -not (Should-Run ($r.Id -replace '-reboot$',''))) { continue }
        Record-Result $r.Id 'MANUAL' $r.Detail
    }
}

function Write-ResultsFile {
    [CmdletBinding()]
    param([string]$Overall)
    $nl = [Environment]::NewLine
    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add('# Do Spec 4 phone results')
    [void]$lines.Add('')
    [void]$lines.Add(('Generated: {0} (Australia/Perth, AWST)' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')))
    [void]$lines.Add(('Commit: `{0}`' -f $ExpectedSha))
    [void]$lines.Add(('Device: Samsung Galaxy S24+ serial `{0}`' -f $Serial))
    [void]$lines.Add(('Evidence: `{0}`' -f $script:EvidenceDir))
    [void]$lines.Add(('Overall (automated rows): **{0}**' -f $Overall))
    [void]$lines.Add('')
    [void]$lines.Add('| ID | Status | Detail |')
    [void]$lines.Add('|---|---|---|')
    foreach ($r in $script:Results) {
        $detail = [string]$r.Detail
        $detail = $detail.Replace('|', '/')
        [void]$lines.Add(('| {0} | {1} | {2} |' -f $r.Id, $r.Status, $detail))
    }
    [void]$lines.Add('')
    [void]$lines.Add('MANUAL / RECORD rows do not fail the overall automated score. Use `/workspace/do-app/qa/spec-04-manual-checklist.md` (copy to `C:\Users\tdrev\do-qa`).')
    [void]$lines.Add('SET_REPEAT and SHIFT_DUE write through, unlike the editor. Leftover T_s4_* reminders are Marked Done in finally. Factory slots, Auto postpone, factory snooze, repeat prompt=PROMPT and automatic time zone are restored in finally. Far-future next fires are scored from DUMP_REPEAT / repeat-advance / dumpsys, not by waiting a day.')
    $text = [string]::Join($nl, $lines) + $nl
    $dir = Split-Path $ResultsPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    Set-Content -Path $ResultsPath -Value $text -Encoding UTF8
    if ($script:EvidenceDir) {
        Copy-Item $ResultsPath (Join-Path $script:EvidenceDir 'results-spec-04.md') -Force
    }
    Write-Log "Wrote $ResultsPath"
}

# --- main -------------------------------------------------------------------
$script:EvidenceRoot = 'C:\Users\tdrev\do-qa\qa-evidence'
$script:EvidenceDir = Join-Path $script:EvidenceRoot ("Do-spec04-" + $Stamp)
$script:SnoozeOptionsTouched = $false
$script:CreatedIds = New-Object 'System.Collections.Generic.List[int64]'

try {
    if ($DryRun) {
        Write-Log 'DryRun is on: no git checkout, no install, no phone changes.'
        $script:EvidenceDir = Join-Path $env:TEMP ("Do-spec04-dryrun-" + $Stamp)
    }
    if (-not (Test-Path $script:EvidenceDir)) {
        New-Item -ItemType Directory -Path $script:EvidenceDir -Force | Out-Null
    }
    Write-Log "Evidence folder: $script:EvidenceDir"

    if (-not (Test-Path $AdbPath)) {
        $found = Get-Command adb -ErrorAction SilentlyContinue
        if ($found) { $script:Adb = $found.Source } else {
            if (-not $DryRun) { throw "adb not found at $AdbPath and not on PATH." }
            Write-Log "DryRun: adb missing at $AdbPath (would fail a live run)"
        }
    }

    Ensure-Checkout

    if ($DryRun) {
        Write-Log 'DryRun command plan (phone will not be changed):'
        Write-Log "  git clone/fetch $RepoUrl $RepoDir ; git checkout --detach $ExpectedSha ; git rev-parse HEAD"
        Write-Log "  $RepoDir\gradlew.bat assembleDebug --no-daemon"
        Write-Log "  $AdbPath -s $Serial install -r $RepoDir\app\build\outputs\apk\debug\app-debug.apk"
        Write-Log "  $AdbPath -s $Serial shell pm grant $Pkg android.permission.POST_NOTIFICATIONS"
        Write-Log "  $AdbPath -s $Serial shell am start -n $Pkg/.MainActivity"
        Write-Log "  record auto_time, auto_time_zone, persist.sys.timezone, low_power, standby bucket, inactive"
        Write-Log "  phone-auto: AT-02 presets+Done, AT-03 every 2 weeks, AT-weekstart (factory Monday every-N-weeks boundary), AT-04 Mon/Wed/Fri, AT-05 monthly On, AT-06 from completion, AT-07 nag per occurrence, AT-08 snooze this occurrence, AT-09 all-future vs this, AT-11 Repeat Off, AT-12-clock missed/catch-up, AT-14 hour/minute + process death, AT-16 host PASS"
        Write-Log "  finally: Done leftover T_s4_*; CONFIG_REPEAT_PROMPT PROMPT; restore factory QAT, Auto postpone, factory snooze; deviceidle unforce; battery reset; auto_time 1; auto_time_zone 1; restore zone; low_power if it was off; set-inactive false; standby bucket reset"
        Write-Log "  never: locksettings, factory reset, pm clear of other packages, PIN, force-stop"
        Record-Result 'Do-runner' 'SKIP' 'DryRun only. No checkout, build, install or phone changes.'
        Record-ManualRows
        return
    }

    Invoke-BuildAndInstall
    Capture-Originals
    Start-DoLogcat
    $preAlarm = Save-AdbDump 'Do-pretest-dumpsys-alarm.txt' @('shell', 'dumpsys', 'alarm')
    if (Test-HasPendingDoAlarm -Dump $preAlarm) {
        Write-Log "WARN: leftover $Pkg alarms are already on the phone (often T_doze from a crashed earlier run). Mark those reminders Done in the app before a clean run; they pollute later alarm-clock checks."
    }
    Save-AdbDump 'Do-device-props.txt' @('shell', 'getprop')

    Run-AT16
    Run-AT02
    Run-AT03
    Run-AT-WeekStart
    Run-AT04
    Run-AT05
    Run-AT06
    Run-AT07
    Run-AT08
    Run-AT09
    Run-AT11
    Run-AT12-Clock
    Run-AT14
    Record-ManualRows
}
catch {
    Write-Log ("ERROR: " + $_.Exception.Message)
    Record-Result 'Do-runner' 'FAIL' $_.Exception.Message
    throw
}
finally {
    try { Finish-TestReminders } catch { Write-Log ("Test reminder cleanup failed: " + $_.Exception.Message) }
    try { Restore-QatDefaults } catch { Write-Log ("QAT default restore failed: " + $_.Exception.Message) }
    try { Restore-SnoozeDefaults } catch { Write-Log ("Snooze default restore failed: " + $_.Exception.Message) }
    try { Restore-Phone } catch { Write-Log ("Restore failed: " + $_.Exception.Message) }
    Stop-DoLogcat
    $autoFails = @($script:Results | Where-Object { $_.Status -eq 'FAIL' })
    $overall = 'PASS'
    if ($autoFails.Count -gt 0) { $overall = 'FAIL' }
    try { Write-ResultsFile -Overall $overall } catch { Write-Log ("Could not write results: " + $_.Exception.Message) }
    Write-Log "Done. Overall (automated rows)=$overall. Results: $ResultsPath"
}
