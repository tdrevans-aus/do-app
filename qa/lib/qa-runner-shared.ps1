#Requires -Version 5.1
# Shared collection and log-parse helpers for Do QA runners.
# Windows PowerShell 5.1 and pwsh 7 both report Generic.List[object] as
# -is [pscustomobject], and @($list) of such a list throws
# "Argument types do not match". Never detect a single row with
# -is [pscustomobject], and never wrap a Generic.List in @().
#
# Dot-source from run-spec-0*.ps1 (copy this file to C:\Users\tdrev\do-qa\lib\)
# and from qa/tests/test-runner-helpers.ps1.

function New-QaList {
    return New-Object System.Collections.Generic.List[object]
}

function Test-IsQaCollection {
    param($Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [string] -or $Value -is [ValueType]) { return $false }
    if ($Value -is [hashtable] -or $Value -is [System.Collections.IDictionary]) { return $false }
    return ($Value -is [System.Collections.IEnumerable])
}

function Test-IsQaRow {
    param($Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [hashtable] -or $Value -is [System.Collections.IDictionary]) { return $true }
    if (Test-IsQaCollection $Value) { return $false }
    if ($Value -is [string] -or $Value -is [ValueType]) { return $false }
    return $true
}

function Out-QaList {
    param($Value)
    $list = $Value
    if ($null -eq $list) { $list = New-QaList }
    elseif ($list -isnot [System.Collections.Generic.List[object]]) {
        $list = ConvertTo-QaList $list
    }
    # Unary comma: one pipeline object, even when the list has 0 or 1 rows.
    return ,$list
}

function ConvertTo-QaList {
    param($Value)
    $list = New-QaList
    function Walk($item) {
        if ($null -eq $item) { return }
        if (Test-IsQaCollection $item) {
            foreach ($x in $item) { Walk $x }
            return
        }
        [void]$list.Add($item)
    }
    Walk $Value
    Out-QaList $list; return
}

function ConvertTo-QaArray {
    param($Value)
    $list = ConvertTo-QaList $Value
    return ,$list.ToArray()
}

function Get-QaItem {
    param($Value, [int]$Index = 0)
    $rows = ConvertTo-QaList $Value
    if ($Index -lt 0 -or $Index -ge $rows.Count) { return $null }
    return $rows[$Index]
}

function Get-FlatFires {
    param($Raw)
    $list = New-QaList
    function Walk($item) {
        if ($null -eq $item) { return }
        if (Test-IsQaCollection $item) {
            foreach ($x in $item) { Walk $x }
            return
        }
        $isMap = ($item -is [hashtable] -or $item -is [System.Collections.IDictionary] -or (Test-IsQaRow $item))
        if ($isMap) {
            $has = $false
            if ($item -is [hashtable] -and $item.ContainsKey('Scheduled')) { $has = $true }
            elseif ($item.PSObject.Properties.Name -contains 'Scheduled') { $has = $true }
            if ($has) { [void]$list.Add($item) }
        }
    }
    Walk $Raw
    Out-QaList $list; return
}

