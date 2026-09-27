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
        Write-Output "db-mcp is already running (PID $($running.ProcessId))."
        return
    }
    $jarPath = Resolve-ManagedJar $requested
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $process = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jarPath + '"')) `
        -WorkingDirectory $projectDir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    Set-Content -LiteralPath $jarPathFile -Value $jarPath
    Set-Content -LiteralPath $pidFile -Value $process.Id
    Start-Sleep -Seconds 3
    if (-not (Get-ManagedProcess)) {
        Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
        throw "db-mcp failed to start. See $errLog and $outLog"
    }
    Write-Output "db-mcp started (PID $($process.Id)); logs: $outLog, $errLog"
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

switch ($Action) {
    'start' { Start-Mcp $JarOrDirectory }
    'stop' { Stop-Mcp }
    'restart' { Stop-Mcp; Start-Mcp $JarOrDirectory }
}
