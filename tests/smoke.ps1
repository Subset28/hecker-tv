<#
.SYNOPSIS
  Smoke test for the tvhome launcher on the Android emulator (emulator-5554 only).

.DESCRIPTION
  1. Checks the emulator is connected and booted.
  2. Installs build\tvhome.apk (-r) and launches the launcher, waits 6s.
  3. Fails if logcat shows FATAL EXCEPTION / AndroidRuntime errors for the launcher.
  4. Sends DPAD_RIGHT and DPAD_DOWN, screenshots after each.
  5. Disables then re-enables com.google.android.youtube.tvmusic (re-enable always runs).
  6. Prints gfxinfo frame stats for the launcher.
  7. Prints a PASS/FAIL summary and screenshot paths. Exit code 1 if anything failed.

  The serial is fixed to emulator-5554. Every adb call goes through Invoke-Adb,
  which always passes -s emulator-5554, so the physical TV is never touched.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\tests\smoke.ps1
#>
param(
    [string]$AdbPath = (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'),
    [string]$ApkPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'build\tvhome.apk'),
    [string]$OutDir  = $env:TEMP
)

$ErrorActionPreference = 'Continue'

$Serial   = 'emulator-5554'
$Package  = 'com.hecking.tvlauncher'
$Activity = "$Package/.MainActivity"
$Target   = 'com.google.android.youtube.tvmusic'

$results = New-Object System.Collections.Generic.List[object]
$shots   = New-Object System.Collections.Generic.List[string]

function Add-Result {
    param([string]$Step, [bool]$Ok, [string]$Detail)
    $status = 'FAIL'
    if ($Ok) { $status = 'PASS' }
    $results.Add([pscustomobject]@{ Step = $Step; Status = $status; Detail = $Detail })
    Write-Host ("  -> {0}: {1}" -f $status, $Detail)
}

function Write-Header {
    param([string]$Text)
    Write-Host ''
    Write-Host "== $Text ==" -ForegroundColor Cyan
}

# Every adb call goes through here and is pinned to the emulator serial.
function Invoke-Adb {
    $out = & $AdbPath -s $Serial @args 2>&1 | ForEach-Object { "$_" }
    return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($out -join "`n") }
}

# Pull a screenshot from the emulator into $OutDir. Returns $true on success.
function Save-Shot {
    param([string]$FileName)
    $devicePath = '/sdcard/smoke-tmp.png'
    $localPath  = Join-Path $OutDir $FileName
    Invoke-Adb shell screencap -p $devicePath | Out-Null
    Invoke-Adb pull $devicePath $localPath | Out-Null
    Invoke-Adb shell rm -f $devicePath | Out-Null
    if ((Test-Path $localPath) -and ((Get-Item $localPath).Length -gt 0)) {
        $shots.Add($localPath)
        Write-Host "  screenshot: $localPath"
        return $true
    }
    Write-Host "  screenshot failed: $FileName" -ForegroundColor Yellow
    return $false
}