function ConvertTo-Int64Scalar {
    param($Value, [string]$Name = 'value')
    if ($null -eq $Value) {
        throw "Expected a single Int64 for $Name, got null"
    }
    if (Test-IsQaCollection $Value) {
        $flat = New-QaList
        foreach ($x in (ConvertTo-QaList $Value)) {
            if ($null -ne $x) { [void]$flat.Add($x) }
        }
        if ($flat.Count -eq 0) { throw "Expected a single Int64 for $Name, got an empty array" }
        if ($flat.Count -gt 1) {
            throw "Expected a single Int64 for $Name, got an array of $($flat.Count) values"
        }
        return (ConvertTo-Int64Scalar $flat[0] -Name $Name)
    }
    if ($Value -is [hashtable] -or $Value -is [System.Collections.IDictionary] -or (Test-IsQaRow $Value)) {
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
    try { return [int64]$Value }
    catch { throw "Expected a single Int64 for $Name, got $($Value.GetType().FullName)" }
}

function Get-FiresFromText {
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $list = New-QaList
    if ($Text) {
        $pattern = "DoAlarm fire id=$id scheduled=(\d+) actual=(\d+)"
        foreach ($m in [regex]::Matches($Text, $pattern)) {
            $sched = [int64]$m.Groups[1].Value
            $act = [int64]$m.Groups[2].Value
            [void]$list.Add(@{ Scheduled = $sched; Actual = $act; Delta = ($act - $sched) })
        }
    }
    Out-QaList $list; return
}

function Get-RepeatNextsFromText {
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $list = New-QaList
    if ($Text) {
        foreach ($m in [regex]::Matches($Text, "DoAlarm repeat-next id=$id i=(\d+) at=(\d+)")) {
            [void]$list.Add(@{ Index = [int]$m.Groups[1].Value; At = [int64]$m.Groups[2].Value })
        }
    }
    Out-QaList $list; return
}

function Get-RepeatDumpsFromText {
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $list = New-QaList
    if ($Text) {
        $pattern = "DoAlarm repeat id=$id rule=(.+?) from=(\S+) dueAt=(\d+) patternDue=(null|\d+)(?: next=(\S+))?"
        foreach ($m in [regex]::Matches($Text, $pattern)) {
            $pd = $null
            if ($m.Groups[4].Value -ne 'null') { $pd = [int64]$m.Groups[4].Value }
            $next = $null
            if ($m.Groups[5].Success -and $m.Groups[5].Value) { $next = $m.Groups[5].Value }
            [void]$list.Add(@{
                Id = $id
                Rule = $m.Groups[1].Value
                From = $m.Groups[2].Value
                DueAt = [int64]$m.Groups[3].Value
                PatternDue = $pd
                Next = $next
            })
        }
    }
    Out-QaList $list; return
}

function Get-RepeatAdvancesFromText {
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $list = New-QaList
    if ($Text) {
        foreach ($m in [regex]::Matches($Text, "DoAlarm repeat-advance id=$id newDue=(\d+) patternDue=(null|\d+) from=(\S+)")) {
            $pd = $null
            if ($m.Groups[2].Value -ne 'null') { $pd = [int64]$m.Groups[2].Value }
            [void]$list.Add(@{ NewDue = [int64]$m.Groups[1].Value; PatternDue = $pd; From = $m.Groups[3].Value })
        }
    }
    Out-QaList $list; return
}

function Get-SnoozesFromText {
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $list = New-QaList
    if ($Text) {
        foreach ($m in [regex]::Matches($Text, "DoAlarm snooze id=$id newDue=(\d+) option=(\d+)")) {
            [void]$list.Add(@{ NewDue = [int64]$m.Groups[1].Value; Option = [int]$m.Groups[2].Value })
        }
    }
    Out-QaList $list; return
}

function Get-DebugAddsFromText {
    param([string]$Text)
    $list = New-QaList
    if ($Text) {
        foreach ($m in [regex]::Matches($Text, 'DoAlarm debug-add id=(\d+) dueAt=(\d+) nagMinutes=(\-?\d+)')) {
            [void]$list.Add(@{ Id = [int64]$m.Groups[1].Value; DueAt = [int64]$m.Groups[2].Value; NagMinutes = [int]$m.Groups[3].Value })
        }
    }
    Out-QaList $list; return
}

function Get-ParseMatchesFromText {
    param([string]$Text)
    $list = New-QaList
    if ($Text) {
        foreach ($m in [regex]::Matches($Text, 'DoAlarm parse match="(.*?)" title="(.*?)" dueAt=(\d+) rule=(.+?) span=(\S+)')) {
            [void]$list.Add(@{ Matched = $m.Groups[1].Value; Title = $m.Groups[2].Value; DueAt = [int64]$m.Groups[3].Value; Rule = $m.Groups[4].Value; Span = $m.Groups[5].Value })
        }
    }
    Out-QaList $list; return
}

function Get-AlertFallbacksFromText {
    param([string]$Text, $Id)
    $id = ConvertTo-Int64Scalar $Id -Name 'Id'
    $list = New-QaList
    if ($Text) {
        foreach ($m in [regex]::Matches($Text, "DoAlarm alert-fallback id=$id requested=(\S+) effective=(\S+) reason=(\S+)")) {
            [void]$list.Add(@{ Requested = $m.Groups[1].Value; Effective = $m.Groups[2].Value; Reason = $m.Groups[3].Value })
        }
    }
    Out-QaList $list; return
}

function Get-AlarmOrigWhenList {
    # Current pending RTC_WAKEUP Alarm{... pkg} origWhen values, not history.
    param([string]$Dump, [string]$Package)
    $list = New-QaList
    if (-not $Dump -or -not $Package) { Out-QaList $list; return }
    $pkgEsc = [regex]::Escape($Package)
    $rx = [regex]('(?m)^\s*RTC_WAKEUP\s+#\d+:\s+Alarm\{[^}\r\n]*origWhen\s+(\d{10,})[^}\r\n]*' + $pkgEsc)
    foreach ($m in $rx.Matches($Dump)) {
        [void]$list.Add([int64]$m.Groups[1].Value)
    }
    Out-QaList $list; return
}

function Get-ApkRoomSchemaVersion {
    param($RepoDir)
    if (-not $RepoDir) { return $null }
    $rel = 'app/src/main/java/com/evansappstudio/doapp/data/AppDatabase.kt'
    $kt = Join-Path $RepoDir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $kt)) { $kt = Join-Path $RepoDir $rel }
    if (-not (Test-Path -LiteralPath $kt)) { return $null }
    $t = Get-Content -LiteralPath $kt -Raw -ErrorAction SilentlyContinue
    if (-not $t) { return $null }
    $m = [regex]::Match($t, 'version\s*=\s*(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return $null
}

