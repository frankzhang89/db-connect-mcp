param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('start', 'stop', 'restart')]
    [string]$Action,

    [Parameter(Position = 1)]
    [string]$JarOrDirectory
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runDir = Join-Path $projectDir 'run'
$pidFile = Join-Path $runDir 'db-mcp.pid'
$jarPathFile = Join-Path $runDir 'db-mcp.jar-path'
$outLog = Join-Path $runDir 'db-mcp.out.log'
$errLog = Join-Path $runDir 'db-mcp.err.log'
$jarName = if ($env:DB_MCP_JAR_NAME) { $env:DB_MCP_JAR_NAME } else { 'db-mcp.jar' }

function Wait-BeforeExit {
    if ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected) { return }
    Write-Host 'Press any key to close, or wait 5 seconds...'
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ([Console]::KeyAvailable) {
                [Console]::ReadKey($true) | Out-Null
                return
            }
            Start-Sleep -Milliseconds 100
        }
    }
    catch { Start-Sleep -Seconds 5 }
}

function Get-ListeningConnections {
    param([int]$Port = 0, [int]$OwnerId = 0)
    # netstat is also available when Get-NetTCPConnection is unavailable.
    $connections = @(netstat.exe -ano 2>$null | ForEach-Object {
        if ($_ -match '^\s*TCP\s+(\S+):(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') {
            [PSCustomObject]@{ Port = [int]$Matches[2]; OwnerId = [int]$Matches[3] }
        }
    })
    $connections | Where-Object {
        ($Port -eq 0 -or $_.Port -eq $Port) -and ($OwnerId -eq 0 -or $_.OwnerId -eq $OwnerId)
    } | Sort-Object Port, OwnerId -Unique
}

function Show-PortConflict([int]$Port) {
    Write-Host "Port conflict: $Port"
    $listeners = @(Get-ListeningConnections -Port $Port)
    if ($listeners.Count -eq 0) {
        Write-Host 'Owner PID / program directory: unavailable (listener exited or access denied).'
    }
    foreach ($listener in $listeners) {
        Write-Host "Owner PID: $($listener.OwnerId)"
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwnerId)" -ErrorAction SilentlyContinue
        $directory = $null
        if ($owner -and $owner.ExecutablePath) {
            Write-Host "Executable: $($owner.ExecutablePath)"
            $directory = Split-Path -Parent $owner.ExecutablePath
        }
        if ($owner -and $owner.CommandLine -match '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))') {
            $ownerJar = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
            Write-Host "Application JAR: $ownerJar"
            if ([IO.Path]::IsPathRooted($ownerJar)) { $directory = Split-Path -Parent $ownerJar }
        }
        if (-not $directory) { $directory = 'unavailable (access denied or process exited)' }
        Write-Host "Program directory: $directory"
    }
}

function Show-StartupFailure {
    $text = ((Get-Content -LiteralPath $outLog -Raw -ErrorAction SilentlyContinue),
             (Get-Content -LiteralPath $errLog -Raw -ErrorAction SilentlyContinue)) -join "`n"
    $conflict = [regex]::Match($text, '(?i)Port\s+(\d+)\s+(?:was already|is already)\s+in use')
    if ($conflict.Success) { Show-PortConflict ([int]$conflict.Groups[1].Value) }
}

