# 手表直连实现（1.0.0）

本轮把手机与手表之间的蓝牙连接重写为一套独立的持久化直连实现。协议细节见
[LINK_PROTOCOL.md](LINK_PROTOCOL.md)；本文说明代码结构、为什么这样分层、怎么构建和验证。

## 两个应用的路径

```
手机活力记录 → Android 原生 BLE GATT 客户端 → 手表活力记录的 BlueOS 原生 GATT 服务端
```

运行时不依赖 OrbitV、ovpkg、vivo 健康、`device-rpc.aar`、SDK 密钥或云端中转。第三方 OrbitV 手机应用
仍可作为把手表 RPK 装到设备上的工具，它不参与运行，本项目也没有实现替代的手表安装器。

旧扩展（`orbitv/` 工程、手机端 `OrbitLocalBridge`、手表端内置 SDK、相关脚本与文档）已整体移出代码树，
存档在不纳入版本管理的 `archive/orbitv-extension-1.2.0/`，需要时可从 git 历史取回。

## 代码结构

分层原则只有一条：**带判断的逻辑不碰原生接口**。这样 Node 与 JVM 单元测试可以在没有手表、没有手机的
情况下驱动完整协议。

### 手表端 `watch/src/lib/link/`

| 文件 | 职责 | 是否可被 Node 测试直接驱动 |
| --- | --- | --- |
| `bytes.mjs` | 字节/Base64/UTF-8/SHA-256/HMAC-SHA256/CRC-32 | 是（纯函数） |
| `frames.mjs` | 帧编解码、常量、长度上限 | 是（纯函数） |
| `session.mjs` | 会话派生、hello 校验、正文与回复的加解密包装 | 是（注入 native cipher） |
| `protocol.mjs` | 服务端状态机：读回调、写回调、组装、超时、命令分发 | 是（注入 cipher/identity/archive/clock/timers） |
| `server-core.mjs` | GATT 服务生命周期、三个订阅、广播看门狗、原生失败上报 | 是（注入 `ble`/`cipher`/timers） |
| `server.js` | 只做 `import bluetooth/cipher` 并转调 `server-core` | 否（原生胶水） |
| `identity.mjs` | 凭据与 epoch 的持久化，写操作串行化 | 是（注入存储读写） |
| `runtime.js` | 应用级单例：加载日期缓存与身份、启动服务、页面订阅 | 否（原生胶水） |

手表为什么用纯 JS 实现 SHA-256/HMAC：BlueOS 的 `@blueos.security.cipher` 只公开
`rsa/aes/base64/crc32/hash`，没有 HMAC 接口，`hash` 只支持 md5/sha256。所以 HMAC-SHA256 必须自己实现，
AES 才走原生（`cipher.aes`，`text` 为字符串、`key`/`iv` 为 Base64、`ivLen` 必填 16）。

### 手机端 `android/app/src/main/java/com/lrh/healthvivo/link/`

| 文件 | 职责 |
| --- | --- |
| `LinkBytes.kt` | 字节与 CRC-32（`java.util.zip.CRC32`） |
| `LinkFrames.kt` | 帧编解码与常量，与 `frames.mjs` 逐字节对应 |
| `LinkSession.kt` | 会话派生、正文加密、回复校验解密、回执验证；会话失效抛 `LinkSessionLost` |
| `LinkProtocol.kt` | 问候/回执/失败标记/回复分段的解析规则 |
| `LinkGatt.kt` | 唯一接触 `BluetoothGatt` 的类：串行 ATT 操作、状态码、服务变化、关闭 |
| `LinkTransfer.kt` | 分帧发送、回执轮询、正文拉取、连接自检 |
| `LinkStore.kt` | Keystore 包装的凭据、连接选择、更新请求与完成标记 |
| `LinkManager.kt` | 连接协程、指数退避重连、握手、增量同步、扫描、状态流 |
| `LinkService.kt` | `connectedDevice` 前台服务，持有连接 |
| `LinkPanel.kt` | “我的 → 手表直连”界面 |

数据内容层（`WatchDeltaSource.kt`、`WatchSnapshot.kt`、`watch/src/lib/dashboard.mjs`、
`watch/src/lib/archive.mjs`）保持原样：它们描述的是健康记录与手表缓存，与传输无关。

## 手表运行时约束（真机踩过的坑）

BlueOS 不是浏览器，也不是 Node。打包器 `blueos-pack` 为**每个模块**注入一份固定的宿主全局名单；
从已构建的 `.vru` 里可以直接读出它：

```
requireModule unRequireModule $app_define$ $app_bootstrap$ $app_require$
console BroadcastChannel setTimeout clearTimeout setInterval clearInterval $style$ $app_style$
```

