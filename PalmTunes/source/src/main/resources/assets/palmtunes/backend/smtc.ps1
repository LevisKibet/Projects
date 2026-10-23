# PalmTunes: Windows media-session bridge.
#
# Runs under Windows PowerShell 5.1 (powershell.exe), which can see the WinRT
# GlobalSystemMediaTransportControls API. PowerShell 7 cannot, so do not launch pwsh.
#
# Protocol: one request per line on stdin, exactly one JSON line answered on stdout.
#   poll <haveSeq>      -> state of the best session (art included unless <haveSeq> is current)
#   playpause|next|prev -> {"ok":true}
#   seek <milliseconds> -> {"ok":true}

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$null = [Windows.Media.MediaPlaybackAutoRepeatMode, Windows.Media, ContentType = WindowsRuntime]

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($operation, [Type]$resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $null = $task.Wait(-1)
    return $task.Result
}

$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

# Track identity bookkeeping so cover art is only shipped when the Java side lacks it.
$script:lastKey = ''
$script:seq = 0
$script:artB64 = ''
$script:artAttempts = 0
$script:artFinal = $false

function Select-Session {
    $playing = $null
    $first = $null
    foreach ($s in $manager.GetSessions()) {
        if ($null -eq $first) { $first = $s }
        $status = $s.GetPlaybackInfo().PlaybackStatus.ToString()
        if ($status -eq 'Playing' -and $null -eq $playing) { $playing = $s }
    }
    if ($null -ne $playing) { return $playing }
    $current = $manager.GetCurrentSession()
    if ($null -ne $current) { return $current }
    return $first
}

function Get-ArtBase64($thumbnail) {
    if ($null -eq $thumbnail) { return '' }
    try {
        $stream = Await ($thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
        $ms = New-Object System.IO.MemoryStream
        $net.CopyTo($ms)
        $net.Dispose()
        $stream.Dispose()
        if ($ms.Length -eq 0 -or $ms.Length -gt 6000000) { return '' }
        return [Convert]::ToBase64String($ms.ToArray())
    } catch {
        return ''
    }
}

function Write-Json($obj) {
    [Console]::Out.WriteLine((ConvertTo-Json -InputObject $obj -Compress -Depth 4))
    [Console]::Out.Flush()
}

function Get-State($session, $haveSeq) {
    $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    $info = $session.GetPlaybackInfo()
    $timeline = $session.GetTimelineProperties()
    $app = $session.SourceAppUserModelId
    $status = $info.PlaybackStatus.ToString()

    $key = "$($props.Title)|$($props.Artist)|$($props.AlbumTitle)|$app"
    if ($key -ne $script:lastKey) {
        $script:lastKey = $key
        $script:seq++
        $script:artB64 = ''
        $script:artAttempts = 0
        $script:artFinal = $false
    }

    # Players often publish the title before the thumbnail is ready, so retry a few polls.
    if (-not $script:artFinal) {
        $script:artB64 = Get-ArtBase64 $props.Thumbnail
        $script:artAttempts++
        if ($script:artB64.Length -gt 0 -or $script:artAttempts -ge 6) { $script:artFinal = $true }
    }

    $durationMs = -1
    $positionMs = -1
    try {
        $span = $timeline.EndTime - $timeline.StartTime
        if ($span.TotalMilliseconds -gt 0) { $durationMs = [int64]$span.TotalMilliseconds }
        $pos = ($timeline.Position - $timeline.StartTime).TotalMilliseconds
        if ($status -eq 'Playing') {
            $age = ([DateTime]::UtcNow - $timeline.LastUpdatedTime.UtcDateTime).TotalMilliseconds
            if ($age -gt 0 -and $age -lt 3600000) { $pos += $age }
        }
        if ($durationMs -gt 0 -and $pos -gt $durationMs) { $pos = $durationMs }
        if ($pos -ge 0) { $positionMs = [int64]$pos }
    } catch { }

    $shuffle = -1
    $repeat = -1
    try {
        if ($null -ne $info.IsShuffleActive) { $shuffle = $(if ($info.IsShuffleActive.Value) { 1 } else { 0 }) }
        if ($null -ne $info.AutoRepeatMode) {
            $repeat = switch ($info.AutoRepeatMode.Value.ToString()) { 'List' { 1 } 'Track' { 2 } default { 0 } }
        }
    } catch { }

    $sendArt = ($haveSeq -ne $script:seq) -and ($script:artB64.Length -gt 0)

    return [ordered]@{
        title      = [string]$props.Title
        artist     = [string]$props.Artist
        album      = [string]$props.AlbumTitle
        app        = [string]$app
        status     = $status
        positionMs = $positionMs
        durationMs = $durationMs
        shuffle    = $shuffle
        repeat     = $repeat
        seq        = $script:seq
        artFinal   = $script:artFinal
        art        = $(if ($sendArt) { $script:artB64 } else { '' })
    }
}

function Invoke-Command2($session, $command, $argument) {
    switch ($command) {
        'playpause' { $null = Await ($session.TryTogglePlayPauseAsync()) ([bool]) }
        'next'      { $null = Await ($session.TrySkipNextAsync()) ([bool]) }
        'prev'      { $null = Await ($session.TrySkipPreviousAsync()) ([bool]) }
        'shuffle'   {
            $cur = $session.GetPlaybackInfo().IsShuffleActive
            $on = ($null -ne $cur) -and $cur.Value
            $null = Await ($session.TryChangeShuffleActiveAsync(-not $on)) ([bool])
        }
        'repeat'    {
            $cur = $session.GetPlaybackInfo().AutoRepeatMode
            $name = $(if ($null -ne $cur) { $cur.Value.ToString() } else { 'None' })
            $next = switch ($name) { 'None' { 'List' } 'List' { 'Track' } default { 'None' } }
            $null = Await ($session.TryChangeAutoRepeatModeAsync([Windows.Media.MediaPlaybackAutoRepeatMode]$next)) ([bool])
        }
        'seek'      {
            $ticks = [int64]([double]$argument * 10000)
            $null = Await ($session.TryChangePlaybackPositionAsync($ticks)) ([bool])
        }
    }
}

Write-Json ([ordered]@{ ready = $true })

while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    $parts = $line.Trim().Split(' ')
    $command = $parts[0]
    $argument = if ($parts.Length -gt 1) { $parts[1] } else { '' }
    try {
        $session = Select-Session
        if ($null -eq $session) {
            Write-Json ([ordered]@{ none = $true })
        } elseif ($command -eq 'poll') {
            $have = 0
            [void][int]::TryParse($argument, [ref]$have)
            Write-Json (Get-State $session $have)
        } else {
            Invoke-Command2 $session $command $argument
            Write-Json ([ordered]@{ ok = $true })
        }
    } catch {
        Write-Json ([ordered]@{ error = $_.Exception.Message })
    }
}
