param([string]$NotesFile)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$repo='utlastr/personal-dashboard-android'
$manifest=Get-Content -LiteralPath "$project\output\update-manifest.json" -Raw | ConvertFrom-Json
$apk=Join-Path "$project\output" $manifest.apk
if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $manifest.sha256){throw 'APK hash differs from manifest'}
$env:GCM_INTERACTIVE='Never'
if(-not $env:GH_TOKEN) {
    $credentials="protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
    foreach($line in $credentials) {
        if($line.StartsWith('password=')) { $env:GH_TOKEN=$line.Substring(9) }
    }
    if(-not $env:GH_TOKEN) {throw 'Sign in with gh auth login before publishing'}
}
& "$PSScriptRoot\package-source.ps1"
if(-not(Test-Path -LiteralPath "$project\.git")) {
    & git -C $project init -b main
    $account=(& gh api user | ConvertFrom-Json)
    & git -C $project config user.name $account.login
    & git -C $project config user.email "$($account.id)+$($account.login)@users.noreply.github.com"
}
& git -C $project add app tools tests README.md .gitignore
& git -C $project diff --cached --quiet
if($LASTEXITCODE -ne 0) {
    & git -C $project commit -m "Release Android $($manifest.versionName) with GitHub self updates"
    if($LASTEXITCODE -ne 0){throw 'Source commit failed'}
}
& gh repo view $repo --json name 2>$null | Out-Null
if($LASTEXITCODE -ne 0) {
    & gh repo create $repo --public --description 'Personal Dashboard Android companion and signed APK releases'
    if($LASTEXITCODE -ne 0){throw 'Repository creation failed'}
}
$remote=& git -C $project remote get-url origin 2>$null
if(-not $remote){ & git -C $project remote add origin "https://github.com/$repo.git" }
elseif($remote -ne "https://github.com/$repo.git"){throw 'Unexpected origin; refusing to push'}
& git -C $project push -u origin main
if($LASTEXITCODE -ne 0){throw 'Source push failed'}
$tag="v$($manifest.versionName)"
& gh release view $tag --repo $repo 2>$null | Out-Null
if($LASTEXITCODE -eq 0){throw 'Release already exists; increment the version instead of replacing a released APK'}
$arguments=@('release','create',$tag,'--repo',$repo,'--target','main','--title',"Personal Dashboard $($manifest.versionName)",'--latest')
if($NotesFile){$arguments+=@('--notes-file',$NotesFile)}else{$arguments+=@('--generate-notes')}
$arguments+=@($apk,"$project\output\update-manifest.json","$project\output\PersonalDashboard-$($manifest.versionName)-source.zip")
& gh @arguments
if($LASTEXITCODE -ne 0){throw 'Release publication failed'}
