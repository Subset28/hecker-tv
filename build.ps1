# Builds TV Home into build\tvhome.apk using only the SDK and JDK inside TV Hecking.
$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$hecking = Split-Path -Parent $root
$javaHome = Join-Path $hecking 'jdk\jdk-17.0.20.1+1'
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = Join-Path $hecking 'android-sdk'
$jdk    = Join-Path $javaHome 'bin'
$sdk    = Join-Path $hecking 'android-sdk'
$bt     = Join-Path $sdk 'build-tools\34.0.0'
$jar    = Join-Path $sdk 'platforms\android-34\android.jar'
$build  = Join-Path $root 'build'
$classes = Join-Path $build 'classes'
$keyDir = Join-Path $hecking 'launcher-signing'   # dedicated launcher key, outside the repo
$key    = Join-Path $keyDir 'tvhome-release.keystore'
$keyPass = (Get-Content (Join-Path $keyDir 'keystore-password.txt') -Raw).Trim()

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes -Force | Out-Null

# 1. resources + manifest -> unsigned apk (no resources yet, manifest only)
$assets = Join-Path $root 'assets'
$assetArgs = @()
if (Test-Path $assets) { $assetArgs = @('-A', $assets) }
$resArgs = @()
$res = Join-Path $root 'res'
if (Test-Path $res) {
  & (Join-Path $bt 'aapt2.exe') compile --dir $res -o (Join-Path $build 'res.zip')
  if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
  $resArgs = @((Join-Path $build 'res.zip'))
}
& (Join-Path $bt 'aapt2.exe') link -o (Join-Path $build 'base.apk') --manifest (Join-Path $root 'AndroidManifest.xml') -I $jar --min-sdk-version 28 --target-sdk-version 34 @assetArgs @resArgs
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

# 2. java -> class files
$src = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& (Join-Path $jdk 'javac.exe') --release 8 -classpath $jar -d $classes $src
if ($LASTEXITCODE) { throw 'javac failed' }

# 3. class files -> classes.dex
$cls = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $bt 'd8.bat') --release --lib $jar --min-api 28 --output $build $cls
if ($LASTEXITCODE) { throw 'd8 failed' }

# 4. add classes.dex to the apk
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = Join-Path $build 'tvhome-unsigned.apk'
Copy-Item (Join-Path $build 'base.apk') $apk
$zip = [System.IO.Compression.ZipFile]::Open($apk, 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $build 'classes.dex'), 'classes.dex') | Out-Null
$zip.Dispose()
# resources.arsc must be stored and 4-byte aligned (Android 11+ rejects it otherwise)
$aligned = Join-Path $build 'tvhome-aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f 4 $apk $aligned
if ($LASTEXITCODE) { throw 'zipalign failed' }
$apk = $aligned

# 5. debug key (once) and sign
if (-not (Test-Path $key)) {
  throw 'signing key missing: expected launcher-signing\tvhome-release.keystore'
}
$out = Join-Path $build 'tvhome.apk'
& (Join-Path $bt 'apksigner.bat') sign --ks $key --ks-pass pass:$keyPass --key-pass pass:$keyPass --ks-key-alias tvhome --out $out $apk
if ($LASTEXITCODE) { throw 'apksigner failed' }
"Built: $out"
