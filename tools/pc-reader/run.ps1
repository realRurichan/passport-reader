param(
    [Parameter(Mandatory = $true)][string]$DocumentNumber,
    [Parameter(Mandatory = $true)][string]$BirthDate,
    [Parameter(Mandatory = $true)][string]$ExpiryDate,
    [string]$OutDir = "dump"
)
$ErrorActionPreference = "Stop"
$cache = "D:\Gradle\cache\caches\modules-2\files-2.1"
$jars = @(
    "$cache\org.jmrtd\jmrtd\0.8.8\378660d52103ec1636b0719eeb29708070b0ce6d\jmrtd-0.8.8.jar",
    "$cache\net.sf.scuba\scuba-smartcards\0.0.21\904449da8b29debd46c121daa2061777e49b4440\scuba-smartcards-0.0.21.jar",
    "$cache\org.bouncycastle\bcprov-jdk18on\1.85.2\aeb3dac02f799ed4783d5ed5900513339880727f\bcprov-jdk18on-1.85.2.jar",
    "$cache\org.bouncycastle\bcutil-jdk18on\1.85\c41082d6f61628919b675970563f5615e4427c9f\bcutil-jdk18on-1.85.jar",
    "$cache\org.bouncycastle\bcpkix-jdk18on\1.77\ed953791ba0229747dd0fd9911e3d76a462acfd3\bcpkix-jdk18on-1.77.jar",
    "$cache\org.ejbca.cvc\cert-cvc\1.4.13\1885b6e9123193e1b11c6c47a53ba27415bac9c\cert-cvc-1.4.13.jar"
)
$java = "D:\Java\jdk-17.0.20.1+1\bin"
Push-Location $PSScriptRoot
& "$java\javac.exe" -encoding UTF-8 -cp ($jars -join ";") -d out TcpCardService.java PermitDump.java
if ($LASTEXITCODE -ne 0) { Pop-Location; exit 1 }
adb forward --remove-all 2>$null
adb forward tcp:8983 tcp:8983
& "$java\java.exe" "-Dfile.encoding=UTF-8" -cp ("out;" + ($jars -join ";")) PermitDump $DocumentNumber $BirthDate $ExpiryDate $OutDir
Pop-Location
