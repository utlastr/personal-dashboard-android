param([int]$TestVersionCode=0)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$app = Join-Path $project 'app'
$work = Join-Path $project ('.local\build-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
$out = Join-Path $project 'output'
[xml]$manifest = Get-Content -LiteralPath "$app\AndroidManifest.xml"
$versionName = $manifest.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
$versionCode = [int]$manifest.manifest.GetAttribute('versionCode','http://schemas.android.com/apk/res/android')
if($TestVersionCode -gt 0) {
    $versionCode=$TestVersionCode
    $versionName="$versionName-test$TestVersionCode"
    $out=Join-Path $project ".local\test-release-$TestVersionCode"
}
$jdk = 'D:\Users\KLEI\tmp\keep-analysis-tools\jdk21\jdk-21.0.12.1+1'
$platform = 'D:\Users\KLEI\tmp\keep-analysis-tools\platform35\android-35\android.jar'
$buildTools = 'D:\Users\KLEI\tmp\keep-analysis-tools\build-tools35\android-15'
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$keytool = Join-Path $jdk 'bin\keytool.exe'
$env:JAVA_HOME = $jdk
$env:Path = "$($jdk)\bin;$env:Path"

New-Item -ItemType Directory -Path "$work\classes","$work\dex","$work\res","$work\aligned","$out" -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $app 'assets') -Destination "$work\assets" -Recurse -Force
$dashboard = 'D:\Users\KLEI\Projects\PC\personal-dashboard'
$assetRoot = Join-Path $work 'assets\dashboard'
if (Test-Path -LiteralPath $dashboard) {
foreach ($folder in @('js','css','fonts','health-plus','stopwatch')) {
    Copy-Item -LiteralPath (Join-Path $dashboard $folder) -Destination $assetRoot -Recurse -Force
}
foreach ($name in @('index.html','dark.css','logo.png','manifest.json',
    'activity-performance.js','activity-privacy.js','activity-privacy.css',
    'icon-client.js','browser-public-suffix.js','browser-url-identity.js')) {
    Copy-Item -LiteralPath (Join-Path $dashboard $name) -Destination $assetRoot -Force
}
}
if (-not (Test-Path -LiteralPath "$assetRoot\index.html")) { throw 'Personal Dashboard frontend assets are required' }
$license=Join-Path $project 'vendor\aw-android\LICENSE'
if (-not(Test-Path -LiteralPath $license)) { $license=Join-Path $project 'vendor\MPL-2.0.txt' }
Copy-Item -LiteralPath $license `
    -Destination (Join-Path $assetRoot 'MPL-2.0.txt') -Force
# Bind the desktop build's local API address to this app's own loopback server.
foreach ($asset in Get-ChildItem -LiteralPath $assetRoot -Recurse -File |
    Where-Object { $_.Extension -in @('.js','.html') }) {
    $content = [IO.File]::ReadAllText($asset.FullName)
    $content = $content.Replace('http://127.0.0.1:5611','http://127.0.0.1:5667')
    [IO.File]::WriteAllText($asset.FullName,$content,[Text.UTF8Encoding]::new($false))
}

$sourceRoot=Join-Path $app 'src'
$manifestPath=Join-Path $app 'AndroidManifest.xml'
if($TestVersionCode -gt 0) {
    Copy-Item -LiteralPath $sourceRoot -Destination "$work\src" -Recurse
    $sourceRoot="$work\src"
    $updateSource=Join-Path $sourceRoot 'local\personaldashboard\mobile\Updates.java'
    $content=[IO.File]::ReadAllText($updateSource)
    $content=$content -replace 'static final int VERSION=\d+;', "static final int VERSION=$versionCode;"
    $content=$content -replace 'static final String NAME="[^"]+";', "static final String NAME=`"$versionName`";"
    [IO.File]::WriteAllText($updateSource,$content,[Text.UTF8Encoding]::new($false))
    $manifest.manifest.SetAttribute('versionCode','http://schemas.android.com/apk/res/android',"$versionCode")
    $manifest.manifest.SetAttribute('versionName','http://schemas.android.com/apk/res/android',$versionName)
    $manifestPath="$work\AndroidManifest.xml"
    $manifest.Save($manifestPath)
}
$sources = Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $platform -d "$work\classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

& $d8 --min-api 28 --lib $platform --output "$work\dex" (Get-ChildItem -LiteralPath "$work\classes" -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

& $aapt2 compile --dir (Join-Path $app 'res') -o "$work\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& $aapt2 link -o "$work\unsigned.apk" -I $platform --manifest $manifestPath --java "$work\generated" "$work\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

& $jar uf "$work\unsigned.apk" -C "$work\dex" classes.dex
& $jar uf "$work\unsigned.apk" -C "$work" assets
foreach ($abi in @('arm64-v8a','x86_64')) {
    New-Item -ItemType Directory -Path "$work\lib\$abi" -Force | Out-Null
    Copy-Item -LiteralPath "$app\lib\$abi\libaw_server.so" -Destination "$work\lib\$abi\libaw_server.so"
}
& $jar uf "$work\unsigned.apk" -C "$work" lib
if ($LASTEXITCODE -ne 0) { throw 'APK content assembly failed' }

$keystore = Join-Path $project '.local\personal-dashboard-release.jks'
if (-not (Test-Path -LiteralPath $keystore)) {
    & $keytool -genkeypair -keystore $keystore -storepass 'personal-dashboard-local' `
        -keypass 'personal-dashboard-local' -alias personal-dashboard -keyalg RSA `
        -keysize 2048 -validity 10000 -dname 'CN=Personal Dashboard, OU=Local, O=KLEI, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
$unsignedAligned = Join-Path $work 'aligned\unsigned-aligned.apk'
$final = Join-Path $out "PersonalDashboard-$versionName.apk"
& $zipalign -f 4 "$work\unsigned.apk" $unsignedAligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
& $apksigner sign --ks $keystore --ks-pass pass:personal-dashboard-local `
    --key-pass pass:personal-dashboard-local --ks-key-alias personal-dashboard `
    --out $final $unsignedAligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& $apksigner verify --verbose $final
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
Get-Item -LiteralPath $final | Select-Object FullName,Length
[ordered]@{
    versionCode=$versionCode
    versionName=$versionName
    packageName='local.personaldashboard.mobile'
    apk=(Split-Path -Leaf $final)
    sha256=(Get-FileHash -LiteralPath $final -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'update-manifest.json') -Encoding utf8
