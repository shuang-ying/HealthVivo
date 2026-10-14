# 活力直连协议 v1

手机 **活力记录 0.15.2**（versionCode 26）与手表 **活力记录 0.9.2**（versionCode 19）之间的原生 BLE 协议。
两套实现互为对照：手机端 `android/app/src/main/java/com/lrh/healthvivo/link/`，手表端 `watch/src/lib/link/`。

本协议不使用 OrbitV、ovpkg、vivo 健康、`device-rpc.aar`、SDK 密钥或云端中转。旧诊断协议
（服务 UUID `fa954400-…`，见 [DIRECT_LINK_DEVICE_VALIDATION.md](DIRECT_LINK_DEVICE_VALIDATION.md)）
是历史上用于验证物理层可用的临时方案，与本协议不能互通，两端也已不再内置。

## 物理层

| 项 | 值 |
| --- | --- |
| 服务 UUID | `fa954400-48d6-4a12-b74b-4c5b5170360e` |
| 特征 UUID | `fa954401-48d6-4a12-b74b-4c5b5170360e` |
| 特征属性 | `properties: [4, 5]` — BlueOS 的枚举里 4 = 可读、5 = 可写（不是 Android 的属性位掩码） |
| 角色 | 手表 = GATT 服务端（`createGattServer` + `startAdvertising`），手机 = GATT 客户端（`connectGatt`） |
| ATT 写入 | 固定 ≤ 20 字节，`WRITE_TYPE_DEFAULT`（带响应） |
| 读取 | 手机从同一特征读回回应，不协商大 MTU，不使用通知 |

**为什么沿用这组 UUID，而不是换新的。** Android 会按已配对设备缓存整个属性数据库（服务、特征、
句柄）。本项目已经记录过一次教训：换上新的服务 UUID 后，X80 对真实 GT2 一直读到旧的服务目录，
直到用非公开接口刷新缓存才看见新服务（见 [DIRECT_LINK_DEVICE_VALIDATION.md](DIRECT_LINK_DEVICE_VALIDATION.md)）。
换 UUID 会让每一台已经配对过的手表在首次发现时找不到服务，而 Android 没有任何公开接口可以清除该缓存。
因此这里刻意复用 0.6.x 与诊断探针使用过、且已经存在于手机缓存中的同一组 UUID；换来的代价是
独立诊断应用 `probes/blueos-direct` 不能与正式手表应用同时运行（这一点在重写之前也是成立的）。

**为什么只有一个特征。** 真实 GT2 在诊断阶段验证过 `[4, 5]` 单特征的读写收发，本协议沿用这个
已经被真机证明的最小拓扑，把新增能力（可靠性、持久化）放在帧格式和两端状态机里，而不是增加特征数量。

## 手机 → 手表：帧格式

每一次 ATT 写入恰好是一个帧，`offset` 必须为 0，`isPrep` 必须为假。整数一律大端。

| 操作 | 值 | 布局 | 长度 |
| --- | --- | --- | --- |
| BEGIN | 1 | `1` + id(4) + kind(1) + 总长度(4) + CRC32(4) | 14 |
| DATA | 2 | `2` + id(4) + offset(4) + 数据 | 9 + 数据 |
| END | 3 | `3` + id(4) + CRC32(4) | 9 |
| POLL | 4 | `4` + id(4) + offset(4) + 每段字节数(1) | 10 |
| RELEASE | 5 | `5` + id(4) | 5 |
| RESET | 6 | `6` | 1 |

- 传输标识 `id` 是手机本会话内递增的非零 32 位整数，同一个 id 只能成功 BEGIN 一次；已消费的 id 重放会被拒绝。
- `kind`：`1` = 用已保存凭据恢复会话（48 字节），`2` = 加密正文，`3` = 首次登记新凭据（80 字节）。
- DATA 的 `offset` 必须严格等于已接收长度；乱序、重复、越界都会被拒绝而不破坏已有组装。
- `CRC32` 为 CRC-32/ISO-HDLC（与 `zlib.crc32`、`java.util.zip.CRC32` 相同）。手表在 END 时先校验
  长度和 CRC，再调用解密与存储，因此损坏的分段不可能覆盖已保存的日期。
- 正文最大 200 KiB；单帧数据段 11 字节（`20 - 9`）。
- 组装超时 60 秒；`RESET` 可随时清空会话与组装。

## 手表 → 手机：读取回应

手机每次读取得到三种形态之一，按首字节区分：

### 公开问候（无会话，20 字节）

```
0xd6 | 协议版本(1) | 已登记(1) | epoch(8) | 读取计数(4) | 写入计数(4) | 状态(1)
```