function ConvertFrom-RoomUserVersionHex {
    param([string]$Raw)
    if (-not $Raw) { return $null }
    $hex = [regex]::Replace($Raw, '[^0-9a-fA-F]', '')
    if ($hex.Length -lt 8) { return $null }
    try { return [Convert]::ToInt32($hex.Substring(0, 8), 16) } catch { return $null }
}

function Write-QaSharedLog {
    param([string]$Message)
    if (Get-Command Write-Log -ErrorAction SilentlyContinue) { Write-Log $Message }
    else { Write-Host $Message }
}

function Invoke-QaAdbShell {
    param([string]$Command, [switch]$AllowFail)
    if (Get-Command Invoke-AdbShell -ErrorAction SilentlyContinue) {
        return Invoke-AdbShell $Command -AllowFail:$AllowFail
    }
    throw 'Invoke-AdbShell is not defined'
}

function Get-InstalledRoomSchemaVersion {
    param([string]$Package)
    if (-not $Package) { return $null }
    # SQLite user_version is a 4-byte big-endian int at offset 60 of do.db.
    $cmds = @(
        "run-as $Package dd if=databases/do.db bs=1 skip=60 count=4 2>/dev/null | od -An -tx1",
        "run-as $Package dd if=databases/do.db bs=1 skip=60 count=4 2>/dev/null | toybox od -An -tx1",
        "run-as $Package dd if=databases/do.db bs=1 skip=60 count=4 2>/dev/null | hexdump -C -n 4"
    )
    foreach ($cmd in $cmds) {
        try {
            $r = Invoke-QaAdbShell $cmd -AllowFail
            $ver = ConvertFrom-RoomUserVersionHex ([string]$r.Output)
            if ($null -ne $ver) { return $ver }
        } catch { }
    }
    return $null
}

function Test-QaPackageInstalled {
    param([string]$Package)
    if (-not $Package) { return $false }
    try {
        $r = Invoke-QaAdbShell "pm path $Package" -AllowFail
        return ([string]$r.Output -match 'package:')
    } catch { return $false }
}

