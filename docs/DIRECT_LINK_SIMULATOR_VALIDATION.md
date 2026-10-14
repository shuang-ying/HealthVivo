# 独立蓝牙通道模拟验证

验证日期：2026-10-13。结论：Android 侧能够在不安装 OrbitV、vivo 健康或扩展程序的情况下，用原生 RFCOMM 接口双向传输合成消息，并在短时间的后台、熄屏和对端主动断线场景继续收发、重连。BlueOS 原生模拟器现已执行探测页，但没有证明两个原生模拟器通过蓝牙互通。补充的物理 X80 / GT2 建连结果见 [真机验证记录](DIRECT_LINK_DEVICE_VALIDATION.md)。GT2 的业务 RPC 协议仍未验证。

## 本次结果

| 项目 | 结果 | 实际验证范围 |
| --- | --- | --- |
| Android 原生 SPP 收发 | 通过 | Android Emulator 37.2.12 / API 37，连接 Bumble 虚拟 RFCOMM 服务端 |
| 独立连接 | 通过 | AVD 未安装 OrbitV、vivo 健康；探测代码不使用 `.ovpkg` |
| 后台与熄屏 | 通过 | 退到桌面并进入 Dozing 后，回应数从 2 增加到 8 |
| 对端断线恢复 | 通过 | 虚拟对端主动断开 1 次，Android 成功连接 2 次；恢复后继续收到匹配会话和序号的 PONG |
| 前台服务 | 通过 | Android 原生 `connectedDevice` 服务，检查到 `isForeground=true` |
| 手动更新与增量保存 | 软件仿真通过 | 原有生产同步模块的合成数据检查；传输适配器为模拟实现，不是独立 GT2 SPP 协议 |
| BlueOS 探测工程编译 | 通过 | 官方 blueos-pack 1.0.12-beta.13，独立包名 `com.lrh.healthvivo.watch.directprobe` |
| BlueOS 原生模拟器启动 | 通过 | 按 Studio 的预览桥发送 `previewToSimulator / on`，官方 `watch.exe` 已执行 `/Home` 并回传原生接口诊断 |
| BlueOS 原生 GATT 初始化 | 部分通过 | `bluetooth.BLE.createGattServer()` 和 `addService()` 可调用，后者返回 true；广播返回 void；底层日志出现 `le_gatt_characteristic_read nullptr`，没有验证无线收发 |
| BlueOS 与 netsim 共用虚拟无线通道 | 未验证 | 尚未找到可用的接入方法；不能根据两个模拟器均存在就假定其蓝牙可互通 |
| GT2 独立连接和应用收发 | 基础收发通过 | 物理 X80 与 GT2 的专用原生 GATT 测试页已匹配 PING/PONG；标准 SPP 只验证建连，尚未验证系统 RPC、健康数据同步和后台持久性 |

主要证据保存在未提交 Git 的 `artifacts/direct-link-probe/`：

- `android-spp-result.json`：本次合格运行的汇总。
- `android-before-background.json` / `android-after-background.json`：本次相同 `runId` 的原生 App 回应与连接事件。
- `android-background-service.txt` / `android-background-power.txt`：服务与熄屏状态。
- `virtual-peer.json`：对端收发计数、主动断线次数；其中 ready 表示进程运行状态。
- `blueos-native-result.json`：原生模拟器的连接信息，明确标记蓝牙传输未验证。
- `android-first-attempt.json`：首次因未完成系统配对而超时的结果，不作为成功证据。

所有消息仅为随机测试会话和序号的 PING/PONG，不读取健康数据库，不调用 AI、不传照片或密钥。Android 调试入口和服务检查模拟器硬件类型，实际手机不能启动该探测。健康产品的生产同步方式、版本号和已发布安装包没有更新。

## 重现 Android 结果

