# Called by the project Codex Stop hook. Keep stdout valid hook JSON.
$ErrorActionPreference = 'Stop'
$icon = $null
try {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $icon = New-Object System.Windows.Forms.NotifyIcon
    $icon.Icon = [System.Drawing.SystemIcons]::Information
    $icon.Text = 'Codex - task-tracker-backend'
    $icon.BalloonTipTitle = 'Codex finished'
    $icon.BalloonTipText = 'task-tracker-backend: the response is ready. Open IntelliJ IDEA to review it.'
    $icon.BalloonTipIcon = [System.Windows.Forms.ToolTipIcon]::Info
    $icon.Visible = $true
    $icon.ShowBalloonTip(5000)
    # Keep the tray icon and its event loop alive long enough to display the notification.
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 6) {
        [System.Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 100
    }
    [Console]::Out.WriteLine('{}')
} catch {
    $result = @{ systemMessage = ('Windows notification failed: ' + $_.Exception.Message) }
    [Console]::Out.WriteLine(($result | ConvertTo-Json -Compress))
} finally {
    if ($null -ne $icon) {
        $icon.Visible = $false
        $icon.Dispose()
    }
}
exit 0
