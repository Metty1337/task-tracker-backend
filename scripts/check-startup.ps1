[CmdletBinding()]
param(
    [ValidateRange(5, 600)]
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$appProcess = $null
$exitCode = 1
Push-Location $projectRoot
try {
    $javaPath = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        (Get-Command java -ErrorAction Stop).Source
    }
    if (-not (Test-Path -LiteralPath $javaPath)) {
        throw "Java executable not found: $javaPath"
    }
    # Java writes its version to stderr; capture it without PowerShell 5 treating it as a failure.
    $versionInfo = New-Object System.Diagnostics.ProcessStartInfo
    $versionInfo.FileName = $javaPath
    $versionInfo.Arguments = '-version'
    $versionInfo.UseShellExecute = $false
    $versionInfo.CreateNoWindow = $true
    $versionInfo.RedirectStandardError = $true
    $versionProcess = [System.Diagnostics.Process]::Start($versionInfo)
    try {
        $javaVersion = $versionProcess.StandardError.ReadToEnd()
        $versionProcess.WaitForExit()
        if ($versionProcess.ExitCode -ne 0 -or $javaVersion -notmatch 'version "25[."]') {
            throw 'Java 25 is required. Set JAVA_HOME to a Java 25 JDK.'
        }
    } finally {
        $versionProcess.Dispose()
    }

    $logDirectory = Join-Path $projectRoot ('build/startup-check/' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    Write-Host "Logs: $logDirectory"
    & .\gradlew.bat --console=plain bootJar *> (Join-Path $logDirectory 'build.log')
    if ($LASTEXITCODE -ne 0) {
        throw "bootJar failed. See $logDirectory/build.log"
    }
    $jars = @(Get-ChildItem -LiteralPath 'build/libs' -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' })
    if ($jars.Count -ne 1) {
        throw 'Expected exactly one executable JAR in build/libs. Remove stale build artifacts and retry.'
    }

    $stdout = Join-Path $logDirectory 'application.log'
    $stderr = Join-Path $logDirectory 'application-error.log'
    $appProcess = Start-Process -FilePath $javaPath -ArgumentList @(
        '-jar', ('"' + $jars[0].FullName + '"'), '--server.port=0', '--server.address=127.0.0.1',
        '--spring.output.ansi.enabled=never'
    ) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $appProcess.Refresh()
        if ($appProcess.HasExited) {
            throw "Application exited before startup verification completed. See $stdout and $stderr"
        }
        $startupLog = Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue
        if ($startupLog -match 'Started TaskTrackerBackendApplication' -and
            $startupLog -match 'Tomcat started on port (\d+)') {
            $port = [int]$Matches[1]
            $statusCode = 0
            try {
                $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 3 -MaximumRedirection 0
                $statusCode = [int]$response.StatusCode
            } catch {
                if ($null -ne $_.Exception.Response) {
                    $statusCode = [int]$_.Exception.Response.StatusCode
                }
            }
            $appProcess.Refresh()
            if (-not $appProcess.HasExited -and $statusCode -ge 200 -and $statusCode -lt 500) {
                Write-Host "PASS: Spring started and HTTP responded with $statusCode on port $port."
                $exitCode = 0
                break
            }
        }
        Start-Sleep -Milliseconds 500
    }
    if ($exitCode -ne 0) {
        throw "Startup/HTTP check timed out after $TimeoutSeconds seconds. See $stdout and $stderr"
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    if ($null -ne $appProcess) {
        $appProcess.Refresh()
        if (-not $appProcess.HasExited) {
            $appProcess.Kill()
            $appProcess.WaitForExit()
        }
        $appProcess.Dispose()
    }
    Pop-Location
}
exit $exitCode