1. 使用 Android Studio 启动支持虚拟蓝牙的 AVD。本机使用 `Medium_Phone_API_37.0`，ADB 序列号为 `emulator-5556`。这些探测脚本拒绝非 `emulator-*` 序列号。
2. 在项目内创建独立 Python 环境并安装 `probes/requirements-bluetooth.txt`。本机位置为 `.tools/bluetooth-probe-venv`。
3. 在 `android` 工程执行 `assembleDebug`，只向 AVD 安装产生的调试 APK，并授予 `android.permission.BLUETOOTH_CONNECT`。本次探测包另存为 `artifacts/direct-link-probe/vitality-spp-emulator-probe.apk`，没有覆盖 Release 资产。
4. 在一个终端运行下列虚拟对端，在另一个终端运行检查器：

```powershell
& '.tools\bluetooth-probe-venv\Scripts\python.exe' 'scripts\probe-virtual-spp-peer.py' --duration 600
```

```powershell
& '.tools\bluetooth-probe-venv\Scripts\python.exe' 'scripts\probe-spp-emulator.py' --serial emulator-5556
```

检查器会在 AVD 内完成名为 `HealthVivo-SPP-SIM` 的虚拟设备配对，收到 2 次回应后退到桌面并熄屏，再检查后台回应和重连。每次运行用新 `runId` 排除旧缓存结果。探测服务最多运行 120 秒；合格检查结束时主动停止服务。虚拟对端每次启动都会得到新的虚拟地址，应使用新启动的对端，避免旧计数影响主动断线场景。

这是传输机制的短时检查，不等于 OriginOS 后台长期稳定性、进程被杀后的恢复或 GT2 业务兼容性测试。用于合成消息的 JSON 帧不是 OrbitV 的 eRPC 协议。

## BlueOS 探测

`scripts/prepare-direct-watch-probe.ps1` 将 `probes/blueos-direct` 编译到有标记的独立目录 `D:\AgentProject\HealthVivoDirectProbe`，不覆盖现有手表工程或演示副本。`scripts/probe-blueos-simulator.mjs` 启动官方模拟器和只监听本机的调试/结果收集端点。结果收集用 HTTP，不能当作蓝牙验证。

首次尝试只收到调试握手。核对本机 Studio 预览桥后，将启动消息改为 `previewToSimulator` 事件中的 `{ method: 'on' }`，已收到 `/Home` 页面创建事件以及 `Home.onInit` 的原生 API 结果。探测页按官方 API 使用 `bluetooth.BLE.createGattServer()`；`startAdvertising()` 文档未约定布尔返回值，记录 void 不应判为失败，也不应当作无线广播成功证明。

当前原生结果中 `gattServerCreated=true`、`addServiceReturn=true`，但底层日志含 `le_gatt_characteristic_read nullptr`。因此只确认页面执行和接口调用，未确认模拟器蓝牙硬件接入或跨模拟器传输。不要用 HTTP 诊断结果代替 Bluetooth PONG 证据。

## 对下一阶段的影响

手机端独立收发、连接保持和重连已有原生模拟证据；本次补充真机 GATT 检查后，测试消息已实际到达 GT2 原生页面并读回对应确认。发现手表旧服务残留与手机旧服务目录缓存：手表普通重启后新服务出现，手机诊断缓存刷新后收发成功，随后公开接口普通连接也通过。系统 SPP/RPC 的帧格式和应用路由仍未解析，但自定义 GATT 已有独立基础收发证据。要交付完全脱离 OrbitV 的产品，仍需完成服务生命周期、公开接口下的服务变更处理、后台持久性以及正式健康数据帧与保存确认。

健康数据仍按既定要求只在手机“更新”或手表“获取更新”时同步；连接保持和元数据心跳不触发新的健康数据同步。

参考：[Android 虚拟网络与蓝牙](https://developer.android.com/studio/run/emulator-networking-advanced)、[Android connectedDevice 服务](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)、[Google Bumble](https://github.com/google/bumble)、[BlueOS BLE](https://developers-watch.vivo.com.cn/api/system/bluetooth/)。
