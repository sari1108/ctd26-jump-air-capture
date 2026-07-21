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

    $waited = 0
    while (-not $player.NaturalDuration.HasTimeSpan -and $waited -lt 2000) {
        Start-Sleep -Milliseconds 100
        $waited += 100
    }
    if ($player.NaturalDuration.HasTimeSpan) {
        $clipMs = [int]$player.NaturalDuration.TimeSpan.TotalMilliseconds
    } else {
        $clipMs = 1500
    }

    for ($i = 0; $i -lt 3; $i++) {
        $player.Position = [TimeSpan]::Zero
        $player.Play()
        Start-Sleep -Milliseconds ($clipMs + 200)
    }

    $player.Stop()
    $player.Close()
}