function Uninstall-DoAppIfDowngrade {
    <#
      Uninstall before install -r when the phone already has a newer Room
      schema or versionCode than this APK. Room cannot migrate v3 -> v2
      (Spec 5 then Spec 4 on the same test phone). App data is not restored.
    #>
    param($RepoDir, [string]$Package)
    if ($DryRun) { return $false }
    if (-not $Package) { $Package = $Pkg }
    if (-not (Test-QaPackageInstalled $Package)) {
        Write-QaSharedLog "Install: $Package is not installed."
        return $false
    }
    $apkRoom = Get-ApkRoomSchemaVersion $RepoDir
    $instRoom = Get-InstalledRoomSchemaVersion $Package
    $reasons = New-QaList
    if ($null -ne $instRoom -and $null -ne $apkRoom -and $instRoom -gt $apkRoom) {
        [void]$reasons.Add("installed Room schema v$instRoom is newer than this APK's v$apkRoom")
    }
    if ($reasons.Count -eq 0) {
        Write-QaSharedLog ("Install: $Package is already present (Room installed=$instRoom apk=$apkRoom). install -r is enough unless a downgrade is detected after start.")
        return $false
    }
    $msg = "Uninstalling $Package before install: " + ([string]::Join('; ', $reasons.ToArray())) + ". This is a test phone; app data will not be restored. Room cannot downgrade (a previous run died with 'A migration from 3 to 2 was required but not found')."
    Write-QaSharedLog $msg
    Invoke-QaAdbShell "pm uninstall $Package" -AllowFail | Out-Null
    return $true
}

function Test-RoomDowngradeInLog {
    param($Text)
    if (-not $Text) { return $false }
    return ([string]$Text -match 'A migration from \d+ to \d+ was required but not found')
}

function Get-HealthDumpsFromText {
    param([string]$Text)
    $list = New-QaList
    if (-not $Text) { Out-QaList $list; return }
    foreach ($m in [regex]::Matches($Text, 'DoAlarm health (?!late|test)((?:\S+=\S+\s*)+)')) {
        $body = $m.Groups[1].Value.Trim()
        $fields = @{}
        foreach ($fm in [regex]::Matches($body, '(\w+)=(\S+)')) {
            $fields[$fm.Groups[1].Value] = $fm.Groups[2].Value
        }
        [void]$list.Add([pscustomobject]@{
            Body = $body
            Fields = $fields
            Notif = $fields['notif']
            Channels = $fields['channels']
            Exact = $fields['exact']
            Fsi = $fields['fsi']
            Dnd = $fields['dnd']
            Battery = $fields['battery']
            BgRestrict = $fields['bgRestrict']
            Samsung = $fields['samsung']
            Standby = $fields['standby']
            Boot = $fields['boot']
            Unlocked = $fields['unlocked']
            LastLate = $fields['lastLate']
            Test = $fields['test']
            Late = $fields['late']
        })
    }
    Out-QaList $list; return
}

function Get-HealthTestScheduledFromText {
    param([string]$Text)
    $list = New-QaList
    if (-not $Text) { Out-QaList $list; return }
    foreach ($m in [regex]::Matches($Text, 'DoAlarm health-test scheduled=(\d+) id=(\d+)')) {
        [void]$list.Add([pscustomobject]@{
            Scheduled = [int64]$m.Groups[1].Value
            Id = [int64]$m.Groups[2].Value
        })
    }
    Out-QaList $list; return
}

function Get-HealthTestFiresFromText {
    param([string]$Text)
    $list = New-QaList
    if (-not $Text) { Out-QaList $list; return }
    foreach ($m in [regex]::Matches($Text, 'DoAlarm health-test id=(\d+) scheduled=(\d+) actual=(\d+)')) {
        $sched = [int64]$m.Groups[2].Value
        $act = [int64]$m.Groups[3].Value
        [void]$list.Add([pscustomobject]@{
            Id = [int64]$m.Groups[1].Value
            Scheduled = $sched
            Actual = $act
            Delta = ($act - $sched)
        })
    }
    Out-QaList $list; return
}

function Get-HealthLatesFromText {
    param([string]$Text)
    $list = New-QaList
    if (-not $Text) { Out-QaList $list; return }
    foreach ($m in [regex]::Matches($Text, 'DoAlarm health-late (?:synthetic )?lagMs=(-?\d+)')) {
        $synthetic = $m.Value -match 'synthetic'
        [void]$list.Add([pscustomobject]@{
            LagMs = [int64]$m.Groups[1].Value
            Synthetic = $synthetic
        })
    }
    Out-QaList $list; return
}

function Test-QaAlarmPresentForDue {
    param([string]$Dump, [string]$Package, $DueAt)
    $want = ConvertTo-Int64Scalar -Value $DueAt -Name 'DueAt'
    $whens = ConvertTo-QaList (Get-AlarmOrigWhenList -Dump $Dump -Package $Package)
    foreach ($w in $whens) {
        if ([int64]$w -eq $want) { return $true }
    }
    return $false
}


