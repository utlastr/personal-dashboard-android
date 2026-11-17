$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$work=Join-Path $project '.local\limit-fixtures'
$jdk='D:\Users\KLEI\tmp\keep-analysis-tools\jdk21\jdk-21.0.12.1+1'
$bt='D:\Users\KLEI\tmp\keep-analysis-tools\build-tools35\android-15'
$platform='D:\Users\KLEI\tmp\keep-analysis-tools\platform35\android-35\android.jar'
$env:JAVA_HOME=$jdk
$env:Path="$jdk\bin;$env:Path"
New-Item -ItemType Directory -Path "$work\classes","$work\dex" -Force | Out-Null
& "$jdk\bin\javac.exe" -source 8 -target 8 -classpath $platform -d "$work\classes" "$project\tests\fixture\LimitActivity.java"
if($LASTEXITCODE -ne 0){throw 'Fixture compilation failed'}
& "$bt\d8.bat" --min-api 28 --lib $platform --output "$work\dex" "$work\classes\fixture\LimitActivity.class"
if($LASTEXITCODE -ne 0){throw 'Fixture dex failed'}
foreach($package in @('tv.danmaku.bili','com.ss.android.ugc.aweme')) {
    [xml]$manifest=Get-Content "$project\tests\fixture\AndroidManifest.xml"
    $manifest.manifest.SetAttribute('package',$package)
    $manifest.Save("$work\manifest.xml")
    & "$bt\aapt2.exe" link -I $platform --manifest "$work\manifest.xml" -o "$work\$package.apk"
    & "$jdk\bin\jar.exe" uf "$work\$package.apk" -C "$work\dex" classes.dex
    & "$bt\zipalign.exe" -f 4 "$work\$package.apk" "$work\$package-aligned.apk"
    & "$bt\apksigner.bat" sign --ks "$project\.local\personal-dashboard-release.jks" --ks-pass pass:personal-dashboard-local --ks-key-alias personal-dashboard --out "$work\$package-signed.apk" "$work\$package-aligned.apk"
    if($LASTEXITCODE -ne 0){throw 'Fixture sign failed'}
    & 'D:\Users\KLEI\tmp\keep-analysis-tools\android-sdk\platform-tools\adb.exe' -s emulator-5592 install -r "$work\$package-signed.apk"
    if($LASTEXITCODE -ne 0){throw 'Fixture install failed'}
}
