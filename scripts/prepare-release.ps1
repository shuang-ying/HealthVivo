param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$androidConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'android\app\build.gradle.kts') -Raw
$match = [regex]::Match($androidConfig, 'versionName\s*=\s*"(\d+\.\d+\.\d+)"')
if (!$match.Success) { throw 'Android versionName not found.' }
$phoneVersion = $match.Groups[1].Value
$watchVersion = (Get-Content -LiteralPath (Join-Path $projectRoot 'watch\src\manifest.json') -Raw | ConvertFrom-Json).versionName
$extensionVersion = (Get-Content -LiteralPath (Join-Path $projectRoot 'orbitv\orbitv.json') -Raw | ConvertFrom-Json).version
if ($watchVersion -notmatch '^\d+\.\d+\.\d+$' -or $extensionVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid component version.' }
$tag = "v$phoneVersion"
$notesPath = Join-Path $projectRoot "docs\releases\$tag.md"
if (!(Test-Path -LiteralPath $notesPath)) { throw "Write release notes first: $notesPath" }
$files = @("vitality-phone-$phoneVersion.apk", "health-vivo-watch-$watchVersion.rpk", "vitality-orbitv-$extensionVersion.ovpkg")
foreach ($name in $files) {
    $source = Join-Path $projectRoot "artifacts\$name"
    if (!(Test-Path -LiteralPath $source) -or (Get-Item -LiteralPath $source).Length -eq 0) { throw "Build artifact missing: $name" }
}
$destination = Join-Path $projectRoot "artifacts\releases\$tag"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$checksums = foreach ($name in $files) {
    $source = Join-Path $projectRoot "artifacts\$name"
    Copy-Item -LiteralPath $source -Destination (Join-Path $destination $name) -Force
    "{0}  {1}" -f (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant(), $name
}
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $destination 'SHA256SUMS.txt'), (($checksums -join "`n") + "`n"), $utf8)
$install = @"
# 活力记录 $tag 配套安装

- 手机：$($files[0])，Android 8 或以上；本次为调试版预发布。
- GT2 手表：$($files[1])，BlueOS；通过 OrbitV 安装。
- OrbitV 手机扩展：$($files[2])，通过 OrbitV 安装。

覆盖更新，不卸载现有 App。手机与手表、扩展程序应配套更新。
手机“我的”开启持续连接；首次使用复制配对信息，在扩展程序粘贴保存。
保持 OrbitV 扩展程序和手表应用打开，等待手机 App、蓝牙通道、手表应用三段就绪，再点击更新。
日常录入和重新连接不会自行同步健康记录。配对信息与 API 密钥请勿发给他人。

APK 为调试签名构建，包含开发入口；本版 GT2 数轴仍需实机确认。
安装包 SHA256 校验值见 SHA256SUMS.txt。
"@
[System.IO.File]::WriteAllText((Join-Path $destination 'INSTALL.md'), $install, $utf8)
Copy-Item -LiteralPath $notesPath -Destination (Join-Path $destination 'RELEASE_NOTES.md') -Force
foreach ($notice in @('LICENSE', 'THIRD_PARTY_NOTICES.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $notice) -Destination (Join-Path $destination $notice) -Force
}
Write-Output "Release files prepared: $destination"
Write-Output 'No upload performed. Review the package set and notes before publishing.'