function Stop-QaProcessTree {
    param($Process)
    if ($null -eq $Process) { return }
    $procId = $null
    try { $procId = $Process.Id } catch { return }
    if ($null -eq $procId) { return }
    try {
        if ($Process.HasExited) { return }
    } catch { }
    try {
        if ($env:OS -eq 'Windows_NT') {
            $taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
            if (-not (Test-Path -LiteralPath $taskkill)) { $taskkill = 'taskkill.exe' }
            $tk = Start-Process -FilePath $taskkill -ArgumentList @('/F', '/T', '/PID', "$procId") -Wait -PassThru -WindowStyle Hidden -ErrorAction SilentlyContinue
            [void]$tk
        } else {
            try { & kill -TERM $procId 2>$null } catch { }
            Start-Sleep -Milliseconds 200
        }
    } catch { }
    try {
        if (-not $Process.HasExited) { $Process.Kill() }
    } catch { }
}

function Invoke-QaBinaryRedirect {
    <#
      PS 5.1-safe, binary-safe process I/O with a timeout. Copies stdout to
      OutputPath and/or stdin from InputPath via BaseStream, so file bytes
      never appear on a command line and are not re-encoded as text.
      If the process (or a hung CopyTo) is still running after TimeoutSec,
      the process tree is killed and the result has TimedOut = $true.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [string[]]$ArgumentList,
        [string]$OutputPath,
        [string]$InputPath,
        [int]$TimeoutSec = 90
    )
    if ($TimeoutSec -le 0) { $TimeoutSec = 90 }
    $timeoutMs = [int]($TimeoutSec * 1000)
    $deadline = [datetime]::UtcNow.AddMilliseconds($timeoutMs)
    function Get-QaRemainMs {
        $ms = [int]($deadline - [datetime]::UtcNow).TotalMilliseconds
        if ($ms -lt 1) { return 1 }
        return $ms
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FileName
    $quoted = New-Object System.Collections.Generic.List[string]
    foreach ($a in @($ArgumentList)) {
        if ($null -eq $a) { continue }
        $s = [string]$a
        if ($s -match '[\s"]') { [void]$quoted.Add(('"{0}"' -f ($s -replace '"', '\"'))) }
        else { [void]$quoted.Add($s) }
    }
    $psi.Arguments = [string]::Join(' ', $quoted)
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = [bool]$InputPath
    $psi.CreateNoWindow = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()
    $errTask = $p.StandardError.ReadToEndAsync()
    $inStream = $null
    $outStream = $null
    $timedOut = $false
    try {
        if ($InputPath) {
            $inStream = [System.IO.File]::OpenRead($InputPath)
            $inTask = $inStream.CopyToAsync($p.StandardInput.BaseStream)
            if (-not $inTask.Wait((Get-QaRemainMs))) {
                $timedOut = $true
            } else {
                try { $p.StandardInput.BaseStream.Flush(); $p.StandardInput.Close() } catch { }
            }
        }
        if ($OutputPath) {
            $dir = Split-Path -Parent $OutputPath
            if ($dir -and -not (Test-Path -LiteralPath $dir)) {
                New-Item -ItemType Directory -Path $dir -Force | Out-Null
            }
            $outStream = [System.IO.File]::Create($OutputPath)
            $outTask = $p.StandardOutput.BaseStream.CopyToAsync($outStream)
        } else {
            $outTask = $p.StandardOutput.BaseStream.CopyToAsync([System.IO.Stream]::Null)
        }
        if (-not $timedOut) {
            if (-not $p.WaitForExit((Get-QaRemainMs))) { $timedOut = $true }
        }
        if ($timedOut) {
            Stop-QaProcessTree -Process $p
            try { [void]$p.WaitForExit(3000) } catch { }
        }
        if ($outTask) {
            $drain = 5000
            if (-not $timedOut) { $drain = [Math]::Max(1000, (Get-QaRemainMs)) }
            [void]$outTask.Wait($drain)
        }
    } finally {
        if ($outStream) { try { $outStream.Close() } catch { } }
        if ($inStream) { try { $inStream.Close() } catch { } }
    }
    $err = ''
    try {
        if ($errTask.Wait(2000)) { $err = $errTask.Result }
    } catch { $err = $_.Exception.Message }
    if ($timedOut) {
        $msg = "timed out after ${TimeoutSec}s"
        if ($err) { $msg = $msg + ': ' + $err }
        return @{ ExitCode = -1; Error = $msg; TimedOut = $true }
    }
    return @{ ExitCode = $p.ExitCode; Error = $err; TimedOut = $false }
}

