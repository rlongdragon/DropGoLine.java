param(
    [string]$Version = "1.0.0",
    [switch]$RunTests,
    [switch]$Installer
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    $scriptDir = Split-Path -Parent $PSCommandPath
    return (Resolve-Path (Join-Path $scriptDir "..")).Path
}

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    & $Action
}

function Test-Command {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function ConvertTo-JPackageAppVersion {
    param([string]$Value)

    $candidate = ($Value -split "[-+]")[0]
    if ($candidate -notmatch "^\d+(\.\d+){0,2}$") {
        return "1.0.0"
    }

    $parts = [System.Collections.Generic.List[string]]::new()
    $candidate.Split(".") | ForEach-Object { $parts.Add(([int]$_).ToString()) }
    while ($parts.Count -lt 3) {
        $parts.Add("0")
    }

    if ([int]$parts[0] -lt 1) {
        $parts[0] = "1"
    }

    return ($parts | Select-Object -First 3) -join "."
}

function New-AppImageZip {
    param(
        [string]$SourceDir,
        [string]$DestinationPath
    )

    $sourcePath = (Resolve-Path $SourceDir).Path
    $destinationFullPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $DestinationPath))
    $sourceParent = Split-Path -Parent $sourcePath
    $sourceName = Split-Path -Leaf $sourcePath

    if (Test-Path -LiteralPath $destinationFullPath) {
        Remove-Item -LiteralPath $destinationFullPath -Force
    }

    $tar = Get-Command "tar" -ErrorAction SilentlyContinue
    if ($tar) {
        Push-Location $sourceParent
        try {
            & $tar.Source -a -cf $destinationFullPath $sourceName
            if ($LASTEXITCODE -ne 0) {
                throw "tar failed to create archive '$destinationFullPath' (exit code $LASTEXITCODE)."
            }
        } finally {
            Pop-Location
        }
        return
    }

    Compress-Archive -LiteralPath $sourcePath -DestinationPath $destinationFullPath -Force
}

$repoRoot = Resolve-RepoRoot
$p2pDir = Join-Path $repoRoot "p2p-sample"
$uiDir = Join-Path $repoRoot "ui"
$localM2 = Join-Path $repoRoot "temp\m2"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

$testFlag = if ($RunTests) { "" } else { "-Dmaven.test.skip=true" }
$mavenRepoArg = "-Dmaven.repo.local=$localM2"
$jpackageAppVersion = ConvertTo-JPackageAppVersion $Version

Test-Command "mvn"
Test-Command "jpackage"

Invoke-Step "Build and install p2p library" {
    Push-Location $p2pDir
    try {
        $args = @($mavenRepoArg)
        if ($testFlag) { $args += $testFlag }
        $args += "install"
        & mvn @args
    } finally {
        Pop-Location
    }
}

Invoke-Step "Build DropGoLine UI jar" {
    Push-Location $uiDir
    try {
        $args = @($mavenRepoArg)
        if ($testFlag) { $args += $testFlag }
        $args += "package"
        & mvn @args
    } finally {
        Pop-Location
    }
}

Invoke-Step "Copy runtime dependencies" {
    Push-Location $uiDir
    try {
        $args = @(
            $mavenRepoArg,
            "dependency:copy-dependencies",
            "-DincludeScope=runtime",
            "-DoutputDirectory=target\dependency"
        )
        & mvn @args
    } finally {
        Pop-Location
    }
}

Invoke-Step "Create Windows app image with bundled runtime" {
    Push-Location $uiDir
    try {
        $jarName = "dropgoline-ui-0.1.0-SNAPSHOT.jar"
        $stageDir = Join-Path "target" "jpackage-input-$timestamp"
        $stageDependencyDir = Join-Path $stageDir "dependency"
        $destDir = Join-Path "target" "jpackage-$timestamp"
        $appImageDir = Join-Path $destDir "DropGoLine"

        New-Item -ItemType Directory -Force $stageDependencyDir | Out-Null
        Copy-Item -LiteralPath (Join-Path "target" $jarName) -Destination $stageDir -Force
        Copy-Item -Path "target\dependency\*.jar" -Destination $stageDependencyDir -Force

        & jpackage `
            --type app-image `
            --name DropGoLine `
            --app-version $jpackageAppVersion `
            --input $stageDir `
            --main-jar $jarName `
            --main-class dropgoline.Launcher `
            --dest $destDir `
            --win-console

        $debugCmdPath = Join-Path $appImageDir "DropGoLine-debug.cmd"
        $debugCmd = @(
            "@echo off",
            "cd /d ""%~dp0""",
            "echo Starting DropGoLine debug launcher...",
            "echo.",
            "DropGoLine.exe",
            "set EXITCODE=%ERRORLEVEL%",
            "echo.",
            "echo DropGoLine exited with code %EXITCODE%",
            "pause",
            "exit /b %EXITCODE%"
        )
        Set-Content -LiteralPath $debugCmdPath -Value $debugCmd -Encoding ASCII

        $zipPath = Join-Path "target" "DropGoLine-win11-runtime-$Version.zip"
        New-AppImageZip -SourceDir $appImageDir -DestinationPath $zipPath

        Write-Host ""
        Write-Host "App image: $((Resolve-Path $appImageDir).Path)" -ForegroundColor Green
        Write-Host "Zip:       $((Resolve-Path $zipPath).Path)" -ForegroundColor Green

        if ($Installer) {
            Write-Host ""
            Write-Host "==> Create Windows installer" -ForegroundColor Cyan
            $installerDir = Join-Path "target" "installer"
            New-Item -ItemType Directory -Force $installerDir | Out-Null
            & jpackage `
                --type exe `
                --name DropGoLine `
                --app-version $jpackageAppVersion `
                --app-image $appImageDir `
                --dest $installerDir `
                --win-menu `
                --win-shortcut `
                --win-console

            Get-ChildItem -Path $installerDir -Filter "*.exe" | ForEach-Object {
                Write-Host "Installer: $($_.FullName)" -ForegroundColor Green
            }
        }
    } finally {
        Pop-Location
    }
}
