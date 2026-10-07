$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:PHONE_WIFI_SDK) { $env:PHONE_WIFI_SDK } else { Join-Path $env:TEMP 'phone-wifi-sdk' }
$jar = Join-Path $sdk 'platforms/android-35/android.jar'
if (!(Test-Path $jar)) { $jar = Join-Path $sdk 'platform/android-35-ext15/android.jar' }
$buildTools = Join-Path $sdk 'build-tools/35.0.1'
if (!(Test-Path $buildTools)) { $buildTools = Join-Path $sdk 'build-tools/android-15' }
if (!(Test-Path $jar) -or !(Test-Path $buildTools)) { throw '缺少 Android SDK Platform 35 或 Build Tools 35.0.1' }
$build = Join-Path $root 'build'
$classesDir = Join-Path $build 'classes'
$signing = Join-Path $root '.signing'
New-Item -ItemType Directory -Force $classesDir, $signing | Out-Null
$sources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object FullName
& javac -source 8 -target 8 -classpath $jar -d $classesDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败' }
$unsigned = Join-Path $build 'unsigned.apk'
& (Join-Path $buildTools 'aapt.exe') package -f -M (Join-Path $root 'AndroidManifest.xml') -S (Join-Path $root 'res') -A (Join-Path $root 'assets') -I $jar -F $unsigned
if ($LASTEXITCODE -ne 0) { throw '资源打包失败' }
$classFiles = Get-ChildItem $classesDir -Recurse -Filter '*.class' | ForEach-Object FullName
& (Join-Path $buildTools 'd8.bat') --min-api 26 --output $build @classFiles
if ($LASTEXITCODE -ne 0) { throw 'DEX 编译失败' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $build 'classes.dex'), 'classes.dex') | Out-Null }
finally { $zip.Dispose() }
$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK 对齐失败' }
$keystore = Join-Path $signing 'release.jks'
$passwordFile = Join-Path $signing 'password.txt'
if (!(Test-Path $keystore)) {
  $bytes = New-Object byte[] 24
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $password = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
  [System.IO.File]::WriteAllText($passwordFile, "$password`n$password`n")
  & keytool -genkeypair -keystore $keystore -storepass $password -keypass $password -alias wifimap -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Wi-Fi Signal Map,O=Personal' -noprompt
  if ($LASTEXITCODE -ne 0) { throw '签名密钥生成失败' }
}
$password = (Get-Content $passwordFile -TotalCount 1)
[System.IO.File]::WriteAllText($passwordFile, "$password`n$password`n")
$apk = Join-Path $root 'wifi-signal-detector.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $keystore --ks-pass "file:$passwordFile" --key-pass "file:$passwordFile" --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK 签名失败' }
& (Join-Path $buildTools 'apksigner.bat') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK 验签失败' }
Write-Output $apk