其余全局来自引擎自身的 ECMAScript 实现。这条边界有一个很隐蔽的后果：

- **`globalThis` 不在注入名单里。** 我最初把 `createLinkServerCore` 的默认参数写成 `timers = globalThis`。
  默认参数由调用方求值，而该构造函数在 `openServer()` 里被调用——于是它抛 ReferenceError，
  被 `runtime.js` 的兜底 catch 吞掉，界面显示「读取连接信息失败，请重新打开应用」，
  而 `server` 从未创建，**GATT 服务没有注册、手表根本没有广播**。手机侧看到的是 LE 建连一直
  `ARMED` 直到 25 秒超时（`gatt_cleanup_upon_disc: Connection timeout`），完全看不出是手表的问题。
- **`BigInt` 同样不在名单里。** 我改用 `BigInt`/`DataView.setBigUint64` 实现 `u64`，这是第二个同类地雷。
  现在 `u64`/`read64` 用两个 32 位半字拼装，只要数值小于 2^53 就完全精确，字节结果与原来一致
  （由跨语言向量锁定）。
- 遗留的 Annex B 全局 `escape`/`unescape` 也不使用。`utf8()` 改为只依赖 `charCodeAt` 的手写编码，
  与 `Buffer.from(s,'utf8')` 逐字节一致（含中文与代理对）。
- `setInterval`/`clearInterval` 其实是被注入的，但看门狗改成自重排的 `setTimeout` 后，
  整个链路只依赖四个定时器里最基础的那一个。

教训是：**默认参数里的全局引用会把"某一项功能不可用"升级成"整个应用起不来"**，而兜底 catch 又会
把真实原因替换成一句没用的提示。所以现在：

- 启动的三个阶段（日期缓存、连接记录、原生 GATT 服务）互相独立，任一失败都不阻止 GATT 服务启动；
- 失败消息一律带上底层 `error.message`；
- `runtime.js` 在启动前做一次能力自检，缺失的全局会直接追加到启动失败提示里
  （`直连服务启动失败 · 添加服务（缺少：…）`）；
- `watch/test/link-runtime.test.mjs` 机械地禁止这些构造回到代码里，并且额外验证
  「不注入任何定时器对象也能完成注册、广播与一次完整登记」。

## Android 会缓存手表的服务目录（真机踩过的第二个坑）

广播修好之后，手机能连上手表（`connection status=0 state=2`、`services status=0`），但服务发现
返回的目录里没有我们的服务：

```
appServicePresent=false serviceCount=6
services=[00001800(GAP), 00001801(GATT), 00002760-…, cc353442-…, 66841001-…, 0000180d(Heart Rate)]
```

用 Windows 侧的工具做**无缓存**发现（`BleakClient(..., winrt={"use_cached_services": False})`，
即 `scripts/probe-gt2-windows-gatt.py --catalog-only`）读到的是 **7 个服务**，其中包含我们的：

```json
"serviceUuids": [ …, "fa954400-48d6-4a12-b74b-4c5b5170360e" ],
"testCharacteristicFound": true
```

结论：**手表端完全正确**（服务与特征都在），手机读到的是过期的持久化缓存。原因很清楚——
之前排查时手表应用启动就失败、GATT 服务从未注册，而手机此时照常连上去做了一次服务发现，
于是把「没有应用服务」的目录写进了这台已配对设备的缓存。

Android 的这条缓存有几点必须记住：

- 它按**已配对设备**持久化，用来避免每次重连都重新发现；
- **关闭再打开蓝牙不会清除它**（本项目实测无效：清除后仍是同样的 6 个服务）；
- 也没有任何公开 API 可以清除它——`BluetoothGatt.refresh()` 是非公开接口，本项目明确不把它作为
  产品策略（见 [DIRECT_LINK_DEVICE_VALIDATION.md](DIRECT_LINK_DEVICE_VALIDATION.md)）；
- 只有两种公开途径会让它失效：**解除配对**（缓存随配对记录一起删除），或**手表主动发 Service Changed 指示**
  （由协议栈的 Generic Attribute 服务发送，应用无法代发）。

因此唯一的用户级解法是：在系统蓝牙设置里对这台手表**取消配对再重新配对**。手机端现在会在这种情况下
直接给出这句话，面板上也有常驻提示。

复发条件与自愈：只要手表应用在运行，它就会注册服务，手机每次发现都会把**正确**的目录写回缓存，
所以正常使用不会复发。只有当手表应用没运行（或像修复前那样启动就失败）而手机又完成了一次服务发现时，
缓存才会再次过期——这正是手表端「启动三阶段互相独立、任何失败都不阻止 GATT 服务注册」这条设计的价值。

