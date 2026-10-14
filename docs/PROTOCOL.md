# 手机到手表直连协议 v3（历史记录，已停用）

> 本文记录 0.10.0 / 0.3.0 时期的协议，包括当时的直连帧格式与更早的 OrbitV 中继协议。
> 这些实现都已不在代码树中：当前协议见 [活力直连协议 v1](LINK_PROTOCOL.md)，
> 当前实现见 [手表直连实现](LINK_IMPLEMENTATION.md)，旧扩展源码存放在不纳入版本管理的
> `archive/orbitv-extension-1.2.0/`。

手机0.10.0 / 手表0.3.0使用 BLE GATT。服务 UUID 为9f7c7200-1771-42b9-bc36-d88bfb0952a0；写入特征将首段7200换成7201，状态读取特征换成7202。BlueOS 属性枚举为写入[5]、读取[4]，不是 Android 属性位掩码。广播由 GattServer.startAdvertising 开启。

## 分包

所有整数采用大端无符号表示；Android Int 的原始32位作为传输标识。

| 操作 | 二进制布局 |
| --- | --- |
| BEGIN | 1字节操作1 + 4字节id + 1字节kind + 4字节总长度 |
| DATA | 1字节操作2 + 4字节id + 4字节offset + 数据 |
| END | 1字节操作3 + 4字节id |

kind1为256字节RSA会话密文；kind2为加密的UTF-8每日摘要。最大密文200KiB，DATA必须连续且offset准确。手机请求MTU，应用写入长度限制20–180字节，每次WRITE_TYPE_DEFAULT写入需成功回调后才继续。90秒无新数据即清理组装缓存；未收到END及完整数据不能覆盖旧看板。

## 会话与摘要

持久构建身份为RSA2048。会话key为手机随机32字节，nonce为随机16字节，JSON中用Base64编码。RSA使用OAEP SHA-256，手机显式指定MGF1 SHA-256；BlueOS用公开的RSA/None/OAEPwithSHA-256andMGF1Padding，原生互操作仍待真机确认。

确认码是SHA-256(base64key + ":" + base64nonce)的前3字节大写十六进制。手表人工确认后才允许发送摘要。MAC密钥为SHA-256(base64key + ":vitality-mac:" + base64nonce)。

摘要密文为IV16 + AES256-CBC/PKCS5Padding密文 + HMAC-SHA256。MAC覆盖4字节传输id、IV和密文，必须先验证MAC再解密。每次发送使用独立随机IV。回执认证绑定本次会话，拒绝其他对端。

摘要仍为schema2/client=com.lrh.healthvivo.watch，包括当天步数、三餐、运动、热量、体重/目标、AI建议，以及可选body（BMI/参考状态、用户实测体脂率/测量日期/参考状态）。旧schema2缓存缺少body时仍能显示。缺失为null，真实零为0；revision随手机持久计数器增加。

## 读取回执

会话建立前返回1字节0；握手解密失败返回1字节5。建立会话后回执固定20字节，兼容默认MTU23：
state1字节 + id4字节 + progress2字节 + HMAC前13字节。MAC覆盖前7字节，手机必须验证。

| state | 意义 |
| --- | --- |
| 1 | 等待手表人工允许 |
| 2 | 已允许会话 |
| 3 | 正在接收或解密/保存 |
| 4 | 已完整保存，对应id |
| 5 | 失败，progress携带阶段码 |
| 6 | 手表请求手机刷新 |
| 7 | 手表拒绝会话 |

写入成功仅表示收到分包。只有storage.set成功、格式有效且版本新于缓存才返回state4。手机发送结束后等待30秒；无匹配认证回执时不显示同步完成。隐藏手表应用释放GATT、清除会话和组装状态，持久看板缓存保留。

手表刷新不得覆盖尚未被手机读到的握手/保存/失败回执。state2、4、5保持原state/id，progress的0x8000位表示请求刷新；阶段码或进度使用低15位。手机可先消费原回执，再排入刷新任务；下一次BEGIN清除此标记。

# 历史官方中继协议 v2

旧版手机0.9.0 / 手表0.2.0使用只读每日摘要。`client` 固定为 `com.lrh.healthvivo.watch`。通过官方智能终端SDK的 `ACTION_DEVICE_BUSINESS_DATA` 与 BlueOS connectionManager 传输JSON。

| type | 用途与字段 |
| --- | --- |
| fitness.dashboard.pull.v2 | 手表请求手机今日摘要，带 client |
| fitness.dashboard.frame.v2 | 手机发送分片：id、revision、checksum、index（从0开始）、total、text、client |
| fitness.dashboard.ack.v2 | 手表存储成功后的确认：对应id、revision、checksum、stored:true、client |

摘要 `schema:2` 包含日期、生成时间、版本号、步数、体重与目标、energy、meals、exercises和advice。缺失数值为JSON null，真实零保留为0；只有完整摘要经过格式和校验检查、并成功保存后才更新手表展示和发回ACK。任何部分分片、旧版本、校验错误或存储失败都不覆盖已保存摘要。

`revision` 是手机私有计数器与当前时间取较大值，重启后继续增加。最多256片，每片不超过512个UTF-16代码单元，手机切分时避开高低代理对之间的边界。总文本不超过128Ki代码单元。校验初始5381，按UTF-16代码单元滚动 `hash = hash * 31 + code`，结果按32位无符号小写十六进制编码；仅检测传输完整性，通道鉴权由官方 SDK 和签名配对提供。

手表分片组装超时25秒，页面26秒清理计时器；手机发送完后最多等15秒存储回执。失败时可手动刷新重传完整新版本，不宣称已经同步。手机合并快速连续变化；关闭同步会取消正在发送的任务。第一版不回传运动和身体传感器数据。

## 历史传感器验证协议 v1（当前看板不使用）

UTF-8 JSON，顶层 `{ "type": "fitness.snapshot.v1", "data": { ... } }`。
`data` 必须带 `id`（不超过 128 字符的稳定标识）、`source: "watch"`、毫秒 `timestamp`。

| type | 载荷 |
| --- | --- |
| fitness.ping.v1 | 通道测试的来源、时间、标识 |
| fitness.snapshot.v1 | `heartRate: {value, timestamp}` 或 null；`todaySteps: {value, startTime, endTime, timestamp}` 或 null |
| fitness.workout.v1 | `workout: {kind, startedAt, endedAt, activeMillis, steps, stepObserved, status}` |
| fitness.ack.v1 | `data: {id, stored: true}`；手机在 Room 事务成功后发送 |
| fitness.error.v1 | `data: {stored: false}`；不返回输入内容或 SDK 密钥 |

运输成功不代表落库成功。手表持久化待发送消息后再发送，收到对应标识的存储确认才从队列移除。
队列最多 30 条，满时停止追加并提示同步；不覆盖未确认记录。
手机按标识去重，同一标识但不同原文拒绝写入。
手机不把手表当日累计步数与手机步数累加。

当前源代码中已实现协议与事务测试。vivo 通道实际回调格式和应答是否到达手表仍待双端真机验证，不能用单元测试代替。
