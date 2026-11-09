$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
$hashes=@{
    'arm64-v8a'='c95210f8b7cceff6951052f03a6a64e11687aff44a7298e00940670598c279d3'
    'x86_64'='1046ab75eaed0b1ad032f9ba2cd4e47c95a367167b289eff2daadd58510a1412'
}
New-Item -ItemType Directory -Path "$project\vendor\releases" -Force | Out-Null
foreach($abi in @('arm64-v8a','x86_64')){
    $apk="$project\vendor\releases\aw-android-$abi.apk"
    if(-not(Test-Path -LiteralPath $apk)){
        Invoke-WebRequest -Uri "https://github.com/ActivityWatch/aw-android/releases/download/v0.14.3/aw-android-$abi.apk" -OutFile $apk
    }
    if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hashes[$abi]){
        throw "Upstream release hash mismatch: $abi"
    }
    New-Item -ItemType Directory -Path "$project\app\lib\$abi" -Force | Out-Null
    $archive=[IO.Compression.ZipFile]::OpenRead($apk)
    try{
        $entry=$archive.GetEntry("lib/$abi/libaw_server.so")
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,"$project\app\lib\$abi\libaw_server.so",$true)
    }finally{$archive.Dispose()}
}