顺带确认了另一件与发现有关的事实：`testServiceAdvertised=false`、`advertisedServiceUuids=[]`——
**GT2 的广播包里不含服务 UUID**，所以手机不能只用服务 UUID 做扫描过滤，必须先按设备名发现
（手机端的扫描逻辑已经同时接受两者）。

## 连接持久化的具体做法

官方文档在几处留了空白，这些空白正是连接会“悄悄断掉”的原因，实现在对应位置做了防御：

| 文档空白 | 本实现的处理 |
| --- | --- |
| `startAdvertising` 无返回值、无成功回调、无错误码 | 3 秒看门狗：无人连接且距上次请求广播超过 6 秒就重新请求；从不把广播当成已成功 |
| 未说明广播是否在连接/断开时自动停止 | 断开事件后延迟 400 ms 主动重新请求广播；连接期间不打扰 |
| 未说明 `GattServer` 实例在原生回收后是否还能用 | 服务注册失败或失效时，距上次尝试超过 10 秒就重建整个服务并重新订阅 |
| 未说明 `sendResponse` 的状态码取值（只写了 0） | 成功用 0，拒绝用非 0（沿用真机验证过的诊断探针行为）；语义结果只由回执表达 |
| `subscribeConnectStateChange` 表格字段名写成 `transId`，示例读 `state` | 优先读 `state`，缺失时回退 `transId` |
| 未说明写入请求是否需要回复 | 只在 `needRsp !== false` 时回复 |

手机侧的持久化：

- 连接由 `LinkService`（前台服务）+ `LinkManager` 中唯一一个协程持有；其他代码不能自行建立或关闭 GATT。
- 任何失败（超时、GATT 状态码、服务变化、会话失效）都回到同一循环，按 1、2、4、8 … 秒递增退避，上限 30 秒。
- **会话失效与物理断线分开处理**：手表重启、另一台手机占用、用户在手表清除连接，都会让回执 MAC 校验失败；
  这时在同一条物理链路上重跑握手（`LinkSessionLost`），比重新建立蓝牙连接快得多。
- 手动更新请求先写盘再尝试；断线、进程被杀、换电池都不会丢，重连后按日期版本差继续。
- 每次 ATT 操作都经过同一把互斥锁；连接本身不请求 MTU，因为协议按默认 20 字节设计，也是真机验证过的形态。

## 与旧实现的关系

服务 UUID、特征布局（单特征、`properties: [4, 5]`、20 字节带响应写入）、
“先回 ATT 再异步解密落盘”的顺序都沿用真机验证过的结论；被替换掉的是帧格式（新增 CRC32、
回复分段带传输标识、指令集）、会话域标签、存储键、代码结构和连接状态机。

**服务 UUID 保持不变是刻意的。** 重写时一度换成新的 UUID，结果手机端一直重连：Android 按已配对设备
缓存整个属性数据库，X80 对这台 GT2 的缓存里只有 0.6.x／诊断探针用过的 `fa954400-…`，而公开接口没有
任何办法清除它（项目自己已经记录过同样的问题，见
[DIRECT_LINK_DEVICE_VALIDATION.md](DIRECT_LINK_DEVICE_VALIDATION.md)）。所以最终复用同一组 UUID：
已配对的手表在第一次服务发现时就能找到服务，代价是诊断探针不能与正式手表应用同时运行。

如果仍然找不到服务，手机端的状态栏会直接说明原因，logcat 的 `VitalityLink` 标签还会打印手机
实际读到的完整服务列表（`appServicePresent=… serviceCount=… services=[…]`），这是判断缓存是否过期的
唯一现场证据。

因此：

- 旧的 `direct_watch_secret` / `fitness.direct.identity.v1` 凭据不会被当成新协议的凭据；两端需要一次
  首次登记（手机搜索并选择手表即可，不需要输入任何代码）。
- 旧诊断应用 `com.lrh.healthvivo.watch.directprobe` 与正式手表应用共用同一个服务 UUID，因此两者
  **不能同时运行**（重写之前也是如此）。探针现在只是历史工具，需要时可以卸载。
- 手表按天缓存（`fitness.index.v3` / `fitness.day.v3.*`）原样保留，升级后不需要重新同步历史。
- 如果手表还没更新到 0.7.1，手机会先找到服务、读问候，然后提示“请将手表活力记录更新到 0.7.1 或更新版本”
  —— 这正是版本不匹配的判别信号，而不是一直停在“找不到服务”。

## 只构建

