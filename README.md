# 活力记录 · 手机 0.14.1 / 手表 0.7.1

本仓库只包含两个应用：**手机 App（Android）** 和 **手表 App（BlueOS）**。两者之间通过原生 BLE GATT 直连，
不依赖任何扩展程序、中转 App、健康平台接口或云端服务。旧版曾附带的 OrbitV 扩展（ovpkg）已废弃并移出代码树，
见 [archive/orbitv-extension-1.2.0/README.md](archive/orbitv-extension-1.2.0/README.md)。

通过用户录入或选择的食物照片、文字、体重与运动数据，计算热量参考值和记录趋势。手机独立记录、BMI、
手动体脂率与 AI 建议继续可用；手表是手机数据的只读看板，不读取 vivo 健康数据库。

- 协议：[docs/LINK_PROTOCOL.md](docs/LINK_PROTOCOL.md)
- 实现与真机验证状态：[docs/LINK_IMPLEMENTATION.md](docs/LINK_IMPLEMENTATION.md)
- 手表使用说明：[docs/WATCH_GUIDE.md](docs/WATCH_GUIDE.md)
- 发布流程：[docs/GITHUB_RELEASE.md](docs/GITHUB_RELEASE.md)
- 热量与运动模型：[docs/CALORIE_MODEL.md](docs/CALORIE_MODEL.md)

## 手机与手表的连接

手机搜索并选择一次手表，连接凭据自动生成并保存在手机 Keystore 与手表应用私有存储，之后重连无需输入配对码。

连接是**持久**的：手机由 `connectedDevice` 前台服务持有连接，失败按 1、2、4、8 秒递增重试（上限 30 秒）；
手表端每 3 秒检查一次广播与原生服务，无人连接时重新请求广播、服务失效时重建服务。手表会话失效
（手表重启、被另一台手机占用、在手表上清除连接）时，手机会在同一条蓝牙链路上重跑握手，不重建物理连接。

只在手机点击“更新到手表”或手表点击“获取更新”后传输记录，按日期只更新新增和修改的摘要：
保存记录、切换日期、恢复连接都不会自行同步。手动更新请求会先写盘，断线、应用重启或手表重启后继续。

系统回收或挂起仍可能中断连接；真机长期后台表现尚未验证。

### 设备与缓存的两点注意

- **GT2 的广播包里不含服务 UUID**，所以手机不能只用 UUID 做扫描过滤，必须先按设备名发现。App 已经同时接受两者。
- **Android 会按已配对设备缓存整个服务目录。** 若手机一直提示“未发现活力记录直连服务”，
  说明这份缓存过期了：请在系统蓝牙设置里对这台手表**取消配对再重新配对**。关闭再打开蓝牙不足以清除它，
  也没有公开 API 可以清除。详见 [docs/LINK_IMPLEMENTATION.md](docs/LINK_IMPLEMENTATION.md)。

## 可以使用的功能

