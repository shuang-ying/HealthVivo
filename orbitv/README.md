# 活力记录同步 · OrbitV 扩展程序 1.2.0

配套 Android 活力记录 0.12.3、GT2 / BlueOS 手表 0.5.3，关联包名 `com.lrh.healthvivo.watch`。覆盖更新保留原首次配对信息。

1. 手机活力记录“我的”开启持续连接；首次使用复制配对信息并在扩展程序粘贴保存。
2. 保持本扩展程序和手表活力记录页面打开。
3. 手机／扩展程序点击“更新到手表”，或手表点击“获取更新”。只传新增和修改的日期，保留原有历史缓存。日常保存记录不自动同步。

页面支持上下滚动。定时连接检查只读取指令和确认状态，不读取健康记录。数据只经过同手机回环接口、OrbitV 和蓝牙手表，不使用云服务器，不复制照片或模型 API 密钥。扩展程序持久保存配对配置，不保存健康摘要。

开发：Node.js >=20.19，`npm ci --ignore-scripts`，`npm run package`。产物 `release/com.lrh.healthvivo.watch-1.2.0.ovpkg`，可本地导入。

工程基于 [OrbitV 公开下载模板](https://doc.developers.orbitv.top/downloads)，业务页面和 service.js 为本项目实现。安装、协议和验证范围见 [ORBITV_SYNC.md](../docs/ORBITV_SYNC.md)。

三段连接状态独立检测，掉线后自动重新检查。手机接口不再每 15 分钟关闭；扩展程序页面关闭后 OrbitV 会释放 service.js，无法跨越其生命周期常驻。