```powershell
./scripts/build-android.ps1                 # 只编译打包，不测试
./scripts/build-watch.ps1
./scripts/build-android.ps1 -UnitTests      # 额外运行 JVM 单元测试
./scripts/build-android.ps1 -Install        # 安装到已连接手机
./scripts/build-android.ps1 -DeviceTests -Install
```

输出：

- `artifacts/vitality-phone-1.0.0.apk`（调试签名，沿用本机既有签名）
- `artifacts/health-vivo-watch-1.0.0.rpk`（BlueOS debug 构建）

手表编译器即使退出码为 0 但打印 `[ERROR]`，构建脚本也会拒绝复制产物。

## 验证状态与边界

**已经在本地完成**

- 手表协议测试 40 项：帧布局与固定向量、CRC-32 与 Node zlib 对照、会话派生与 Node crypto 对照、
  首次登记、凭据重连、重放/越界/乱序/错误 CRC/错误 MAC 拒绝、组装超时、登记限速、
  绑定对端保护、`SAVED` 必须在存储提交之后、断线保留请求、清除旧连接、
  看门狗重新广播、注册失败重建服务、订阅失败保留阶段、`close()` 顺序，
  以及 4 项运行时约束守卫（禁用构造、禁用默认参数引用全局、不注入定时器也能工作、单一原生导入点）。
- 手机 JVM 测试 15 项（含原有测试共 49 项）：直接断言由**手表实现**生成的
  `link-vectors.properties`，覆盖 CRC、帧字节、问候解析、会话派生、正文加密、
  回复验证解密、回执验证、篡改拒绝、回复分段校验。
- 两端均编译通过（APK 与 RPK）。

**真机结果（2026-10-14，vivo X80 + iQOO WATCH GT2 / BlueOS 3）**

手机 0.14.1（versionCode 23）、手表 0.7.1（versionCode 15）。链路与协议自这次验证后没有改动，
0.15.0 / 0.8.0 只增加了界面、热量口径与数据库列，尚未在真机复测。首次接通前需要在手机上刷新一次过期的
服务缓存（见上一节：本次用 debug 专用的隐藏接口刷新，普通用户的做法是取消配对再重新配对），
之后普通连接即可。日志证据：

```
connection status=0 state=2
services status=0
appServicePresent=true serviceCount=7 services=[…, fa954400-48d6-4a12-b74b-4c5b5170360e]
greeting enrolled=true pending=false
session ready                       ← 会话建立，凭据认证通过（未重新登记）
正在读取手表索引 96 字节             ← 加密索引读取成功（空索引 = 16+48+32）
```

随后由 `WatchDiagnosticsActivity` 的 `sync_once` 路径触发一次真实更新，最终报告：

```json
"connectionMessage": "手表已确认保存 3 个更新日期",
"connectionStage": "ready",
"completed": true, "passed": true,
"events": [ … 正在更新日期 1/3 … 3/3 … 等待手表保存确认… … 手表已确认保存 3 个更新日期 ]
```

即完整链路已在真机验证：持久连接 → 服务发现 → 会话握手 → 加密读取日期索引 → 逐日加密下发 →
手表逐个回 SAVED → 手机记录完成。缓存刷新之后又连续两次普通重连都在第一次发现时找到服务，
不需要再次刷新。

**尚未验证**

- 断线续传的完整路径（本次没有在传输中途强制断线；协议与单元测试覆盖了该路径）。
- 熄屏/后台的长期持久性与系统回收表现。
- 首次登记在真实无线链路上的表现（本次是已登记凭据的 HELLO 路径）。
- 本版没有读取任何 vivo 健康数据，也没有验证系统链路是否已启用加密绑定；首次登记仍是首次使用信任，
  此阶段不能声称抵抗中间人或附近其他客户端抢先登记。

真机联调步骤与诊断入口见 [LINK_PROTOCOL.md](LINK_PROTOCOL.md) 的“测试”一节；
历史诊断结论（旧协议）见 [DIRECT_LINK_DEVICE_VALIDATION.md](DIRECT_LINK_DEVICE_VALIDATION.md) 与
[DIRECT_LINK_SIMULATOR_VALIDATION.md](DIRECT_LINK_SIMULATOR_VALIDATION.md)。

实现依据：[BlueOS BLE](https://developers-watch.vivo.com.cn/api/system/bluetooth/)、
[BlueOS cipher](https://developers-watch.vivo.com.cn/api/system/cipher/)、
[BlueOS 生命周期](https://developers-watch.vivo.com.cn/api/extend/lifecycle/)、
[BlueOS 应用共享对象](https://developers-watch.vivo.com.cn/reference/configuration/script/)、
[BlueOS 后台运行](https://developers-watch.vivo.com.cn/reference/extend/resident/)、
[Android connectedDevice 前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)、
[Android BLE 收发](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)。