- 个人资料：年龄、身高、当前体重、目标体重、公式性别参数、保持/减脂/增重目标；已取消活动水平。
- 能量卡片同时列出当天消耗参考与登记摄入，绿色／红色差值仅等于消耗参考减登记摄入；没有登记食物的合计为 0。消耗仍按静息加已记录净活动估算。
- 体重与 BMI 为独立可拖动卡片；两列标题与数值对齐，红黄绿数轴和箭头标出当前 BMI，超出范围显示到参考边界所需增／减重公斤数，正常时不显示。
- 首页目标体重与差值显示；可独立修改 / 清除目标，不改写已记录体重。
- 首页 AI 每日建议：共用 API，饮食、运动、步数、体重、资料或目标保存成功后自动生成 / 更新；按日保存在手机，可手动重新生成。
- 每日总步数单独录入、更新、补录、删除和 CSV 导入；步长可选，缺失时只保存步数。
- 体重：按天录入/更新、趋势图、删除、CSV 导入；体重旁显示 BMI 及成人参考分类。
- 体脂率：用户手动录入测量值，按日更新、删除、趋势和备份；首页独立可拖动卡片，个人资料中也可填写。依据年龄和性别显示参考正常、偏低、偏高，颜色数轴与当前值箭头两端使用相同范围；不由 BMI 推算。
- 三餐与加餐：209 种可离线搜索的基础食物、11 个分类，自定义食物库，克数计算，编辑与删除。食物保留生熟状态、FDC 编号和营养来源。
- 本地文字匹配：例如“熟米饭150g，香蕉100g”；对未知食物、模糊份量不会自动猜测。
- 拍照/选图及复杂文字 AI 识别：配置通用接口后点击发送，只发送本次输入；结果经用户检查食物、份量与热量后保存。
- 运动：填写名称，选择有氧/无氧，按时间、距离或组数记录；可补充次数、负重、总时长及休息等说明。
- 运动热量支持本地 MET/平地步行跑步距离公式和 AI；与三餐共用 API 配置，确认结果后保存。条件不足时保存为待估算，组数不会自动换算为热量。
- 74 个运动/强度类型可搜索、按 8 个分组浏览，选择后带入名称、分类和步数重叠标记；保留 2024 Compendium 来源及编号。
- AI 配置：输入服务地址和密钥后自动读取模型列表，支持搜索选择、重新检测、失败时手动填写；只检测列表，不调用模型生成。
- 靛蓝 / 白 / 浅灰主题、简化的固定页头、自适应启动图标、42 个矢量图标、圆角卡片、分类筛选、摄入餐次进度和体重面积图。支持系统深色模式、大字体自动换行和指标卡片调整布局，各页面滚动位置独立重置。
- 步行/跑步可标记为已包含在当天总步数中，合并时取两种估算较大值，避免直接相加；该近似可能漏算运动以外的步行。
- 导出 JSON 记录备份，API 密钥不会进入导出文件。此版尚无 JSON 备份恢复入口，导出文件也可供后续迁移使用。

二级录入、餐次详情、食物选择、运动选择、AI 模型选择及配置均使用完整页面：返回按钮、滚动内容和固定底部主操作；
保存错误显示在底部按钮上方。食物和运动选择采用固定 360dp 高度的选择框，在框内连续滚动全部搜索结果，
无上一页 / 下一页按钮；搜索或切换分类时回到列表顶部。

首页模块可长按右侧六点手柄上下拖动，靠近上下边缘可滚动到其他模块；顺序保存于本机。首页已移除重置按钮与独立排序提示，
日期切换合并到紧凑顶栏，模块使用其余可用显示空间。排序不会调用 AI。

## 手表看板

iQOO WATCH GT2 / BlueOS 3 显示步数、运动、热量、三餐、体重、目标和手机 AI 建议，并可按天离线查询。
手表是只读看板，不修改手机记录；数据包含步数、运动、三餐摘要、能量参考、体重/BMI、手动体脂率、目标与已保存的
AI 建议，**不包含照片、API 密钥或连接凭据**。更新按钮只在手表主页最底部，仅在请求更新或正在保存时申请最多 150 秒亮屏。

## 使用顺序

1. 打开“我的 → 编辑个人资料”，填写真实资料与目标体重；首页“目标体重”卡片也可单独设置或清除目标。
2. “饮食”首页只展示早餐、午餐、晚餐及各餐已记录食物。点击某一餐进入详情，再点“添加食物”或右上角＋，选择食物库或照片 / 文字识别；也可手动录入。加餐入口在餐次详情中，旧加餐记录保留。
3. “运动”首页只展示已添加的运动，右上角＋打开新增页面；点击卡片查看详情、删除，编辑图标修改。每日步数从首页步数卡片或“录入／修改每日步数”按钮填写，也可通过“快速记录 → 步数”进入；“趋势”中记录体重。运动填写后点击本地公式或 AI，核对估算再确认保存；也可先存待估算记录。
4. 在“我的”页右上角设置或“我的 → AI 服务”填写 HTTPS 服务地址（例如基础地址 `/v1`，或完整 `/chat/completions` 地址）和自己的密钥。停顿约 1 秒自动检测模型；点击“选择模型”搜索，也可手动填写。保存后供三餐与运动共用。
5. 用文字描述或照片点击“AI 识别这一餐”，检查预览结果后确认入账。未配置 API 时，离线记录和食物库照常可用。
6. 打开手表“活力记录”，在手机“我的 → 手表直连”允许附近设备权限，点击“搜索手表”并选择 GT2。首次连接后凭据自动保存；需要时点“连接自检”验证链路，点“更新到手表”传输记录。
7. 配置 API 后，保存饮食 / 运动 / 步数 / 体重 / 体脂率 / 资料或目标会自动更新对应日期的建议，发送资料（含目标体重）、当天餐食/活动及近 7 日体重、体脂率摘要。连续保存合并，失败不影响记录；也可首页手动重新生成。界面只展示简短的吃饭与锻炼建议，结果按日保存在手机，不自动改写记录，也不自动同步手表。

