param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('publisher', 'sandbox')]
    [string]$Mode,
    [string]$SandboxUrl = 'rtsp://10.126.59.120:8554/live/live3'
)

$path = Join-Path $PSScriptRoot 'mediamtx.yml'
$raw = [IO.File]::ReadAllText($path)
if ($raw.Length -gt 0 -and [int][char]$raw[0] -eq 0xFEFF) {
    $raw = $raw.Substring(1)
}
$nl = if ($raw -match "`r`n") { "`r`n" } else { "`n" }
$lines = $raw -split "`r?`n"
$out = New-Object System.Collections.Generic.List[string]
$inCam = $false
$replaced = $false

foreach ($line in $lines) {
    if (-not $inCam -and $line -match '^  cam1:\s*$') {
        $inCam = $true
        $replaced = $true
        $out.Add('  cam1:')
        if ($Mode -eq 'publisher') {
            $out.Add('    source: publisher')
        } else {
            $out.Add("    source: $SandboxUrl")
            $out.Add('    rtspTransport: tcp')
            $out.Add('    sourceOnDemand: true')
            $out.Add('    sourceOnDemandStartTimeout: 30s')
        }
        continue
    }
    if ($inCam) {
        # End of cam1 block: next top-level indented path key or section
        if ($line -match '^  \S' -or $line -match '^[a-zA-Z]') {
            $inCam = $false
            $out.Add($line)
        }
        # else skip old cam1 body lines
        continue
    }
    $out.Add($line)
}

if (-not $replaced) {
    throw 'cam1 path not found in mediamtx.yml'
}

$text = ($out -join $nl)
if (-not $text.EndsWith($nl)) { $text += $nl }
$utf8 = New-Object System.Text.UTF8Encoding $false
[IO.File]::WriteAllText($path, $text, $utf8)
Write-Host "cam1 -> $Mode"
