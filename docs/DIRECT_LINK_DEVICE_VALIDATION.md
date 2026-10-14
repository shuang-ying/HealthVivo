# X80 / iQOO WATCH GT2 独立连接验证

日期：2026-10-13。按 Android 原生 RFCOMM / GATT、BlueOS BLE 和屏幕亮度文档制作并运行诊断。手机为 vivo X80，Android 16 / API 36；手表为用户的 iQOO WATCH GT2 / BlueOS 3。

结论：**物理 X80 与真实 GT2 的独立 BLE 双向测试已通过**。停止 OrbitV 后，X80 在诊断刷新旧 GATT 缓存后收到两次匹配当前会话和序号的 PONG；随后不调用缓存刷新，普通连接再次收到两次匹配 PONG。电脑独立客户端也已通过。证明基础收发可行，不代表正式健康数据同步、手表后台和长期持久连接已经交付。

## 现有证据

| 检查 | 本次结果 | 结论的边界 |
| --- | --- | --- |
| 暂停 OrbitV 后，手机独立连接标准 SPP | 通过，667 ms | 只证明原生 RFCOMM 可以建连。发送 0 字节，不能证明 GT2 RPC、应用路由或健康数据同步 |
| 向已配对 GT2 地址建立 BLE GATT | 通过，连接状态 2 / ATT status 0 | 只查询服务 UUID，没有读取系统健康特征值 |
| 发现独立探测服务的广播 | 首轮及更新 0.1.2 后均超时 | 每轮 20 秒，未发现 `fa954400-48d6-4a12-b74b-4c5b5170360e`；首轮用户明确提到手表曾熄屏 |
| 通过已配对地址查询探测服务 | 未通过 | 服务目录含旧实验服务 `9f7c7200-1771-42b9-bc36-d88bfb0952a0` 六次，不含新探测服务 |
| Windows 独立客户端、无缓存 GATT 查询 | 重启前未发现，重启后发现 | GT2 普通重启后六个旧服务消失，新探测服务出现 |
| Windows ↔ 真实 GT2 原生 GATT 双向消息 | 通过 | 重启后读到测试问候，写入两次随机会话/序号 PING，并读回匹配 PONG；OrbitV 停止运行 |
| X80 缓存目录与 Windows 对照 | 已确认差异 | 两端设备地址摘要一致。Windows 无缓存查询可见新服务，X80 重连仍读到旧服务目录 |
| X80 ↔ 真实 GT2 原生 GATT 双向消息 | 通过，2 次匹配 PONG | 本轮先用非公开接口诊断刷新元数据缓存，随后发现新服务；不是正式产品方案 |
| X80 不刷新缓存的普通重连 | 通过，再次 2 次匹配 PONG | 新服务目录已建立后，公开 `connectGatt` / `discoverServices` / 读写接口可完成本次普通连接；不等于后台自动重连测试 |
| GT2 原生应用长期后台、熄屏收发 | 未验证 | 先完成前台 PING/PONG 才能进入后台持续性测试 |

测试前确认活力记录具有附近设备权限；vivo 健康的 Bluetooth 权限处于关闭状态。每轮独立收发测试暂停 OrbitV。OrbitV 仅用于把独立测试 RPK 安装到手表。

0.1.2 测试页通过 `brightness.setKeepScreenOn({ keepScreenOn: true })` 请求常亮 90 秒，点击常亮提示可重新计时。`onHide` / `onDestroy` 和计时结束均解除常亮。用户已回复“已请求广播，并显示已保持亮屏”，收到回复后立即启动 Windows 查询。常亮有时限，这项确认不代表之后的所有轮次都持续亮屏。

