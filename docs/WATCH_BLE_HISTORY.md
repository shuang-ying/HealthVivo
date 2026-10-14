# 历史 BLE 原型说明（已停用）

以下为 0.10.0 / 0.3.0 的开发记录，当前版本的安装与同步方式以 ORBITV_SYNC.md 为准。

# 手机与手表直连版
2026-10-12：手机 0.10.0（versionCode 12），手表 0.3.0（versionCode 3）。目标为 iQOO WATCH GT2 / BlueOS 3。开发调试包，尚未上架。

## 安装与连接

1. 使用配套 APK 覆盖更新手机，保留原记录和 API 配置。不要卸载或清空数据。
2. 更新配套手表 RPK。RPK 仍须通过 BlueOS Studio 的设备安装入口、开发者分发或可用的 vivo 健康调试安装入口安装，不能当手机 APK 安装。
3. 打开手表活力记录，底部应显示“蓝牙直连服务已开启”。保持手表应用在前台。
4. 手机进入“我的 → 手表同步”，打开开关，允许“附近设备”权限，点击“搜索附近手表”，选中设备。
5. 两端确认码一致时，在手表点击“允许这台手机”。连接后手机自动发送今日数据。
6. 手机显示“手表已确认保存今日数据”才表示完成。随后保存手机记录或生成建议会自动更新；手表“刷新手机数据”也可请求更新。

应用数据经 BLE GATT 直连，不通过 vivo 健康，也不使用智能终端 SDK 密钥。系统已配对只表示系统蓝牙连接，仍需建立应用服务连接。每次新建会话都需在手表核对确认码；手表退到后台时关闭服务，下次打开需要重新连接。首次实现暂不承诺后台持续同步。

## 页面与数据

只读看板显示今日步数、运动、热量、三餐、手机 AI 建议、当前/目标体重，以及 BMI 和用户手动体脂率的参考状态。日期、最近保存时间和旧建议状态明确显示，离线缓存继续可用。旧版缓存仍可读取。

未记录用空白标记，真实零保留为零。每餐最多20条食物、最多20条运动，超出部分显示数量。静息加已知活动为部分消耗参考，不能直接视作完整全天消耗或剩余可吃额度。

不传照片、API 密钥、完整历史、年龄、身高或性别参数。BMI、体脂状态在手机计算；体脂率仅来自用户录入的测量值，携带测量日期。完整记录以手机为准。

## 连接提示

- “未发现应用直连服务”：确认已安装0.3.0、已打开手表应用、手机附近设备权限和蓝牙已开启。
- “BLE 直连失败”：手表显示创建服务、添加服务、监听或广播的失败阶段及可用错误码。该机型具体固件的接口开放情况仍需真机确认。
- 确认码不同：拒绝并重连，不能继续传送。
- “解密连接请求失败”：确认 APK/RPK 来自同一持久构建身份，或核对设备加密 API 支持。
- “已传送，等待保存确认”：手机已完成 BLE 写入，尚未收到手表成功落盘回执。失败或断线保留上次缓存；可重新连接。
- “接收 / 校验 / 保存失败”：2为分包超时、3为解密/认证错误、4为摘要格式或存储失败。修复原因后重连。

## 构建与身份

    ./scripts/build-android.ps1
    ./scripts/build-watch.ps1

脚本先运行 prepare-ble-keys.py，首次生成本机私有 RSA 构建身份，之后重用。Android 仅携带公钥，手表 RPK 携带私钥；会话随机密钥由手机产生，经 RSA-OAEP 传送，在手表人工允许后采用 AES-CBC + HMAC 传输。状态回执也认证，只有手表本地保存成功才返回完成。

这些是个人使用的配套调试包，不是公钥基础设施或经过安全审计的公开分发方案。保留并妥善保护 .local/watch-ble-identity.json，不提交生成的密钥配置，不公开分发包含私有构建身份的 RPK。旧 vivo SDK 本地配置仍保留，当前构建不再读取或打包 SDK 密钥/AAR。

产物：artifacts/vitality-phone-0.10.0.apk、artifacts/health-vivo-watch-0.3.0.rpk。

build-watch.ps1 -Install 仍可经手机发起 vivo 健康调试安装请求。安装请求成功不代表 RPK 已装入手表；应用运行时的数据通信与该安装路径独立。

接口依据：[BlueOS BLE](https://developers-watch.vivo.com.cn/api/system/bluetooth/)、[BlueOS 加密](https://developers-watch.vivo.com.cn/api/system/cipher/)、[Android BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)。

## 仿真与限制

    ./scripts/prepare-watch-studio.ps1 -Demo -Open

Studio 副本仍在 D:\AgentProject\HealthVivoWatchDev。演示包名带.preview，使用虚构数据、模拟确认与内存存储，不访问真实蓝牙、模型 API 或健康数据；不会携带生产私钥。点击演示“允许这台手机”后显示看板。

~~scripts/simulate-ble-watch.mjs~~ 运行当时的 GATT 协议和 Home 页面处理函数，用独立的 Node 原生 RSA/AES/HMAC 和临时身份模拟手机，覆盖最小20字节包、认证、人工确认、保存前不回执、存储失败、零步数、半包、旧版本、对端隔离及离线重开。该脚本与被它驱动的 `ble-transport.mjs` / `ble-wire.mjs` 已在 0.7.1 随直连协议重写一并删除；当前协议的仿真与测试见 [活力直连协议 v1](LINK_PROTOCOL.md) 的“测试”一节。

APK、RPK 已构建成功；手机预览使用 Android 模拟器的合成记录。协议仿真不等于 GT2 真机 BLE/BlueOS 原生加密验证；真实 API 服务、手表安装及系统后台限制亦未验证。目前未发现通过 ADB 连接的 X80。本次未运行旧单元测试或 instrumentation。
