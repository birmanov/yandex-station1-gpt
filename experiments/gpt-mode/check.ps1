# Compiles experiments/gpt-mode sources and tests for Java 7 and runs every *Check class on the PC JVM.
# No Android needed: classes that require Android are compiled but never instantiated here.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $candidate = Get-ChildItem 'C:\Program Files\Microsoft\jdk-17*' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($candidate) { $javaHome = $candidate.FullName }
}
if (-not $javaHome) { throw 'JDK 17 not found: set JAVA_HOME' }
$javac = Join-Path $javaHome 'bin\javac.exe'
$java = Join-Path $javaHome 'bin\java.exe'
$out = Join-Path $env:TEMP 'gpt-mode-classes'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

$sources = @(Get-ChildItem (Join-Path $here 'src') -Filter *.java | ForEach-Object { $_.FullName })
$tests = @(Get-ChildItem (Join-Path $here 'tests') -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw 'No sources in src/' }
& $javac --release 7 -Xlint:-options -encoding UTF-8 -d $out ($sources + $tests)
if ($LASTEXITCODE -ne 0) { Write-Host 'COMPILE FAILED'; exit 1 }

$failed = 0
foreach ($test in $tests) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($test)
    if (-not $name.EndsWith('Check')) { continue }
    & $java -cp $out $name
    if ($LASTEXITCODE -ne 0) { Write-Host "FAIL $name"; $failed = 1 } else { Write-Host "PASS $name" }
}
exit $failed
