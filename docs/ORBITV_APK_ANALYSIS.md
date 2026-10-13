# OrbitV 蓝牙互操作分析

分析对象为用户提供的 `D:\AgentProject\OrbitV_2.2.0.APK`，54,124,957 字节；SHA-256：`8ED760158EC35BAB272B51C281120D35CF3B78C54B05B733B9D171644E85334E`。手机实际安装的 OrbitV 为 2.2.7；未替换其安装或读取私有账号数据。

## APK 中可以确认的部分

- Android 包名 `com.yyh.orbitv`，主要界面和业务在 Flutter/Dart AOT。
- `RpcChannelPlugin` 暴露 Dart 方法通道 `orbit_v/rpc_channel`。串口方法包括 `nativeSppConnect`、`nativeSppIsConnected`、`nativeSppDisconnect`、`nativeSppSend`。
- 原生蓝牙层使用标准经典蓝牙 SPP UUID `00001101-0000-1000-8000-00805F9B34FB`。首选 `createRfcommSocketToServiceRecord`，另有反射通道 1 和公开的非安全 RFCOMM 备选策略。
- 收发为串口字节流，独立读写线程与发送队列；并非本项目先前的自定义 BLE GATT 特征协议。
- Dart 字符串包含 eRPC、sendFrame、serviceId、动态库加载以及导入 `.orbitpkg` / `.so` 驱动的提示。Android Java 层本身不提供完整 BlueOS RPC 接口定义。

本次没有复用反编译专有代码或尝试绕过付费/驱动校验。`ClassicSppProbeActivity` 用公开 Android API 独立实现只建连的探测，不向手表发送猜测的 RPC 命令。

## GT2 实测

X80 已配对 GT2 的标准安全 SPP 建连成功，约 630 ms。随后关闭探测套接字；应用数据发送 0 字节。报告为 `artifacts/gt2-classic-spp-probe.json`。这个结果确认硬件传输通道可达，不能据此认定任意 JSON 可以直接送达手表应用。

先前 BLE 直连在 PC 真蓝牙模拟外设上完成了摘要与保存确认，但 GT2 读特征返回空值、手表计数读 0 / 写 0。SPP 成功与这些 GATT 回调失败并不矛盾：两条连接使用不同传输机制。

## 新的可用资料

用户随后提供 [OrbitV 开发文档](https://doc.developers.orbitv.top)。已通过站点公开下载入口获取 BlueOS SDK 1.0.0 与 Vue 扩展程序模板，无需从手机私有目录提取驱动。

文档提供的应用通信方式是：手表 SDK 请求本地网关，OrbitV 按手表 `package` 与扩展程序 `appId` 把请求交给 `service.js`，由其返回 JSON / 文件。扩展程序打开时服务才运行。这足以建立本项目手机摘要 → OrbitV → GT2 的应用通道，不需要重建底层 eRPC。

接入工程、三个安装包和使用方式见 [ORBITV_SYNC.md](ORBITV_SYNC.md)。截至本报告，SPP 真机建连、本机手机接口与合成数据全链路已验证；GT2 的 OrbitV 业务同步仍需安装新包后实测。