`状态` 的位 0 = 手表有未完成的更新请求，位 1 = 手表正在清除旧连接（暂停登记）。
手机用 `epoch` 计算本次会话密钥；`epoch` 每次握手后严格递增，因此旧连接的 hello 无法重放。

### 会话回执（20 字节，已认证）

```
state(1) | 传输标识(4) | 标志(2) | HMAC-SHA256(receiptKey, 前 7 字节)[0..12]
```

标志位 15 = 手表请求手机更新；低 15 位是进度或阶段码。手机必须先验证 HMAC 再采信内容。

| state | 含义 |
| --- | --- |
| 2 | 会话已建立，空闲 |
| 3 | 正在接收或保存，低 15 位是接收百分比 |
| 4 | 该 id 已完整保存（存储提交之后） |
| 5 | 失败，低 15 位是阶段码 |
| 6 | 拒绝（保留） |
| 8 | 有一段回复正文待读取，低 15 位是正文长度 |

### 回复正文分段

```
0xd8 | 传输标识(4) | offset(4) | 数据(≤11)
```

手机用 `POLL` 逐段拉取（每段 ≤ 11 字节），拉完必须发 `RELEASE`，手表才释放缓冲。

### 握手失败

```
0x05 | 阶段码(1)
```

只有两字节，不带密钥材料。阶段码：1 通用、2 挑战不匹配、3 凭据保存失败、
4 正文 MAC/格式、5 解密或解析、6 存储提交、7 回复构造、8 未知指令。

## 会话与凭据

- 凭据是手机为每块手表生成的 32 字节随机值，Base64（无换行）编码传输。手机用 Android Keystore
  AES-GCM 包装保存，手表存在应用私有存储。
- 主密钥 `master = SHA-256("vitality-link-v1:" + base64凭据)`。
- 派生 `HMAC-SHA256(master, label + u64(epoch) + nonce)`，`label` 依次为
  `phone-enc`、`phone-mac`、`watch-enc`、`watch-mac`、`receipt`。
- `nonce` 是手机每次连接新生成的 16 字节随机值；`epoch` 是手表持久化的单调版本号。
- hello = `nonce` + `HMAC(HMAC(master, "hello"), u64(epoch) + nonce)`。
  80 字节的登记载荷 = 32 字节凭据 + 48 字节 hello（用新凭据计算）。
- 手表先持久化凭据、再安装会话，因此掉电不会造成两端对“当前凭据”的理解不一致。
- 手机正文：`iv(16) || AES-256-CBC/PKCS5Padding || HMAC-SHA256(phoneMac, u32(id) + iv + 密文)`。
  手表先验 MAC 再调用原生解密。
- 手表回复：`iv(16) || 密文 || HMAC-SHA256(watchMac, u32(id) + iv + 密文)`，
  其中 `iv = HMAC-SHA256(watchMac, u64(会话计数器) + u32(id))[0..15]`。计数器每个会话从 0 开始，
  由 `epoch` 重置，因此同一密钥下的 IV 永不重复（手表侧随机源不保证可用，所以不依赖随机 IV）。
- 握手尝试限速：30 秒窗口内最多 3 次，超出的 BEGIN 直接拒绝，避免附近设备反复试探。

## 命令（加密正文内的 JSON）

| type | 字段 | 手表回应 |
| --- | --- | --- |
| `inventory` | `cursor` | state 8，正文 `{known: {日期: 版本}, cursor, more}`，每页最多 100 个日期，总数上限 5000 |
| `days` | `days`（最多 1 个日期快照）、`final` | state 4（存储提交后）；最后一批评为 `final` 时同时清除手表的更新请求标志 |
| `ping` | 无 | state 2，用于连接自检（一次完整的加密往返） |

日期正文结构为看板 **schema 3**（步数、运动、三餐摘要、能量、体重/BMI、体脂率、目标、AI 建议，以及 `history`），
校验规则见 `watch/src/lib/dashboard.mjs` 与 `WatchSnapshot.kt`。

### schema 3：history 取代逐日 trend

schema 2 里的 `trend` 是"倒数 14 天的定长窗口"，只够画固定的日柱。schema 3 改成 **`history`**：

| 字段 | 含义 |
| --- | --- |
| `days[]` | **只含有记录的那天**，`{day, intakeKcal, activityKcal, referenceKcal}`，由旧到新，且不晚于手机当天的日期 |
| `weights[]` / `fats[]` | 体重的测量记录 `{day, value}`，由旧到新 |
| `today` | 手机的当天日期，用于判断某一列是否"还没到" |

`history` 是**稀疏**的：没有记录的那天不是一行零，而是根本不存在——空白日对任何周期都不贡献，
这样一年的历史也能跟着每天的摘要一起发送。**每一条能量规则（哪天的体重生效、哪些天计入）都在手机侧算完**，
手表只负责把落在某一列里的天数相加，所以规则不会在两种语言里各写一遍。
运动记录新增可空的 `steps`（本次运动归属的步数）。

