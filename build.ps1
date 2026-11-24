param(
    [string]$ServerJar,
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
if (-not $ServerJar) {
    $hytaleDirectory = if ($env:HYTALE_HOME) { $env:HYTALE_HOME } else { 'D:/Games/Hytale' }
    $ServerJar = Join-Path $hytaleDirectory 'install/release/package/game/latest/Server/HytaleServer.jar'
}

$sdk = (Resolve-Path -LiteralPath $ServerJar).Path
$resources = Join-Path $PSScriptRoot 'src/main/resources'
$manifest = Get-Content -LiteralPath (Join-Path $resources 'manifest.json') -Raw | ConvertFrom-Json
$build = Join-Path $PSScriptRoot 'build'
$dist = Join-Path $PSScriptRoot 'dist'
$classes = Join-Path $build ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $classes, $dist -Force | Out-Null

try {
    $sources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java') -Recurse -File -Filter '*.java'
    & javac --release 25 -encoding UTF-8 -classpath $sdk -d $classes $sources.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

    if ($Test) {
        $testClasses = Join-Path $classes 'tests'
        New-Item -ItemType Directory -Path $testClasses | Out-Null
        $testSources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java') -Recurse -File -Filter '*.java'
        $mainClasspath = $classes + [IO.Path]::PathSeparator + $sdk
        & javac --release 25 -encoding UTF-8 -classpath $mainClasspath -d $testClasses $testSources.FullName
        if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
        $testClasspath = $testClasses + [IO.Path]::PathSeparator + $mainClasspath
        & java -classpath $testClasspath br.pedro.hytale.naturaldisasters.BlizzardTest
        if ($LASTEXITCODE -ne 0) { throw 'Blizzard tests failed.' }
        & java -classpath $testClasspath br.pedro.hytale.naturaldisasters.EarthquakeTest
        if ($LASTEXITCODE -ne 0) { throw 'Earthquake tests failed.' }
    }

    $output = Join-Path $dist "NaturalDisasters-$($manifest.Version).jar"
    & jar --create --file $output -C $classes br -C $resources .
    if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
    Write-Output $output
} finally {
    $resolvedBuild = (Resolve-Path -LiteralPath $build).Path
    $resolvedClasses = (Resolve-Path -LiteralPath $classes).Path
    if ((Split-Path -Parent $resolvedClasses) -eq $resolvedBuild) {
        Remove-Item -LiteralPath $resolvedClasses -Recurse -Force
    }
}
