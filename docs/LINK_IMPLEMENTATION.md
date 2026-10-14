# 手表直连实现（0.14.0 / 0.7.0）

本轮把手机与手表之间的蓝牙连接重写为一套独立的持久化直连实现。协议细节见
[LINK_PROTOCOL.md](LINK_PROTOCOL.md)；本文说明代码结构、为什么这样分层、怎么构建和验证。

## 两个应用的路径

```
手机活力记录 → Android 原生 BLE GATT 客户端 → 手表活力记录的 BlueOS 原生 GATT 服务端
```

运行时不依赖 OrbitV、ovpkg、vivo 健康、`device-rpc.aar`、SDK 密钥或云端中转。OrbitV 仍可用于把
RPK 安装到手表，本版没有实现替代的手表安装器。旧扩展工程与旧 `OrbitLocalBridge` 作为历史参考保留，
不在正式页面启动；手表构建已排除 OrbitV SDK 与 `.local.*` 生成文件。

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
- 如果手表还没更新到 0.7.0，手机会先找到服务、读问候，然后提示“请将手表活力记录更新到 0.7.0 或更新版本”
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

- `artifacts/vitality-phone-0.14.0.apk`（调试签名，沿用本机既有签名）
- `artifacts/health-vivo-watch-0.7.0.rpk`（BlueOS debug 构建）

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

**尚未验证，需要真机**

- 真实 GT2 上的完整同步保存确认、断线续传、熄屏/后台持久性。
- 首次登记在真实无线链路上的表现。
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