**`history` 只出现在 `day` 等于 `today` 的那份摘要里，其余日期为 `null`。**
它是整个序列、不是某一天的内容，所以不随每一天重复下发。这一条同时满足三件事：

- 看 12 号时，同一时段内的 14 号记录仍然画得出来（数据不再被"当天"截断）；
- 任何一次记录变更只会让**一份**摘要变化，而不是索引里全部日期（同步指纹覆盖整张摘要）；
- 传输量不随缓存的日期数重复累加历史。

手表把收到的最后一份 `history` 保存下来，用它绘制所有时段；`today` 与 `revision`、`generatedAt` 一样
**不参与同步指纹**（它们会在午夜对所有日期同时变化，只影响"还没到"的浅色标注）。

手表侧的周期换算（周／月／年、列的标签与分组求和）用 `watch/test/fixtures/trend-vectors.json` 锁定：
该文件由手机端 `TrendVectorsTest` 用 `Trends` 生成，手表端 `trend-vectors.test.mjs` 用 JS 重算并逐字段比对。

## 一次更新的完整流程

1. 手机连接 → 发现服务 → 写 `RESET` → 读问候，取 `epoch`。
2. 手机发 BEGIN(kind 1 或 3) + DATA + END；手表在 ATT 应答之后异步验证、持久化凭据、安装会话，
   下一次读取返回 state 2。
3. 手机发 `inventory`，逐页拉取手表已保存的日期与版本。
4. 手机本地比较，只把新增或修改的日期逐批（每批一天）加密发送。
5. 手表 `archive.merge` 落盘成功后才返回 state 4；手机看到 state 4 才把该批当作已保存。
6. 全部批次确认后，手机记录 `completed = request`，界面显示“手表已确认”。
7. 断线时已提交的日期保留；重连后重新执行第 3 步，只继续缺失或过期的日期——这就是续传。

## 持久化设计

连接本身永远不传输健康记录；只有用户点“更新到手表”/“获取更新”才会走上面的流程。

**手表端**（`watch/src/lib/link/server-core.mjs`）

- 服务由应用（`app.ux`）持有，页面切换、`onHide` 都不重建，避免重复注册同名服务。
- `startAdvertising` 在官方文档里没有返回值、没有成功回调、没有错误码；也没有任何地方说明
  广播会在连接或断开时自动停止。因此有一个 3 秒周期的看门狗：
  - 没有任何手机连接、且距上次请求广播超过 6 秒 → 重新请求广播（丢失的广播自愈）；
  - 服务注册失败或原生服务被回收 → 距上次尝试超过 10 秒 → 重建整个服务（服务 + 三个订阅 + 广播）；
  - 有手机连接时绝不打扰链路。
- 物理断开时只清除会话，保留日期缓存和更新请求标志，方便下次重连后继续。
- 更新请求标志持久化，App 重启或手表重启后依然有效。

**手机端**（`android/app/src/main/java/com/lrh/healthvivo/link/`）

- `LinkService`（`connectedDevice` 前台服务）持有连接；`LinkManager` 里唯一的协程负责连接，
  其他代码不允许自行创建或关闭 GATT。
- 失败后按 1、2、4、8 … 秒递增重试，上限 30 秒；任何异常都不会让循环退出。
- 会话失效（手表重启、另一台手机占用、用户在手表清除连接）与物理断线分开处理：
  会话失效时在同一条物理链路上重跑握手，不重新建立蓝牙连接。
- 手动更新请求先落盘再尝试；断线、进程被杀、换电池都不会丢，重连后继续。

## 测试

```powershell
# 手表侧：独立 Node crypto 手机客户端驱动手表协议
node --test watch/test/link-wire.test.mjs watch/test/link-server.test.mjs

# 跨语言向量（改动线格式后必须重新生成并一起提交）
node scripts/generate-link-vectors.mjs

# 手机侧：JVM 单元测试，含上面生成的向量
./scripts/build-android.ps1 -UnitTests
```

手表侧测试用的“手机”不引用 `watch/src/lib/link/` 的任何代码，而是用 Node 的
`crypto`/`zlib` 重新实现一遍线格式与会话派生；手机侧测试则直接断言
`android/app/src/test/resources/link-vectors.properties` 里由**手表实现**生成的字节。
两个方向互为对照，任何一端单独改动都会让另一端的测试失败。

有真机时另可运行 `scripts/build-android.ps1 -DeviceTests`（instrumentation）以及
debug 版 `WatchDiagnosticsActivity`，后者只读蓝牙元数据，不写健康数据。