图片识别需要支持图像输入的模型；纯文本模型可用于饮食文字和运动。接口采用兼容 Chat Completions 的
messages/text/image_url 结构；不支持所有供应商的私有协议。尚未使用真实服务商/密钥进行在线联调。
食物请求仅发送本次文字/照片，运动请求仅发送本次运动与当时体重，不发送年龄、身高或历史记录。
每日建议会在保存或删除健康记录后自动更新，也可按钮重试；按界面提示发送资料、对应记录日摘要与该日之前 7 日内的体重记录，使用同一 API。

模型检测依照 [模型列表协议](https://developers.openai.com/api/reference/resources/models/methods/list)，
使用同一个基础路径下的 `GET /models`，仅向用户指定服务发送认证头，不发送健康记录或在正文/URL 携带密钥；不跟随重定向。
模型列表不保证支持图片或本应用请求格式，需按服务商说明选视觉模型。未开放列表或认证失败时有提示，仍可手动填写。
更换域名会清空当前表单中的密钥输入，取消则保留此前已保存配置。

## CSV 上传格式

UTF-8 编码，日期 YYYY-MM-DD；最多 1 MB / 1000 条记录。选择文件后确认导入。

体重：

```csv
date,weight_kg
2026-10-11,70.5
2026-10-12,70.0
```

运动：

```csv
date,name,minutes,kcal
2026-10-12,步行,30,100
```

每日步数（步长列可省略，也可留空）：

```csv
date,steps,step_length_cm
2026-10-11,0,
2026-10-12,6000,70
```

列名固定。体重和步数文件拒绝重复日期；与已保存的同日记录相比，新文件值会更新原值。同一运动文件再次导入使用稳定记录 ID，
不重复累加；同一文件中的两条相同运动仍可保留为两条。旧运动 CSV 热量口径未知，只保留记录，不计入净消耗，需编辑后重新估算。
任何一行无效均拒绝整个文件，避免部分入账。

## 计算与食物来源

公式、运动估计、食物来源和参考值边界见 [CALORIE_MODEL.md](docs/CALORIE_MODEL.md)。209 种基础食物不是完整中国菜谱，
复杂菜品及油、酱料仍需根据实际份量填写或识别后修正。

食物库已作为 foods.json 打包，无需账号、API 或联网查询。生成脚本 scripts/generate-food-catalog.py 从官方
USDA SR Legacy CSV 压缩包提取数值与来源编号，不由语言模型生成营养数值。

运动库由 scripts/generate-exercise-catalog.py 核对 [2024 Adult Compendium](https://pacompendium.com/) 官方条目后生成，
选择项保存在 scripts/exercise-selection.json。JSON 与计算模块使用同一组生成的数据，扩充时不能任意编写 MET 值。
跑步/步行之外的距离或无时长组数不会强行套用距离公式。

BMI 依据[国家卫健委成人标准](https://www.nhc.gov.cn/jkj/s10045/200907/943149b60565497bb3e25783a0e3de68.shtml)。
体脂率使用 [Gallagher 等 2000 年论文](https://pubmed.ncbi.nlm.nih.gov/10966886/) 的 Table 4 年龄/性别参考范围，
适用于 20–79 岁资料，属于暂定参考而非诊断；[全文 Table 4](https://zakboekdietetiek.nl/wp-content/uploads/2015/11/Am-J-Clin-Nutr-2000-Gallagher-694-701.pdf)。
年龄或资料不适用时只显示测量值。参考状态不能代表整体健康。

## 构建、测试与安装

Android Studio 打开 `android`；Gradle 使用 JDK 17，API 36 / Build-Tools 36.0.0。手表用 BlueOS Studio 的
`blueos-pack` 构建。

```powershell
./scripts/build-android.ps1                 # 只编译打包
./scripts/build-watch.ps1                   # 只构建 RPK
./scripts/build-android.ps1 -UnitTests      # 额外运行 JVM 单元测试
./scripts/build-watch.ps1 -Install          # 额外把 RPK 推到手机 Download
./scripts/build-android.ps1 -Install        # 额外安装并启动到已连接手机
./scripts/build-android.ps1 -DeviceTests -Install
```

默认脚本只编译和打包，不运行测试；`-UnitTests` / `-DeviceTests` 为显式测试选项。手表编译器即使退出码为 0
但打印 `[ERROR]`，构建脚本也会拒绝复制产物。

产物：

- `artifacts/vitality-phone-0.14.1.apk`（调试签名，沿用本机既有签名）
- `artifacts/health-vivo-watch-0.7.1.rpk`（BlueOS debug 构建）

RPK 需要装到手表上。仓库不再包含任何扩展程序，可继续用第三方 OrbitV 应用作为**安装器**（它不参与运行），
或用 BlueOS Studio 的调试通道安装。

### 测试

```powershell
# 手表侧：用独立的 Node crypto 手机客户端驱动手表协议（40 项）
node --test watch/test/*.test.mjs

# 跨语言线格式向量：改动协议后重新生成并一起提交
node scripts/generate-link-vectors.mjs

# 手机侧：JVM 单元测试，含上面生成的向量（49 项）
./scripts/build-android.ps1 -UnitTests
```

手表侧测试用的“手机”不引用 `watch/src/lib/link/` 的任何代码，而是用 Node 的 `crypto`/`zlib` 重新实现线格式与会话派生；
手机侧测试直接断言 `android/app/src/test/resources/link-vectors.properties` 里由**手表实现**生成的字节。
两个方向互为对照，任何一端单独改动都会让另一端的测试失败。

### 本机数据与兼容性

本机数据库为 `fitness-local.db`，当前版本 4；从 0.3.0 的版本 1 迁移到版本 2，保留个人资料、体重、三餐、自建食物和历史运动，
移除活动系数并增加步数表；0.7.0 从版本 2 升级到 3，仅增加可空的目标体重字段。旧手填运动热量口径未确认，不参与净消耗；
旧 MET 值保留，步行/跑步默认标记包含在总步数中，可编辑。没有破坏性迁移或清空数据操作。

升级到 0.14.1 后蓝牙连接信息使用新的存储键（手机 `link_connection` / `link_secret`，手表 `fitness.link.identity.v1`），
所以需要一次首次连接；手表按天缓存（`fitness.index.v3` / `fitness.day.v3.*`）原样保留，历史日期不需要重新同步。
旧的按天版本账本会从 `orbitv_companion` 一次性迁移到 `watch_revision_ledger`，避免升级后重传全部日期。

健康数据保存在应用私有目录，关闭 Android 自动备份；API 密钥用 Android Keystore 加密。应用不会读取计步、位置或其他 App 的私有数据。
调试构建附带 PreviewActivity，用演示资料和模拟模型列表检查真实页面，禁止保存、不读取真实密钥、不打开正式数据库；发布构建不包含这个入口。
预览截图是演示数据。正式入口为 MainActivity。

### 历史记录

旧版曾使用 OrbitV 扩展（ovpkg）作为手机与手表之间的中转。该方案已在 0.13.0 被直连 BLE 取代，
其工程、手机端桥接、手表端内置 SDK、脚本与文档一并移出代码树，存放在
[archive/orbitv-extension-1.2.0/](archive/orbitv-extension-1.2.0/README.md)（该目录不纳入版本管理），
需要时可用 `git show <commit>:<path>` 从历史中取回。

- 迁移前的真机与模拟器验证记录：[docs/DIRECT_LINK_DEVICE_VALIDATION.md](docs/DIRECT_LINK_DEVICE_VALIDATION.md)、
  [docs/DIRECT_LINK_SIMULATOR_VALIDATION.md](docs/DIRECT_LINK_SIMULATOR_VALIDATION.md)
- 更早的 BLE 演进记录：[docs/WATCH_BLE_HISTORY.md](docs/WATCH_BLE_HISTORY.md)、[docs/PROTOCOL.md](docs/PROTOCOL.md)
- 历史发布记录：[docs/releases/v0.12.3.md](docs/releases/v0.12.3.md)
- 已发布的首个预发布：[v0.12.3](https://github.com/shuang-ying/HealthVivo/releases/tag/v0.12.3)（手机 0.12.3、手表 0.5.3、扩展 1.2.0）

仓库：[shuang-ying/HealthVivo](https://github.com/shuang-ying/HealthVivo)。

## 许可

项目原创代码采用 [MIT License](LICENSE)，Copyright (c) 2026 shuang-ying。第三方 SDK、模板、依赖和数据遵循各自条款，
详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
