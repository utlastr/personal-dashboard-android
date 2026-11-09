$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$release=Get-Content -LiteralPath "$project\output\update-manifest.json" -Raw | ConvertFrom-Json
$stage=Join-Path $project ('.local\source-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path "$stage\app","$stage\vendor" -Force | Out-Null
foreach($name in @('src','res','assets','AndroidManifest.xml')){
    Copy-Item -LiteralPath "$project\app\$name" -Destination "$stage\app" -Recurse
}
foreach($name in @('tools','tests','README.md','.gitignore')){
    Copy-Item -LiteralPath "$project\$name" -Destination $stage -Recurse
}
$build=Get-ChildItem -LiteralPath "$project\.local" -Directory -Filter 'build-*' |
    Where-Object { Test-Path -LiteralPath "$($_.FullName)\aligned\unsigned-aligned.apk" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(-not $build){throw 'Build the release before packaging its sources'}
Copy-Item -LiteralPath "$($build.FullName)\assets\dashboard" -Destination "$stage\app\assets" -Recurse -Force
Copy-Item -LiteralPath "$project\vendor\aw-android\LICENSE" -Destination "$stage\vendor\MPL-2.0.txt"
& git -C "$project\vendor\aw-android" archive --format=zip `
    "--output=$stage\vendor\ActivityWatch-Android-v0.14.3-source.zip" v0.14.3
if($LASTEXITCODE -ne 0){throw 'Upstream source archive failed'}
Compress-Archive -Path "$stage\*" -DestinationPath "$project\output\PersonalDashboard-$($release.versionName)-source.zip" -Force
Get-Item -LiteralPath "$project\output\PersonalDashboard-$($release.versionName)-source.zip" | Select-Object FullName,Length
