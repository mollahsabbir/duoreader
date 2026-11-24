# Shared local build helpers. These functions never install or connect to a device.
function Assert-NativeCommand([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed ($LASTEXITCODE)" }
}

function Resolve-AndroidToolchain([string]$SdkRoot, [string]$JavaHome) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    if (!$SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
    if (!$SdkRoot) { $SdkRoot = Join-Path $projectRoot '.local\android-sdk' }
    if ($JavaHome) {
        $javaBin = Join-Path $JavaHome 'bin'
    } else {
        $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
        if (!$javac) { throw 'Set JAVA_HOME to a JDK 17 installation, or put its bin directory on PATH.' }
        $javaBin = Split-Path -Parent $javac.Source
    }
    $sdk = [IO.Path]::GetFullPath($SdkRoot)
    $buildTools = Join-Path $sdk 'build-tools\35.0.0'
    $androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
    $required = @($androidJar, "$buildTools\aapt2.exe", "$buildTools\zipalign.exe",
        "$buildTools\lib\d8.jar", "$buildTools\lib\apksigner.jar")
    $required += @('java.exe', 'javac.exe', 'jar.exe', 'keytool.exe') | ForEach-Object { Join-Path $javaBin $_ }
    foreach ($path in $required) {
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing build tool: $path" }
    }
    return [PSCustomObject]@{ SdkRoot = $sdk; BuildTools = $buildTools; AndroidJar = $androidJar; JavaBin = $javaBin }
}

function Reset-BuildDirectory([string]$ProjectRoot, [string]$Name) {
    $build = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'build'))
    $target = [IO.Path]::GetFullPath((Join-Path $build $Name))
    if (!$target.StartsWith($build + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Build output escaped the build directory'
    }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $target | Out-Null
}

function Sign-AndroidApk($Toolchain, [string]$ProjectRoot, [string]$InputApk, [string]$OutputApk) {
    $local = Join-Path $ProjectRoot '.local'
    New-Item -ItemType Directory -Force -Path $local | Out-Null
    $key = Join-Path $local 'duoreader-debug.keystore'
    if (!(Test-Path -LiteralPath $key)) {
        & "$($Toolchain.JavaBin)\keytool.exe" -genkeypair -keystore $key -storepass android -keypass android -alias duoreader -dname 'CN=Duo Reader Development' -keyalg RSA -keysize 2048 -validity 10000 -noprompt
        Assert-NativeCommand 'Create local signing key'
    }
    $aligned = "$InputApk.aligned"
    & "$($Toolchain.BuildTools)\zipalign.exe" -f -p 4 $InputApk $aligned
    Assert-NativeCommand 'Align APK'
    & "$($Toolchain.JavaBin)\java.exe" -jar "$($Toolchain.BuildTools)\lib\apksigner.jar" sign --ks $key --ks-key-alias duoreader --ks-pass pass:android --key-pass pass:android --out $OutputApk $aligned
    Assert-NativeCommand 'Sign APK'
    & "$($Toolchain.JavaBin)\java.exe" -jar "$($Toolchain.BuildTools)\lib\apksigner.jar" verify $OutputApk
    Assert-NativeCommand 'Verify APK signature'
}
