param([switch]$SelfUpdate,[switch]$GitHub)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$work=Join-Path $project ('.local\test-build-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
$jdk='D:\Users\KLEI\tmp\keep-analysis-tools\jdk21\jdk-21.0.12.1+1'
$bt='D:\Users\KLEI\tmp\keep-analysis-tools\build-tools35\android-15'
$platform='D:\Users\KLEI\tmp\keep-analysis-tools\platform35\android-35\android.jar'
$env:JAVA_HOME=$jdk
$env:Path="$jdk\bin;$env:Path"
New-Item -ItemType Directory -Path "$work\classes","$work\dex","$work\assets" -Force | Out-Null
$snapshot=Get-ChildItem "$project\.local\phone-audit" -Filter 'aw-watcher-android_*.json' -ErrorAction SilentlyContinue | Select-Object -First 1
if($snapshot){Copy-Item -LiteralPath $snapshot.FullName -Destination "$work\assets\phone-events.json"}
$sources=Get-ChildItem "$project\app\src" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $platform -d "$work\classes" $sources "$project\tests\RuntimeTests.java"
if($LASTEXITCODE -ne 0){throw 'test javac failed'}
$testClasses=Get-ChildItem "$work\classes\local\personaldashboard\mobile" -Filter 'RuntimeTests*.class' | Select-Object -ExpandProperty FullName
& "$bt\d8.bat" --min-api 28 --lib $platform --output "$work\dex" $testClasses
if($LASTEXITCODE -ne 0){throw 'test d8 failed'}
& "$bt\aapt2.exe" link -I $platform --manifest "$project\tests\AndroidManifest.xml" -A "$work\assets" -o "$work\test.apk"
& "$jdk\bin\jar.exe" uf "$work\test.apk" -C "$work\dex" classes.dex
& "$bt\zipalign.exe" -f 4 "$work\test.apk" "$work\aligned.apk"
& "$bt\apksigner.bat" sign --ks "$project\.local\personal-dashboard-release.jks" --ks-pass pass:personal-dashboard-local --ks-key-alias personal-dashboard --out "$work\signed.apk" "$work\aligned.apk"
if($LASTEXITCODE -ne 0){throw 'test sign failed'}
$adb='D:\Users\KLEI\tmp\keep-analysis-tools\android-sdk\platform-tools\adb.exe'
& $adb -s emulator-5592 install -r "$work\signed.apk"
$argsList=@('-s','emulator-5592','shell','am','instrument','-w')
if($SelfUpdate){$argsList+=@('-e','selfUpdate','true')}
if($GitHub){$argsList+=@('-e','github','true')}
$argsList+='local.personaldashboard.mobile.tests/local.personaldashboard.mobile.RuntimeTests'
$testOutput=& $adb @argsList
$testOutput
if($LASTEXITCODE -ne 0 -or ($testOutput -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Android runtime tests failed'}
