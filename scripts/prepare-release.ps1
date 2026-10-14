param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$androidConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'android\app\build.gradle.kts') -Raw
$match = [regex]::Match($androidConfig, 'versionName\s*=\s*"(\d+\.\d+\.\d+)"')
if (!$match.Success) { throw 'Android versionName not found.' }
$phoneVersion = $match.Groups[1].Value
$watchVersion = (Get-Content -LiteralPath (Join-Path $projectRoot 'watch\src\manifest.json') -Raw | ConvertFrom-Json).versionName
if ($watchVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid component version.' }
$tag = "v$phoneVersion"
$notesPath = Join-Path $projectRoot "docs\releases\$tag.md"
if (!(Test-Path -LiteralPath $notesPath)) { throw "Write release notes first: $notesPath" }
$files = @("vitality-phone-$phoneVersion.apk", "health-vivo-watch-$watchVersion.rpk")
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

本版只有两个安装包：手机 App 与手表 App。旧版曾附带的 OrbitV 扩展（ovpkg）已废弃，
见 archive/orbitv-extension-1.2.0/README.md。

- 手机：$($files[0])，Android 8 或以上；本次为调试版预发布。
- GT2 手表：$($files[1])，BlueOS；RPK 可继续用第三方 OrbitV 应用安装，OrbitV 只作为安装器。

覆盖更新，不卸载现有 App。手机与手表应配套更新。
手机“我的 → 手表直连”开启持续连接；首次在手机搜索并选择手表，连接凭据自动保存在两端，无需输入配对码。
更新只在手机点击“更新到手表”或手表点击“获取更新”后传输；日常录入和重新连接不会自行同步健康记录。
连接凭据保存在手机 Keystore，请勿把连接信息或 API 密钥发给他人。

若手机提示“未发现活力记录直连服务”，是 Android 按设备缓存了过期服务目录：
在系统蓝牙设置里对这台手表取消配对再重新配对即可。

APK 为调试签名构建，包含开发入口。
安装包 SHA256 校验值见 SHA256SUMS.txt。
"@
[System.IO.File]::WriteAllText((Join-Path $destination 'INSTALL.md'), $install, $utf8)
Copy-Item -LiteralPath $notesPath -Destination (Join-Path $destination 'RELEASE_NOTES.md') -Force
foreach ($notice in @('LICENSE', 'THIRD_PARTY_NOTICES.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $notice) -Destination (Join-Path $destination $notice) -Force
}
Write-Output "Release files prepared: $destination"
Write-Output 'No upload performed. Review the package set and notes before publishing.'
