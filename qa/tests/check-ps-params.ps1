#Requires -Version 5.1
<#
.SYNOPSIS
  Flag calls in a .ps1 file that use a named parameter the target function does not declare.

.DESCRIPTION
  Parses each file with the PowerShell AST, maps function name -> param() names,
  and reports CommandAst calls whose -Name does not match that list.
  Common parameters are allowed when the function has [CmdletBinding()].
  Splatted arguments (@foo) are not expanded. Script-level param() is ignored.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$Path
)

$ErrorActionPreference = 'Stop'
$common = @(
    'Verbose', 'Debug', 'ErrorAction', 'WarningAction', 'InformationAction',
    'ErrorVariable', 'WarningVariable', 'InformationVariable', 'OutVariable',
    'OutBuffer', 'PipelineVariable', 'WhatIf', 'Confirm', 'UseTransaction'
)

$fail = 0
foreach ($item in $Path) {
    $full = [System.IO.Path]::GetFullPath($item)
    if (-not (Test-Path -LiteralPath $full)) { throw "file not found: $item" }
    $parseErrs = $null
    $tokens = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($full, [ref]$tokens, [ref]$parseErrs)
    if ($parseErrs -and $parseErrs.Count) {
        foreach ($e in $parseErrs) {
            Write-Host ("PARSE {0}:{1} {2}" -f $full, $e.Extent.StartLineNumber, $e.Message)
        }
        $fail++
        continue
    }

    $funcs = @{}
    foreach ($fn in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
        $names = @()
        $hasBinding = $false
        $pb = $fn.Body.ParamBlock
        if ($pb) {
            foreach ($p in $pb.Parameters) {
                $names += $p.Name.VariablePath.UserPath
            }
            foreach ($attr in $pb.Attributes) {
                $typeName = $attr.TypeName.FullName
                if ($typeName -eq 'CmdletBinding' -or $typeName -like '*.CmdletBinding') { $hasBinding = $true }
            }
        }
        $funcs[$fn.Name] = @{ Params = $names; CmdletBinding = $hasBinding; Line = $fn.Extent.StartLineNumber }
    }

    foreach ($cmd in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.CommandAst] }, $true)) {
        $first = $cmd.CommandElements[0]
        if ($first -isnot [System.Management.Automation.Language.StringConstantExpressionAst]) { continue }
        $cname = $first.Value
        if (-not $funcs.ContainsKey($cname)) { continue }
        $meta = $funcs[$cname]
        $allowed = @($meta.Params)
        if ($meta.CmdletBinding) { $allowed += $common }
        $allowedSet = @{}
        foreach ($a in $allowed) { $allowedSet[$a.ToLowerInvariant()] = $true }

        foreach ($el in $cmd.CommandElements) {
            if ($el -isnot [System.Management.Automation.Language.CommandParameterAst]) { continue }
            $pname = $el.ParameterName
            if (-not $allowedSet.ContainsKey($pname.ToLowerInvariant())) {
                $known = if ($meta.Params.Count) { ($meta.Params -join ', ') } else { '(none)' }
                Write-Host ("MISMATCH {0}:{1} {2} -{3}  (declared: {4})" -f $full, $cmd.Extent.StartLineNumber, $cname, $pname, $known)
                $fail++
            }
        }
    }
}

if ($fail -gt 0) {
    Write-Host "FAILED $fail"
    exit 1
}
Write-Host 'OK'
exit 0
