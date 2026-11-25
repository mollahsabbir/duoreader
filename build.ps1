param(
    [string]$SdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Debug
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\tools\AndroidTools.ps1"
. "$PSScriptRoot\tools\Dependencies.ps1"
$dependencies = @(Resolve-ReaderDependencies -ProjectRoot $PSScriptRoot)
$toolchain = Resolve-AndroidToolchain -SdkRoot $SdkRoot -JavaHome $JavaHome
$buildDir = Join-Path $PSScriptRoot 'build'
foreach ($name in @('generated', 'classes', 'dex', 'attributions')) {
    Reset-BuildDirectory -ProjectRoot $PSScriptRoot -Name $name
}
New-Item -ItemType Directory -Force -Path "$PSScriptRoot\dist" | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot\LICENSE" -Destination "$buildDir\attributions\LICENSE.txt"
Copy-Item -LiteralPath "$PSScriptRoot\THIRD-PARTY-NOTICES.md" -Destination "$buildDir\attributions\THIRD-PARTY-NOTICES.txt"

& "$($toolchain.BuildTools)\aapt2.exe" compile --dir "$PSScriptRoot\app\src\main\res" -o "$buildDir\resources.zip"
Assert-NativeCommand 'Compile resources'
$link = @('link', '-o', "$buildDir\base.apk", '-I', $toolchain.AndroidJar,
    '--manifest', "$PSScriptRoot\app\src\main\AndroidManifest.xml", '--java', "$buildDir\generated",
    '-A', "$PSScriptRoot\app\src\main\assets", '-A', "$buildDir\attributions",
    '--auto-add-overlay', "$buildDir\resources.zip")
if ($Debug) { $link += '--debug-mode' }
& "$($toolchain.BuildTools)\aapt2.exe" @link
Assert-NativeCommand 'Link resources'
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot\app\src\main\java", "$buildDir\generated" -Filter '*.java' -Recurse | ForEach-Object FullName)
& "$($toolchain.JavaBin)\javac.exe" -encoding UTF-8 --release 8 -classpath ((@($toolchain.AndroidJar) + $dependencies) -join ';') -d "$buildDir\classes" @sources
Assert-NativeCommand 'Compile Java'
& "$($toolchain.JavaBin)\jar.exe" cf "$buildDir\classes.jar" -C "$buildDir\classes" .
Assert-NativeCommand 'Package classes'
& "$($toolchain.JavaBin)\java.exe" -cp "$($toolchain.BuildTools)\lib\d8.jar" com.android.tools.r8.D8 --lib $toolchain.AndroidJar --min-api 28 --output "$buildDir\dex" "$buildDir\classes.jar" @dependencies
Assert-NativeCommand 'Compile dex'
& "$($toolchain.JavaBin)\jar.exe" uf "$buildDir\base.apk" -C "$buildDir\dex" classes.dex
Assert-NativeCommand 'Package dex'
$output = if ($Debug) { "$buildDir\Duo-Reader-debug.apk" } else { "$PSScriptRoot\dist\Duo-Reader.apk" }
Sign-AndroidApk -Toolchain $toolchain -ProjectRoot $PSScriptRoot -InputApk "$buildDir\base.apk" -OutputApk $output
Get-Item -LiteralPath $output | Select-Object FullName, Length
