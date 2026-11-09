$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$python = 'D:\Users\KLEI\AppData\Local\Programs\Python\Python310\pythonw.exe'
$script = Join-Path $PSScriptRoot 'mobile_service.py'
$log = Join-Path $project '.local\mobile-service\service.log'
New-Item -ItemType Directory -Path (Split-Path $log) -Force | Out-Null
$existing = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -in @('python.exe','pythonw.exe') -and $_.CommandLine -like "*$script*"
}
if (-not $existing) {
    Start-Process -FilePath $python -ArgumentList $script -WorkingDirectory $project `
        -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.error"
}
Write-Output "Mobile service: port 5668; pairing code in .local\mobile-service\pairing-token.txt"