Windows 原生查询通过 Bleak 0.22.3 的 WinRT 后端执行，显式设置 `use_cached_services=false`。核对安装版本的后端源码，参数会传给原生 `GetGattServicesAsync(BluetoothCacheMode.UNCACHED)`。重启前查询有六个重复旧服务，没有测试服务；GT2 普通重启后查询到七个服务，其中包含新测试服务，六个旧实验服务消失。随后完成两次合成 PING/PONG，证明真实手表页收发回调可执行。没有读取任何系统健康特征值。电脑蓝牙原为 Off，本次临时启用后已恢复 Off。

重启后再次做纯目录查询，Windows 的设备地址 SHA-256 摘要与手机已配对设备、手机扫描所得设备的摘要一致，但两端服务表不同。此证据排除了不同地址，并把 X80 服务缓存确定为当前障碍。诊断入口提供显式 `refresh_cache=true` 选项，通过 AOSP 的 `BluetoothGatt.refresh()` 清除该设备的服务元数据缓存；AOSP 标记为 `@hide`，**非公开 SDK 接口，只用于诊断，不能作为正式产品连接策略**。没有删除蓝牙应用数据或取消配对。

刷新后的手机原生结果：`cacheRefreshReturn=true`、服务发现/读/写状态均为 0，发现新测试特征，`pongMatches=2`、`applicationCallbackVerified=true`。下一轮不带 `refresh_cache` 的普通连接也得到 `pongMatches=2`，该轮结果没有缓存刷新字段。两个轮次使用不同 `runId`、不同随机合成会话，不把上轮 PONG 当成本轮确认。测试只向专用 UUID 写合成测试包，未上传饮食、身体数据或 AI 密钥。

## 测试程序

手机调试入口：`android/app/src/debug/java/com/lrh/healthvivo/debug/DirectGattProbeActivity.kt`。只在 debug manifest 注册，最多运行 60 秒，退出关闭 GATT。只保存接口状态、服务 UUID、时间和合成消息匹配计数，不访问健康数据库。

手表独立源码：`probes/blueos-direct`，包名 `com.lrh.healthvivo.watch.directprobe`，名称“通信接口验证”，版本 0.1.2。通过 `scripts/prepare-direct-watch-probe.ps1` 在有标记的目录 `D:\AgentProject\HealthVivoDirectProbe` 编译。

服务：`fa954400-48d6-4a12-b74b-4c5b5170360e`；特征：`fa954401-48d6-4a12-b74b-4c5b5170360e`，按官方属性枚举 `[4, 5]` 提供读写。

验证顺序：扫描或已配对地址 → 连接 → 服务发现 → 读取测试标记 → 写 `PING:<随机六位十六进制会话>:01` → 读对应 `PONG:...:01` → 第二次匹配回应。测试标记初始为 `PROBE:1`，其他客户端留下的严格格式合成 PONG 也可作为标记，但不计入本次 ACK；本次 ACK 必须匹配新生成的会话和序号。任何一步失败均不算通过。ATT 写入成功不是业务确认；两次匹配的 PONG 才能证明测试页执行了收发逻辑。

GT2 实际广播没有包含新服务 UUID；Windows 是按 WATCH GT2 名称发现设备后查询服务目录。手机不能只用服务 UUID 的扫描过滤器决定是否存在手表，需要已配对设备入口或其他合法发现方式。

BlueOS 的 `startAdvertising()` 文档未定义布尔返回值；void 仅记为已请求，实际广播需要手机扫描证明。原生模拟器接口检查出现 `le_gatt_characteristic_read nullptr`，不能直接推断物理 GT2 会有相同错误。

## 重现

1. 保持手机 USB 调试连接，手表“通信接口验证 0.1.2”页面可见，并确认常亮提示。
2. 停止 OrbitV，运行手机原生探测。独立 RPK 安装完毕后，运行测试不依赖 OrbitV 或扩展程序。

```powershell
& 'D:\Android\Sdk\platform-tools\adb.exe' -s 10AC610LZC001DX shell am force-stop com.yyh.orbitv
& 'D:\Android\Sdk\platform-tools\adb.exe' -s 10AC610LZC001DX shell am start -f 0x18000000 -n com.lrh.healthvivo/.debug.DirectGattProbeActivity
```