function Test-PackageDisabled {
    $list = (Invoke-Adb shell pm list packages -d).Text
    return ($list -match ('package:' + [regex]::Escape($Target) + '(\s|$)'))
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---- Step 1: device connected and booted -------------------------------------
Write-Header 'Step 1: device connected and booted'
$devices = (& $AdbPath devices 2>&1 | Out-String)
$connected = $devices -match ("(?m)^" + [regex]::Escape($Serial) + "\s+device\s*$")
if (-not $connected) {
    Add-Result '1. Device connected' $false "$Serial not listed as 'device' in adb devices"
    Write-Host $devices
    Write-Host 'Emulator is not reachable. Start it manually and re-run. Aborting.' -ForegroundColor Red
    $results | Format-Table -AutoSize | Out-String | Write-Host
    exit 1
}
Add-Result '1. Device connected' $true "$Serial is connected"

$boot = (Invoke-Adb shell getprop sys.boot_completed).Text.Trim()
Add-Result '1. Boot completed' ($boot -eq '1') "sys.boot_completed=$boot"

# ---- Step 2: install and launch ----------------------------------------------
Write-Header 'Step 2: install and launch'
if (-not (Test-Path $ApkPath)) {
    Add-Result '2. Install APK' $false "APK not found: $ApkPath"
} else {
    $inst = Invoke-Adb install -r $ApkPath
    Add-Result '2. Install APK' ($inst.Text -match 'Success') ("adb install -r: " + $inst.Text.Trim())
}

# logcat -c right before launch so step 3 only sees output since launch.
Invoke-Adb logcat -c | Out-Null
$start = Invoke-Adb shell am start -n $Activity
$launchOk = ($start.Code -eq 0) -and ($start.Text -notmatch 'Error|Exception')
Add-Result '2. Launch activity' $launchOk ("am start $Activity -> " + ($start.Text -replace "`n", ' ').Trim())
Write-Host '  waiting 6s...'
Start-Sleep -Seconds 6

# ---- Step 3: crash check -----------------------------------------------------
Write-Header 'Step 3: logcat crash check (since launch)'
$dump = (Invoke-Adb logcat -d).Text
$hits = @($dump -split "`n" | Where-Object { $_ -match 'FATAL EXCEPTION|AndroidRuntime' })
$crashed = ($hits.Count -gt 0) -and ($dump -match [regex]::Escape($Package))
if ($crashed) {
    $hits | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Add-Result '3. No crash in logcat' $false ("{0} FATAL/AndroidRuntime line(s) referencing $Package" -f $hits.Count)
} else {
    Add-Result '3. No crash in logcat' $true 'no FATAL EXCEPTION / AndroidRuntime for the launcher'
}

# ---- Step 4: D-pad navigation ------------------------------------------------
Write-Header 'Step 4: navigation (DPAD_RIGHT, DPAD_DOWN)'
$navShotsOk = $true
Invoke-Adb shell input keyevent KEYCODE_DPAD_RIGHT | Out-Null
Start-Sleep -Seconds 1
$navShotsOk = (Save-Shot 'smoke-1-dpad-right.png') -and $navShotsOk

Invoke-Adb shell input keyevent KEYCODE_DPAD_DOWN | Out-Null
Start-Sleep -Seconds 1
$navShotsOk = (Save-Shot 'smoke-2-dpad-down.png') -and $navShotsOk

$alive = (Invoke-Adb shell pidof $Package).Text.Trim()
Add-Result '4. Navigation' ($navShotsOk -and [bool]$alive) ("launcher pid='$alive', screenshots ok=$navShotsOk")

# ---- Step 5: disable / enable a package --------------------------------------
Write-Header "Step 5: disable and re-enable $Target"
try {
    $dis = Invoke-Adb shell pm disable-user --user 0 $Target
    Write-Host ("  pm disable-user: " + $dis.Text.Trim())
    Write-Host '  waiting 6s...'
    Start-Sleep -Seconds 6
    $isDisabled = Test-PackageDisabled
    Add-Result '5. Package disabled' $isDisabled ("in 'pm list packages -d': $isDisabled")
    $shotOk = Save-Shot 'smoke-3-youtube-disabled.png'
    Add-Result '5. Disabled screenshot' $shotOk 'smoke-3-youtube-disabled.png'
}
catch {
    Add-Result '5. Package disabled' $false "exception: $($_.Exception.Message)"
}
finally {
    $en = Invoke-Adb shell pm enable --user 0 $Target
    Write-Host ("  pm enable: " + $en.Text.Trim())
    Write-Host '  waiting 6s...'
    Start-Sleep -Seconds 6
    $stillDisabled = Test-PackageDisabled
    Add-Result '5. Package re-enabled' (-not $stillDisabled) ("still disabled after enable: $stillDisabled")
    $shotOk = Save-Shot 'smoke-4-youtube-enabled.png'
    Add-Result '5. Enabled screenshot' $shotOk 'smoke-4-youtube-enabled.png'
}

# ---- Step 6: frame stats -----------------------------------------------------
Write-Header 'Step 6: frame stats (gfxinfo)'
$gfx = (Invoke-Adb shell dumpsys gfxinfo $Package).Text -split "`n"
$statLines = @($gfx | Where-Object { $_ -match 'Total frames rendered|50th percentile|90th percentile|99th percentile' } | ForEach-Object { $_.Trim() })
$statLines | ForEach-Object { Write-Host "  $_" }
Add-Result '6. Frame stats' ($statLines.Count -ge 1) ("{0} stat line(s) parsed" -f $statLines.Count)

# ---- Summary -----------------------------------------------------------------
Write-Header 'Summary'
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host 'Screenshots:'
$shots | ForEach-Object { Write-Host "  $_" }

$failed = @($results | Where-Object { $_.Status -eq 'FAIL' }).Count
if ($failed -gt 0) {
    Write-Host "RESULT: FAIL ($failed step(s) failed)" -ForegroundColor Red
    exit 1
}
Write-Host 'RESULT: PASS' -ForegroundColor Green
exit 0