function Resolve-ManagedJar([string]$requested) {
    $candidate = $requested
    if (-not $candidate) { $candidate = $env:DB_MCP_JAR_PATH }
    if (-not $candidate) { $candidate = $env:DB_MCP_EXPORT_PATH }
    if (-not $candidate -and (Test-Path -LiteralPath $jarPathFile)) {
        $candidate = (Get-Content -LiteralPath $jarPathFile -Raw).Trim()
    }
    if (-not $candidate) { $candidate = Join-Path (Join-Path $projectDir 'target') $jarName }
    if (Test-Path -LiteralPath $candidate -PathType Container) {
        $candidate = Join-Path $candidate $jarName
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "JAR not found: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Get-ManagedProcess {
    if (-not (Test-Path -LiteralPath $pidFile)) { return $null }
    $savedPidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    $savedPid = 0
    if (-not [int]::TryParse($savedPidText, [ref]$savedPid)) { return $null }
    if (-not (Test-Path -LiteralPath $jarPathFile)) { return $null }
    $savedJar = (Get-Content -LiteralPath $jarPathFile -Raw).Trim()
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$savedPid" -ErrorAction SilentlyContinue
    if ($process -and $process.Name -eq 'java.exe' -and $process.CommandLine -and
            $process.CommandLine.IndexOf($savedJar, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        return $process
    }
    return $null
}

function Start-Mcp([string]$requested) {
    $running = Get-ManagedProcess
    if ($running) {
        $ports = @(Get-ListeningConnections -OwnerId $running.ProcessId | Select-Object -ExpandProperty Port)
        Write-Output "db-mcp is already running (PID $($running.ProcessId)); listening ports: $($ports -join ', ')."
        return
    }
    $jarPath = Resolve-ManagedJar $requested
    $timeout = 60
    if ($env:DB_MCP_STARTUP_TIMEOUT_SEC) {
        $timeout = [int]$env:DB_MCP_STARTUP_TIMEOUT_SEC
        if ($timeout -lt 1) { throw 'DB_MCP_STARTUP_TIMEOUT_SEC must be a positive integer.' }
    }
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else { (Get-Command java.exe -ErrorAction Stop).Source }
    $process = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jarPath + '"')) `
        -WorkingDirectory $projectDir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    Set-Content -LiteralPath $jarPathFile -Value $jarPath
    Set-Content -LiteralPath $pidFile -Value $process.Id
    $deadline = [DateTime]::UtcNow.AddSeconds($timeout)
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) {
            Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
            Show-StartupFailure
            throw "db-mcp failed to start. See $errLog and $outLog"
        }
        $startupLog = Get-Content -LiteralPath $outLog -Raw -ErrorAction SilentlyContinue
        if (-not $startupLog) {
            Start-Sleep -Milliseconds 500
            continue
        }
        $portMatch = [regex]::Match($startupLog, 'Tomcat started on port(?:\(s\))?:?\s+(\d+)')
        if ($portMatch.Success -and $startupLog -match 'Started \S+ in [\d.]+ seconds') {
            $port = [int]$portMatch.Groups[1].Value
            if (@(Get-ListeningConnections -Port $port -OwnerId $process.Id).Count -gt 0) {
                Write-Output "db-mcp started (PID $($process.Id)); service port: $port"
                Write-Output "Logs: $outLog, $errLog"
                return
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "db-mcp is not ready after $timeout seconds (PID $($process.Id) retained for stop/restart). See $outLog and $errLog"
}

function Stop-Mcp {
    $running = Get-ManagedProcess
    if (-not $running) {
        Write-Output 'db-mcp is not running.'
        return
    }
    Stop-Process -Id $running.ProcessId
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if (-not (Get-ManagedProcess)) { break }
        Start-Sleep -Seconds 1
    }
    if (Get-ManagedProcess) {
        throw "db-mcp did not stop within 20 seconds (PID $($running.ProcessId))."
    }
    Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
    Write-Output 'db-mcp stopped.'
}

$exitCode = 0
try {
    switch ($Action) {
        'start' { Start-Mcp $JarOrDirectory }
        'stop' { Stop-Mcp }
        'restart' {
            # Validate the replacement JAR before stopping a running service.
            $resolvedJar = Resolve-ManagedJar $JarOrDirectory
            if ($env:DB_MCP_STARTUP_TIMEOUT_SEC -and [int]$env:DB_MCP_STARTUP_TIMEOUT_SEC -lt 1) {
                throw 'DB_MCP_STARTUP_TIMEOUT_SEC must be a positive integer.'
            }
            Stop-Mcp
            Start-Mcp $resolvedJar
        }
    }
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
}
finally { Wait-BeforeExit }
exit $exitCode