3. 若广播扫描失败，再单独运行已配对地址策略。该策略只选择名字匹配 WATCH GT2 的已配对设备，服务发现后仍必须找到上述独立特征、通过测试问候才能写合成 PING。

```powershell
& 'D:\Android\Sdk\platform-tools\adb.exe' -s 10AC610LZC001DX shell am start -f 0x18000000 -n com.lrh.healthvivo/.debug.DirectGattProbeActivity --es strategy bonded
```

4. 读取结果；每次需核对新的 `runId` 和 `completed=true`，不能把启动后立即读取到的旧缓存当成本次结果。

```powershell
& 'D:\Android\Sdk\platform-tools\adb.exe' -s 10AC610LZC001DX shell run-as com.lrh.healthvivo cat cache/direct-gatt-probe.json
```

主要证据在未提交 Git 的 `artifacts/direct-link-probe/`：

- `gt2-spp-physical-2026-10-13.json`：667 ms SPP 建连和 0 字节发送。
- `gt2-gatt-scan-2026-10-13.json`、`gt2-gatt-bonded-2026-10-13.json`：首次广播扫描、已配对地址发现结果。
- `gt2-gatt-awake-scan-2026-10-13.json`、`gt2-gatt-awake-bonded-2026-10-13.json`：请求安装常亮版后的两轮结果；文件名 awake 不代表已经测得屏幕连续可见。
- `gt2-gatt-confirmed-awake-2026-10-13.json`：收到用户常亮状态确认后重新查询的手机结果；其中的状态来自用户报告，不是屏幕传感器测量。
- `gt2-windows-before-reboot.json`：重启前独立 Windows 客户端的无缓存失败结果。
- `gt2-windows-uncached-gatt.json`：重启后独立 Windows 客户端，测试服务发现及两次真实 PONG 成功。
- `gt2-windows-catalog.json`：重启后纯目录查询，地址摘要用于与手机对照，不写消息。
- `gt2-gatt-after-reboot-2026-10-13.json`：重启后 X80 普通连接仍返回旧缓存目录。
- `gt2-gatt-refreshed-2026-10-13.json`：诊断刷新缓存后，X80 与真实 GT2 的两次匹配 PONG。
- `gt2-gatt-public-reconnect-2026-10-13.json`：不调用缓存刷新的下一轮普通连接，另两次匹配 PONG。
- `blueos-native-result.json`：官方原生模拟器执行页面与接口调用结果，无线传输标志为 false。

## 后续判断

重启后的真实 GT2 双向回调已经由 Windows 和 X80 验证。正式方案还需处理服务生命周期、公开接口下的发现/服务变更，以及后台持久性、断线恢复、健康数据帧和用户触发同步语义；不能把调试缓存刷新当成这些问题的解决方案。建议正式手表端复用一个稳定的 GATT 服务生命周期，并验证服务变更处理，避免页面切换反复添加同名服务；手机端需支持已配对 GT2 的连接入口，不能只等广播中的服务 UUID。保持连接或心跳仍不得触发自动健康数据同步。

Windows 检查器：`scripts/probe-gt2-windows-gatt.py`，依赖见 `probes/requirements-windows-gatt.txt`。只匹配测试服务或 WATCH GT2 名称，不保存设备地址或其他附近设备信息；只允许在测试问候匹配后写合成消息。

参考：[Android RFCOMM 建连](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)、[Android BLE 收发](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)、[Android 服务变化回调](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback#onServiceChanged(android.bluetooth.BluetoothGatt))、[BlueOS BLE](https://developers-watch.vivo.com.cn/api/system/bluetooth/)、[BlueOS 常亮接口](https://developers-watch.vivo.com.cn/api/system/brightness/)、[Windows 指定缓存模式的服务发现](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothledevice.getgattservicesasync)、[AOSP 非公开 GATT 缓存刷新源码](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothGatt.java)。
