# X80 与模拟手表的真实蓝牙联调

2026-10-12，USB 仅用于 ADB 安装、启动和读取诊断。同步内容由 X80 的真实 BluetoothGatt 经 BLE 传到电脑蓝牙适配器；没有使用 USB 转发模拟蓝牙。

## 已完成的实测

- X80 原安装 0.10.0，附近设备权限已授予。
- Windows 蓝牙适配器报告支持 BLE Peripheral；临时打开电脑蓝牙，实测后已恢复原关闭状态。
- 手机扫描到模拟外设，完成服务发现、RSA 连接请求和两端确认码核对。
- 首次同步与模拟手表主动刷新共保存两次，50 次特征写入，单次最大 180 字节；两次均收到手机端“手表已确认保存今日数据”。
- 每次 JSON 摘要 3476 字节，生产 Home 校验、存储回调与保存确认逻辑均执行。总览、热量、运动、三餐、建议五个页面能够读取实际收到的内容。
- 模拟存储提交完成后才返回保存确认。未调用 AI API，未修改体重、三餐、运动记录或密钥配置。
- 电脑模拟器运行使用 Windows GATT 与 Node crypto；不能据此声称 BlueOS 原生 BLE / cipher 已通过验证。

诊断输出 `artifacts/phone-live-watch-connection.json` 不含食品名称、身体数值、建议文本、照片或 API 密钥。模拟收到的内容只写入 `.local/watch-ble-live/cache.enc` 的 AES-GCM 加密临时缓存，未写入诊断报告。清理该临时缓存的删除命令被自动审批拒绝，缓存仍保留。

## GT2 的实际观察与改动

手机无过滤扫描可以找到 GT2，但该广播未包含活力记录服务 UUID。旧版按 UUID 筛选会遗漏它。0.10.1 新增已配对手表入口，连接之后仍必须找到正确 GATT 特征、通过加密确认，才允许发送摘要。

通过已配对入口能够进入应用服务连接流程；后续读到的是空特征值，手表没有显示确认码。尚不能认定为加密参数错误、权限错误或数据格式错误。

0.10.1 改为收到手表已解密的认证回执之后再显示确认码，并区分连接、查找服务、连接请求回应、用户允许和本地保存阶段。无有效握手回应时约 20 秒报错，避免长时间显示误导性的确认码。保存确认归属当前设备，模拟手表的确认不再出现在 GT2 的连接尝试中。

0.3.1 在手表底部提供读写计数和解密处理阶段；原生加密调用增加 15 秒超时。打开应用、刷新和收到配对请求时提供最多 150 秒的常亮窗口，退出页面恢复正常屏幕行为。常亮接口来自 vivo 官方 `blueos.hardware.display.brightness` 文档。

## 复现工具

`scripts/live-watch-simulator.mjs` 运行生产 `ble-transport.mjs` 和 `Home/index.ux` 的方法。TCP 和网页控制只监听 `127.0.0.1`，网页控制需要本次运行生成的本地令牌。确认码未核对时不会自动批准手机。

`scripts/start-windows-ble.ps1` 使用 Windows PowerShell 5.1 编译 `WindowsBlePeripheral.cs`，将 Windows 的真实 GATT 读写事件转交模拟器。编译引用 Microsoft.Windows.SDK.Contracts 10.0.26100.1，缓存目录 `.tools/windows-contracts/ref/netstandard2.0`。先用 `probe-windows-ble.ps1` 检查无线电状态和 Peripheral 支持，结束后恢复原状态。

`WatchDiagnosticsActivity` 仅存在于 Android debug 构建，读取蓝牙元数据，报告保存在手机应用缓存 `watch-diagnostics.json`。没有访问密钥、写入健康记录或调用模型接口。`attempt_gt2=true` 才主动尝试连接已配对手表。

## 参考

- [Windows GATT Server](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-server)
- [BlueOS 蓝牙](https://developers-watch.vivo.com.cn/api/communication/bluetooth/)
- [BlueOS 常亮接口](https://developers-watch.vivo.com.cn/api/system/brightness/#brightnesssetkeepscreenonobject)