function Invoke-QaAdbTimed {
    <#
      Timeout-guarded adb call. Captures stdout to OutputPath (or a temp
      file) as text. Does not hang: the process tree is killed after
      TimeoutSec and TimedOut is set.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [string[]]$ArgumentList,
        [int]$TimeoutSec = 90,
        [string]$OutputPath
    )
    $tmp = $OutputPath
    $cleanup = $false
    if (-not $tmp) {
        $tmp = [System.IO.Path]::GetTempFileName()
        $cleanup = $true
    }
    try {
        $r = Invoke-QaBinaryRedirect -FileName $Adb -ArgumentList $ArgumentList -OutputPath $tmp -TimeoutSec $TimeoutSec
        $text = ''
        if (Test-Path -LiteralPath $tmp) {
            $text = [System.IO.File]::ReadAllText($tmp)
        }
        $err = ''
        if ($r.ContainsKey('Error')) { $err = [string]$r.Error }
        return @{
            ExitCode = $r.ExitCode
            Output = $text
            Error = $err
            TimedOut = [bool]$r.TimedOut
        }
    } finally {
        if ($cleanup -and $tmp -and (Test-Path -LiteralPath $tmp)) {
            Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        }
    }
}

function Copy-QaRunAsFileToLocal {
    <#
      adb exec-out run-as <pkg> cat -- <remote>  redirected to a local file.
      Never captures the file through adb shell / Invoke-Adb text piping.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [string]$Serial,
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][string]$RemoteRelative,
        [Parameter(Mandatory = $true)][string]$LocalPath,
        [int]$TimeoutSec = 90
    )
    $adbArgs = New-Object System.Collections.Generic.List[string]
    if ($Serial) { [void]$adbArgs.Add('-s'); [void]$adbArgs.Add($Serial) }
    [void]$adbArgs.Add('exec-out')
    [void]$adbArgs.Add('run-as')
    [void]$adbArgs.Add($Package)
    [void]$adbArgs.Add('cat')
    [void]$adbArgs.Add('--')
    [void]$adbArgs.Add($RemoteRelative)
    $r = Invoke-QaBinaryRedirect -FileName $Adb -ArgumentList $adbArgs.ToArray() -OutputPath $LocalPath -TimeoutSec $TimeoutSec
    if ($r.TimedOut) {
        throw ("Copy-QaRunAsFileToLocal timed out after {0}s remote={1}" -f $TimeoutSec, $RemoteRelative)
    }
    if ($r.ExitCode -ne 0) {
        throw ("Copy-QaRunAsFileToLocal failed exit={0} err={1} remote={2}" -f $r.ExitCode, $r.Error, $RemoteRelative)
    }
    return $LocalPath
}

