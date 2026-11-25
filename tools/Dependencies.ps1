function Resolve-ReaderDependencies {
    param([string]$ProjectRoot)
    $directory = Join-Path $ProjectRoot '.local\dependencies'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $dependencies = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json
    foreach ($dependency in $dependencies) {
        if ($dependency.file -notmatch '^[a-zA-Z0-9._-]+\.jar$') { throw 'Invalid dependency filename.' }
        $path = Join-Path $directory $dependency.file
        if (!(Test-Path -LiteralPath $path)) {
            $temporary = "$path.part"
            try {
                Invoke-WebRequest -Uri $dependency.url -OutFile $temporary
                if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $dependency.sha256) {
                    throw "Checksum mismatch: $($dependency.file)"
                }
                Move-Item -LiteralPath $temporary -Destination $path
            } finally {
                if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
            }
        }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $dependency.sha256) {
            throw "Checksum mismatch: $($dependency.file)"
        }
        Write-Output $path
    }
}
