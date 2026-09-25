<#
.SYNOPSIS
    Drives one buffer-edit observation against a running IDE host, and prints the evidence.

.DESCRIPTION
    The claim under observation is narrow and specific: an edit asked for over a host's write API appears in the
    IDE's own editor as an *unsaved* change, the file on disk is untouched, and the reader's own undo takes it
    back. This script performs the half a machine can do and prints exactly what a human then has to confirm by
    looking at the IDE - the response, and the proof that the bytes on disk did not move.

    It works against any host that speaks the contract: the JetBrains plugin's bridge (default 18881), the VS Code
    extension's bridge (default 18882), or webviewd. The buffer path is chosen by the host, not by this script.

    Read doc/ide-observation-checklist.md for the steps around it, and report the whole output plus the two
    visual facts.

.PARAMETER Port
    The host's HTTP port. 18881 for the JetBrains plugin, 18882 for the VS Code extension.

.PARAMETER Token
    The token configured in that host. Required: every state-changing route refuses a caller without it.

.PARAMETER File
    The file to edit. It must be open in the IDE for a buffer edit (VS Code in particular needs an open document).

.PARAMETER Find
    A piece of text in that file to replace. Must be on one line.

.PARAMETER Replace
    What to replace it with. Defaults to OBSERVED, which is easy to spot in the editor.

.PARAMETER Target
    auto (default), buffer or disk. 'auto' is what the contract's default is: the editor's buffer when the host
    can, otherwise this host's own write.

.PARAMETER CheckRefusals
    Also send one request with a wrong token and one with a stale digest, so the refusal behaviour is evidence
    rather than a claim.

.EXAMPLE
    .\observe-edit-host.ps1 -Port 18881 -Token obs-token -File D:\tmp\obs\Sample.java -Find 'int x = 1;' -Replace 'int x = 42;' -CheckRefusals
#>
[CmdletBinding()]
param(
    [int] $Port = 18882,
    [Parameter(Mandatory = $true)] [string] $Token,
    [Parameter(Mandatory = $true)] [string] $File,
    [Parameter(Mandatory = $true)] [string] $Find,
    [string] $Replace = 'OBSERVED',
    [ValidateSet('auto', 'buffer', 'disk')] [string] $Target = 'auto',
    [switch] $CheckRefusals
)

$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port"

function Show([string] $label, $value) {
    # Out-String so the JSON is written where the label is: a raw object left the pipeline collects at the end
    # of the run, which puts the evidence out of order in exactly the paste this script exists to produce.
    Write-Host "--- $label"
    $rendered = if ($value -is [string]) { $value } else { $value | ConvertTo-Json -Depth 8 | Out-String }
    Write-Host $rendered.TrimEnd()
}

function DigestOf([string] $path) {
    'sha256:' + (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant()
}

function Invoke-Host([string] $route, [hashtable] $headers, [string] $body) {
    try {
        return Invoke-RestMethod -Uri "$base$route" -Method Post -Headers $headers `
            -ContentType 'application/json' -Body $body
    } catch {
        # A refusal is a result, not a crash: show the host's own status and body. `ErrorDetails.Message` holds
        # the body in Windows PowerShell; the response stream is the fallback (and read once, since it is
        # forward-only - reading it twice is why an earlier version of this reported empty refusal bodies).
        $response = $_.Exception.Response
        $status = if ($null -ne $response) { [int] $response.StatusCode } else { 0 }
        $detail = ''
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $detail = $_.ErrorDetails.Message
        } elseif ($null -ne $response) {
            try {
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                $detail = $reader.ReadToEnd()
            } catch {
                $detail = "<the host's body could not be read: $($_.Exception.Message)>"
            }
        }
        return [pscustomobject]@{ status = $status; body = $detail }
    }
}

# --- the file, its bytes, and where the needle is -------------------------------------------------------
$full = (Resolve-Path -Path $File).Path
$before = DigestOf $full
$text = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($full))

$index = $text.IndexOf($Find, [System.StringComparison]::Ordinal)
if ($index -lt 0) { throw "'$Find' does not appear in $full" }
$lineStart = $text.LastIndexOf("`n", [Math]::Max($index - 1, 0))
if ($lineStart -lt 0) { $lineStart = -1 }
$lineIndex = ($text.Substring(0, $index) -split "`n").Count - 1
$column = $index - $lineStart
$endColumn = $column + $Find.Length
if ($Find.Contains("`n")) { throw "'$Find' spans lines; give a single-line needle" }

Write-Host "host $base"
Write-Host "file $full"
Write-Host "disk digest before  $before"
Write-Host "edit: line $($lineIndex + 1), columns $column..$endColumn  '$Find' -> '$Replace'"
Write-Host ''

# --- what the host says it can do ----------------------------------------------------------------------
try {
    Show 'health' (Invoke-RestMethod -Uri "$base/health" -Method Get)
} catch {
    throw "no host answered on $base/health - is the IDE running with its bridge on? ($($_.Exception.Message))"
}

$headers = @{ 'X-WebView-Token' = $Token }
$edit = @{
    startLine = $lineIndex + 1; startColumn = $column
    endLine = $lineIndex + 1; endColumn = $endColumn
    newText = $Replace
}
$body = @{
    filePath = ($full -replace '\\', '/')
    expectedDigest = $before
    edits = @($edit)
    dryRun = $false
    target = $Target
} | ConvertTo-Json -Depth 6

Show 'proposal (dryRun, nothing should change)' (Invoke-Host '/api/v1/diff' $headers (@{
    filePath = ($full -replace '\\', '/'); expectedDigest = $before; edits = @($edit); target = $Target
} | ConvertTo-Json -Depth 6))

$answer = Invoke-Host '/api/v1/applyEdit' $headers $body
Show 'applyEdit' $answer

Start-Sleep -Milliseconds 300
$after = DigestOf $full
Write-Host ''
Write-Host "disk digest after   $after"

if ($CheckRefusals) {
    Write-Host ''
    Show 'applyEdit with a WRONG token (expect 403)' (Invoke-Host '/api/v1/applyEdit' @{ 'X-WebView-Token' = 'definitely-wrong' } $body)
    $stale = @{
        filePath = ($full -replace '\\', '/')
        expectedDigest = ('sha256:' + ('0' * 64))
        edits = @($edit); dryRun = $false; target = $Target
    } | ConvertTo-Json -Depth 6
    Show 'applyEdit with a STALE digest (expect 409 stale)' (Invoke-Host '/api/v1/applyEdit' $headers $stale)
}

Write-Host ''
if ($before -eq $after) {
    Write-Host 'DISK UNCHANGED - consistent with a buffer edit, which is what is being observed.'
    Write-Host ''
    Write-Host 'NOW LOOK AT THE IDE, and do not save:'
    Write-Host '  1. the file''s editor shows the replacement, and the tab is marked modified'
    Write-Host '  2. one undo (Ctrl+Z) removes it and the tab goes clean again'
} else {
    Write-Host 'DISK CHANGED - this host wrote the file itself, so there is nothing unsaved to look at.'
    Write-Host 'That is the correct outcome for a host with no editor attached (webviewd --host none).'
    Write-Host 'A buffer observation needs a host that has an editor: the JetBrains plugin or the VS Code extension.'
}