function Copy-QaLocalFileToRunAs {
    <#
      adb push <local> /data/local/tmp/<name>, then run-as <pkg> cp (or
      `cat /data/local/tmp/x | run-as <pkg> sh -c 'cat > files/...'`),
      then rm the tmp file.
      Never puts file bytes or base64 on the adb command line, and never
      feeds stdin to adb exec-out (Windows adb does not forward stdin on
      exec-out, which hung CopyTo with "The pipe has been ended").
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [string]$Serial,
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][string]$RemoteRelative,
        [Parameter(Mandatory = $true)][string]$LocalPath,
        [int]$TimeoutSec = 90
    )
    if ($RemoteRelative -match "[;|&<>'`\$]") {
        throw "Refusing a remote path with shell metacharacters: $RemoteRelative"
    }
    if ($Package -notmatch '^[A-Za-z0-9._]+$') {
        throw "Refusing a package name with unexpected characters: $Package"
    }
    $base = [System.IO.Path]::GetFileName($RemoteRelative)
    if ($base -notmatch '^[A-Za-z0-9._-]+$') { $base = 'payload' }
    $tmpName = 'qa-' + [guid]::NewGuid().ToString('N') + '-' + $base
    $tmpRemote = '/data/local/tmp/' + $tmpName

    $prefix = New-Object System.Collections.Generic.List[string]
    if ($Serial) { [void]$prefix.Add('-s'); [void]$prefix.Add($Serial) }

    $pushArgs = New-Object System.Collections.Generic.List[string]
    foreach ($a in $prefix) { [void]$pushArgs.Add($a) }
    [void]$pushArgs.Add('push')
    [void]$pushArgs.Add($LocalPath)
    [void]$pushArgs.Add($tmpRemote)
    $r = Invoke-QaBinaryRedirect -FileName $Adb -ArgumentList $pushArgs.ToArray() -TimeoutSec $TimeoutSec
    if ($r.TimedOut) {
        throw ("Copy-QaLocalFileToRunAs push timed out after {0}s remote={1}" -f $TimeoutSec, $RemoteRelative)
    }
    if ($r.ExitCode -ne 0) {
        throw ("Copy-QaLocalFileToRunAs push failed exit={0} err={1} dest={2}" -f $r.ExitCode, $r.Error, $tmpRemote)
    }

    try {
        $cpArgs = New-Object System.Collections.Generic.List[string]
        foreach ($a in $prefix) { [void]$cpArgs.Add($a) }
        [void]$cpArgs.Add('shell')
        [void]$cpArgs.Add('run-as')
        [void]$cpArgs.Add($Package)
        [void]$cpArgs.Add('cp')
        [void]$cpArgs.Add($tmpRemote)
        [void]$cpArgs.Add($RemoteRelative)
        $cp = Invoke-QaAdbTimed -Adb $Adb -ArgumentList $cpArgs.ToArray() -TimeoutSec $TimeoutSec
        if ($cp.TimedOut) {
            throw ("Copy-QaLocalFileToRunAs run-as cp timed out after {0}s remote={1}" -f $TimeoutSec, $RemoteRelative)
        }
        $copied = ($cp.ExitCode -eq 0)
        if (-not $copied) {
            $catCmd = "cat $tmpRemote | run-as $Package sh -c 'cat > $RemoteRelative'"
            $catArgs = New-Object System.Collections.Generic.List[string]
            foreach ($a in $prefix) { [void]$catArgs.Add($a) }
            [void]$catArgs.Add('shell')
            [void]$catArgs.Add($catCmd)
            $cat = Invoke-QaAdbTimed -Adb $Adb -ArgumentList $catArgs.ToArray() -TimeoutSec $TimeoutSec
            if ($cat.TimedOut) {
                throw ("Copy-QaLocalFileToRunAs cat fallback timed out after {0}s remote={1}" -f $TimeoutSec, $RemoteRelative)
            }
            if ($cat.ExitCode -ne 0) {
                throw ("Copy-QaLocalFileToRunAs failed cp err={0} cat exit={1} err={2} remote={3}" -f $cp.Error, $cat.ExitCode, $cat.Error, $RemoteRelative)
            }
        }
    } finally {
        $rmArgs = New-Object System.Collections.Generic.List[string]
        foreach ($a in $prefix) { [void]$rmArgs.Add($a) }
        [void]$rmArgs.Add('shell')
        [void]$rmArgs.Add("rm -f $tmpRemote")
        [void](Invoke-QaAdbTimed -Adb $Adb -ArgumentList $rmArgs.ToArray() -TimeoutSec $TimeoutSec)
    }
    return $RemoteRelative
}


function ConvertTo-QaJsonArray {
    # PS 5.1 ConvertTo-Json unwraps a single-element array into one object.
    # Always return a JSON array, including "[]" and a one-element "[ {...} ]".
    [CmdletBinding()]
    param($Items, [int]$Depth = 12)
    $list = ConvertTo-QaList $Items
    if (-not $list -or $list.Count -eq 0) { return '[]' }
    $parts = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $list.Count; $i++) {
        $item = Get-QaItem $list $i
        [void]$parts.Add(($item | ConvertTo-Json -Depth $Depth))
    }
    return ('[' + [string]::Join(',', $parts.ToArray()) + ']')
}

