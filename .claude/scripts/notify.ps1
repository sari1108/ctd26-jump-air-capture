$projectDir = $env:CLAUDE_PROJECT_DIR

if ([string]::IsNullOrEmpty($projectDir)) {
    $projectDir = (Get-Location).Path
}

$path = Join-Path $projectDir '.claude\sounds\notify.mp3'

if (Test-Path $path) {
    Add-Type -AssemblyName PresentationCore

    $player = New-Object System.Windows.Media.MediaPlayer
    $player.Open([Uri]$path)

    Start-Sleep -Milliseconds 300

    $player.Play()

    Start-Sleep -Seconds 5

    $player.Stop()
    $player.Close()
}