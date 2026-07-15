param([string]$RtspUrl)
$path = Join-Path $PSScriptRoot 'mediamtx.yml'
$content = [IO.File]::ReadAllText($path)
if ($content.Length -gt 0 -and [int][char]$content[0] -eq 0xFEFF) {
    $content = $content.Substring(1)
}
$content = $content -replace 'source: rtsp://[^\r\n]+', "source: $RtspUrl"
$utf8 = New-Object System.Text.UTF8Encoding $false
[IO.File]::WriteAllText($path, $content, $utf8)
