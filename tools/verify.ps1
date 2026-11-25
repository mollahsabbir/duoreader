param(
    [string]$SdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Run,
    [string]$Serial,
    [string]$Adb,
    [string[]]$Suite
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\AndroidTools.ps1"
if ($Run -and !$Serial) { throw 'Device tests require an explicit -Serial and an already connected test device.' }
$toolchain = Resolve-AndroidToolchain -SdkRoot $SdkRoot -JavaHome $JavaHome
& "$projectRoot\build.ps1" -SdkRoot $toolchain.SdkRoot -JavaHome (Split-Path -Parent $toolchain.JavaBin) -Debug
$buildDir = Join-Path $projectRoot 'build'
Reset-BuildDirectory -ProjectRoot $projectRoot -Name 'test-classes'
Reset-BuildDirectory -ProjectRoot $projectRoot -Name 'test-dex'
& "$($toolchain.BuildTools)\aapt2.exe" link -o "$buildDir\tests-base.apk" -I $toolchain.AndroidJar --manifest "$projectRoot\tests\AndroidManifest.xml"
Assert-NativeCommand 'Link test APK'
$sources = @(Get-ChildItem -LiteralPath "$projectRoot\tests\java" -Filter '*.java' -Recurse | ForEach-Object FullName)
& "$($toolchain.JavaBin)\javac.exe" -encoding UTF-8 --release 8 -classpath "$($toolchain.AndroidJar);$buildDir\classes.jar" -d "$buildDir\test-classes" @sources
Assert-NativeCommand 'Compile tests'
& "$($toolchain.JavaBin)\jar.exe" cf "$buildDir\tests.jar" -C "$buildDir\test-classes" .
Assert-NativeCommand 'Package tests'
& "$($toolchain.JavaBin)\java.exe" -cp "$($toolchain.BuildTools)\lib\d8.jar" com.android.tools.r8.D8 --lib $toolchain.AndroidJar --lib "$buildDir\classes.jar" --min-api 28 --output "$buildDir\test-dex" "$buildDir\tests.jar"
Assert-NativeCommand 'Compile test dex'
& "$($toolchain.JavaBin)\jar.exe" uf "$buildDir\tests-base.apk" -C "$buildDir\test-dex" classes.dex
Assert-NativeCommand 'Package test dex'
Sign-AndroidApk -Toolchain $toolchain -ProjectRoot $projectRoot -InputApk "$buildDir\tests-base.apk" -OutputApk "$buildDir\tests.apk"
if (!$Run) {
    Write-Output 'App and test APKs compiled. No device access. Running tests requires -Run -Serial SERIAL.'
    return
}
if (!$Adb) {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    $Adb = if ($command) { $command.Source } else { Join-Path $toolchain.SdkRoot 'platform-tools\adb.exe' }
}
if (!(Test-Path -LiteralPath $Adb)) { throw 'Install Android platform-tools and supply -Adb, or put adb on PATH.' }
& $Adb -s $Serial install -r "$buildDir\Duo-Reader-debug.apk"
Assert-NativeCommand 'Install test build'
& $Adb -s $Serial install -r "$buildDir\tests.apk"
Assert-NativeCommand 'Install instrumentation'
$suites = @(
    @('DeviceTests','verification.json'), @('LibraryTests','library-verification.json'),
    @('TouchNavigationTests','touch-verification.json'), @('RobustnessTests','robustness-verification.json'),
    @('OrientationProbe','orientation-verification.json'), @('UiBehaviorTests','ui-behavior-verification.json'),
    @('DisplayTests','DisplayTests.json'), @('AlignmentTests','AlignmentTests.json'),
    @('NoteTests','NoteTests.json'), @('EditorTests','EditorTests.json'),
    @('StorageTests','StorageTests.json'), @('LibraryManagementTests','LibraryManagementTests.json'),
    @('ComicTests','ComicTests.json')
)
if ($Suite) {
    $known = @($suites | ForEach-Object { $_[0] })
    foreach ($name in $Suite) { if ($name -notin $known) { throw "Unknown suite: $name. Available: $($known -join ', ')" } }
    $suites = @($suites | Where-Object { $_[0] -in $Suite })
}
$results = Join-Path $buildDir 'test-results'
New-Item -ItemType Directory -Force -Path $results | Out-Null
foreach ($suite in $suites) {
    & $Adb -s $Serial shell am instrument -w "io.duoreader.tests/io.duoreader.$($suite[0])"
    Assert-NativeCommand "Run $($suite[0])"
    $report = & $Adb -s $Serial shell run-as io.duoreader cat "files/$($suite[1])"
    Assert-NativeCommand "Read $($suite[0]) result"
    $report | Set-Content -LiteralPath (Join-Path $results $suite[1]) -Encoding utf8
    if (!(($report -join "`n" | ConvertFrom-Json).passed)) { throw "$($suite[0]) failed; see $results" }
}
Write-Output 'All instrumentation suites passed.'