function Get-QaLiveRemindersByTitle {
    # Reminders in a backup JSON with this exact title and isDone false.
    [CmdletBinding()]
    param([string]$Json, [string]$Title)
    $list = New-QaList
    if (-not $Json -or -not $Title) { Out-QaList $list; return }
    $doc = $null
    try { $doc = $Json | ConvertFrom-Json } catch { Out-QaList $list; return }
    $rows = ConvertTo-QaList $doc.reminders
    foreach ($rem in $rows) {
        if ([string]$rem.title -ne $Title) { continue }
        $done = $false
        try { $done = [bool]$rem.isDone } catch { $done = $false }
        if (-not $done) { [void]$list.Add($rem) }
    }
    Out-QaList $list; return
}

function New-QaSingleReminderBackup {
    <#
      Slice a backup JSON to one live (not done) reminder with this title.
      If that is not exactly one row, Ok is false and Text is empty: never
      return the full export as a fallback.
    #>
    [CmdletBinding()]
    param([string]$Json, [string]$Title)
    if (-not $Json -or -not $Title) {
        return @{ Ok = $false; Uuid = ''; Text = ''; Count = 0; Reason = 'no export text or title' }
    }
    $doc = $null
    try { $doc = $Json | ConvertFrom-Json } catch {
        return @{ Ok = $false; Uuid = ''; Text = ''; Count = 0; Reason = ("could not parse export: " + $_.Exception.Message) }
    }
    $live = ConvertTo-QaList (Get-QaLiveRemindersByTitle -Json $Json -Title $Title)
    $count = 0
    if ($live) { $count = [int]$live.Count }
    if ($count -ne 1) {
        return @{
            Ok = $false
            Uuid = ''
            Text = ''
            Count = $count
            Reason = ("expected exactly one live (not done) row titled '" + $Title + "', found " + $count)
        }
    }
    $row = Get-QaItem $live 0
    $uuid = [string]$row.uuid
    if (-not $uuid) {
        return @{ Ok = $false; Uuid = ''; Text = ''; Count = 1; Reason = ("live row titled '" + $Title + "' has an empty uuid") }
    }
    $settingsJson = 'null'
    if ($null -ne $doc.settings) { $settingsJson = ($doc.settings | ConvertTo-Json -Depth 12) }
    $appIdJson = ($doc.appId | ConvertTo-Json)
    $verJson = ($doc.appVersionName | ConvertTo-Json)
    $exportedAt = $doc.exportedAt
    $schema = $doc.schemaVersion
    $remindersJson = ConvertTo-QaJsonArray -Items @($row) -Depth 12
    $text = ('{{"schemaVersion":{0},"appId":{1},"appVersionName":{2},"exportedAt":{3},"settings":{4},"reminders":{5}}}' -f $schema, $appIdJson, $verJson, $exportedAt, $settingsJson, $remindersJson)
    return @{ Ok = $true; Uuid = $uuid; Text = $text; Count = 1; Reason = '' }
}

function Test-QaSharedLibInScope {
    [CmdletBinding()]
    param()
    $required = @(
        'ConvertTo-QaList',
        'Out-QaList',
        'Uninstall-DoAppIfDowngrade',
        'ConvertTo-Int64Scalar',
        'Test-QaAlarmPresentForDue',
        'Invoke-QaBinaryRedirect',
        'Invoke-QaAdbTimed',
        'Copy-QaRunAsFileToLocal',
        'Copy-QaLocalFileToRunAs',
        'Get-QaLiveRemindersByTitle',
        'New-QaSingleReminderBackup'
    )
    foreach ($name in $required) {
        if (-not (Get-Command -Name $name -ErrorAction SilentlyContinue)) {
            throw "Shared lib function $name is not in scope. Dot-source qa/lib/qa-runner-shared.ps1 at script scope, not inside a function."
        }
    }
    $one = ConvertTo-QaList @(@{ K = 1 })
    if (-not $one -or $one.Count -ne 1) {
        throw 'ConvertTo-QaList did not return a one-row list at script scope.'
    }
    return $true
}
