$ErrorActionPreference='Stop'
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$principal=[Security.Principal.WindowsPrincipal]::new($identity)
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden `
        -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}
$ruleName='PersonalDashboardMobileLAN'
$python='D:\Users\KLEI\AppData\Local\Programs\Python\Python310\pythonw.exe'
$existing=Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
if(-not $existing){
    New-NetFirewallRule -Name $ruleName -DisplayName 'Personal Dashboard Mobile LAN' `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5668 `
        -RemoteAddress LocalSubnet -Profile Private,Public -Program $python | Out-Null
}
