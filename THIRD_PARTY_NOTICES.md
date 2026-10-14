# 第三方组件与数据说明

本仓库 `LICENSE` 中的 MIT 授权适用于 shuang-ying 有权授权的项目原创代码、文档和资源，包括 Android App 和 BlueOS 手表页面的原创业务实现。第三方已有代码、SDK、模板、依赖和数据的权利归原权利人，继续遵循各自条款；不能据项目根目录的 MIT 声明推断第三方材料也采用 MIT。

## OrbitV SDK 与扩展模板（已移出版本管理）

手机 0.13.0 起，手机与手表的同步运行路径不再使用 OrbitV SDK 或扩展程序；0.14.1 及以后（含 1.0.0）的直连实现完全不涉及它们。
旧扩展的工程、手机端桥接、手表端内置 SDK、脚本与文档已移出代码树，存放在 **不纳入版本管理** 的
`archive/orbitv-extension-1.2.0/`，仅作为本机历史参考，不随仓库分发。

以下记录说明这些第三方材料原先的来源与授权状况，以便需要时追溯；它们不再出现在任何构建产物中。

- 来源：[OrbitV 开发者下载页](https://doc.developers.orbitv.top/downloads)。
- 曾保留的 BlueOS SDK 版本：1.0.0（原路径 `watch/src/common/orbitv/`，手表构建始终排除它）。
- 手机扩展工程基于该站点公开模板；原 `orbitv/common/` 中的运行环境、页面栈和结果展示辅助代码，及应用入口、路由、类型定义等保留模板来源。项目的配对、连接检查、业务转发及健康数据同步实现为本项目添加的业务代码。
- 这些 SDK 与模板下载包未附明确的 LICENSE 文件，尚未核实再分发或再许可授权。这些第三方原有部分明确不由项目 MIT 许可证重新授权。公开提供下载本身不能代替明确许可；拟再分发或商用整套工程时，应先向 OrbitV 作者确认相关授权。
- 本项目未使用反编译的专有 OrbitV 应用代码作为 SDK 实现。
- 第三方 **OrbitV 手机应用** 仍可作为把手表 RPK 安装到设备的工具，按各供应商条款使用，本项目不重新授权或发布该软件本体。

## FoodData Central 食物数据

- 数据来源：USDA FoodData Central / SR Legacy 2018。
- 使用文件：`android/app/src/main/assets/foods.json`；生成脚本为 `scripts/generate-food-catalog.py`，其中保留 FDC 编号与来源。
- USDA 声明其 FoodData Central 数据属于公有领域，并以 CC0 1.0 发布；项目没有将这些原始数据改为 MIT 授权。项目的中文显示名称、别名、分类整理和生成代码属于本项目添加的内容。
- 来源声明：U.S. Department of Agriculture, Agricultural Research Service, Beltsville Human Nutrition Research Center. FoodData Central. https://fdc.nal.usda.gov/ 。
- 依据：[FoodData Central Licensing](https://fdc.nal.usda.gov/)；[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)。

## Compendium 运动数据

- 来源：[2024 Compendium of Physical Activities](https://pacompendium.com/)。
- 使用文件：`android/app/src/main/assets/exercises.json`、`android/app/src/main/java/com/lrh/healthvivo/fitness/ExerciseLibrary.kt`；对应生成脚本和选择清单保留活动编号、MET 与来源链接。
- 官方使用说明允许商业使用，要求保留引用，并要求不要更改 MET 值或合并不同 MET 等级的活动。本项目保留各强度条目的代码、MET 和来源；中文显示名称与分组是本项目的整理。
- 引用：Herrmann SD et al. 2024 Adult Compendium of Physical Activities: A third update of the energy costs of human activities. Journal of Sport and Health Science. 2024;13(1):6–12.
- 原数据与网站材料不由本项目 MIT 声明重新授权，使用条件以来源说明为准。

## 软件依赖

依赖版本以 Gradle 配置和 `watch/package.json` 为准。下表列出主要直接依赖的许可来源，不替代各依赖原文与完整的传递依赖清单。分发包含第三方依赖的构建产物时，应保留对应版权、许可证和必要通知。

| 组件 | 许可／来源 |
|---|---|
| AndroidX、Compose、Room | [AndroidX Apache-2.0](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| Kotlin | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| kotlinx.coroutines | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| Vue | MIT；Copyright (c) 2018-present, Yuxi (Evan) You；[原许可](https://github.com/vuejs/core/blob/main/LICENSE) |
| Vue Router | MIT；Copyright (c) 2019-present Eduardo San Martin Morote；[原许可](https://github.com/vuejs/router/blob/main/LICENSE) |
| Konsta UI | [MIT](https://github.com/konstaui/konsta/blob/master/LICENSE) |
| Tailwind CSS 与 Vite 集成 | [MIT](https://github.com/tailwindlabs/tailwindcss/blob/main/LICENSE) |
| Vite | [MIT](https://github.com/vitejs/vite/blob/main/LICENSE) |
| Vue Vite 插件 | [MIT](https://github.com/vitejs/vite-plugin-vue/blob/main/LICENSE) |
| Archiver（扩展包构建工具） | [MIT](https://github.com/archiverjs/node-archiver/blob/master/LICENSE) |

BlueOS Studio、blueos-pack、Android SDK 和 OrbitV 手机应用为外部开发工具／运行环境，按各供应商的条款使用，本项目不重新授权或发布这些软件本体。
