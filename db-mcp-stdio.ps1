# JDK home directory (absolute path), or the name of a JDK home environment variable.
# Default: JAVA_HOME; if JAVA_HOME is empty/unset, use java.exe from PATH.
$jdkHome = 'JAVA_HOME'
$projectDir = $PSScriptRoot
# Configure the JAR here. Prefer a deployed sibling JAR, then the local build output.
$jarPath = Join-Path $projectDir 'db-mcp.jar'
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    $jarPath = Join-Path $projectDir 'target/db-mcp/db-mcp.jar'
}
$jvmOptions = @('-Xms64m', '-Xmx512m')
$springOptions = @(
    '--spring.ai.mcp.server.stdio=true'
    '--spring.ai.mcp.server.protocol=STREAMABLE'
    '--spring.main.web-application-type=none'
    '--spring.main.banner-mode=off'
    '--logging.config=classpath:logback-stdio.xml'
)

$ErrorActionPreference = 'Stop'
try {
    if ($jdkHome -match '^(?:[A-Za-z]:[\\/]|\\\\)') {
        $jdkDirectory = $jdkHome
    }
    elseif ($jdkHome -match '^[A-Za-z_][A-Za-z0-9_]*$') {
        $jdkDirectory = [Environment]::GetEnvironmentVariable($jdkHome)
        if ([string]::IsNullOrWhiteSpace($jdkDirectory) -and $jdkHome -ne 'JAVA_HOME') {
            throw "JDK environment variable is empty or unset: $jdkHome"
        }
    }
    else { throw 'jdkHome must be an absolute JDK home path or an environment variable name.' }

    if ([string]::IsNullOrWhiteSpace($jdkDirectory)) {
        $java = (Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    }
    else {
        $java = Join-Path $jdkDirectory 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
            throw "Java executable not found: $java"
        }
    }
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "JAR not found: $jarPath; build the project or configure jarPath in this script."
    }

    # Quote Windows native arguments, including embedded quotes and trailing backslashes.
    $nativeArguments = @($jvmOptions) + @('-jar', $jarPath) + @($springOptions)
    $quotedArguments = foreach ($argument in $nativeArguments) {
        '"' + ([regex]::Replace([string]$argument, '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
    }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $java
    $startInfo.Arguments = $quotedArguments -join ' '
    $startInfo.WorkingDirectory = $projectDir
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    # Copy raw bytes so Windows PowerShell never decodes/re-encodes MCP messages.
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    try {
        $inputCopy = [Console]::OpenStandardInput().CopyToAsync($process.StandardInput.BaseStream)
        $outputCopy = $process.StandardOutput.BaseStream.CopyToAsync([Console]::OpenStandardOutput())
        $errorCopy = $process.StandardError.BaseStream.CopyToAsync([Console]::OpenStandardError())
        $inputClosed = $false
        while (-not $process.WaitForExit(50)) {
            if ($inputCopy.IsCompleted -and -not $inputClosed) {
                $process.StandardInput.Close()
                $inputClosed = $true
            }
        }
        [void]$outputCopy.GetAwaiter().GetResult()
        [void]$errorCopy.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
    }
    finally {
        if (-not $process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
    exit $exitCode
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
