$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if ($env:JAVA_HOME) {
        if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
            throw 'JAVA_HOME must point to a JDK, not a JRE.'
        }
        $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
    }
    if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { throw 'Install Apache Maven 3.9+ and add it to PATH.' }
    if (-not (Get-Command javac.exe -ErrorAction SilentlyContinue)) { throw 'Install JDK 25 and set JAVA_HOME to its folder.' }
    & mvn.cmd --version
    if ($LASTEXITCODE -ne 0) { throw 'Maven could not start.' }
    & mvn.cmd --batch-mode --no-transfer-progress clean package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed. Check the Java version shown by Maven above.' }
    $jar = Join-Path $PSScriptRoot 'target\VanillaWeaponsUpgrade-1.1.0.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Expected plugin JAR was not produced.' }
    Write-Host "Built: $jar" -ForegroundColor Green
} finally {
    Pop-Location
}
