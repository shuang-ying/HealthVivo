# 个人账号：手机本地获取数据的替代路线

2026-10-12：用户实测 vivo Health Kit 后台拒绝个人认证账号登录。此前待申请的本地 Health Kit 路线不能作为个人账号当前可用的实现前提。

## 本次 X80 检查

- vivo 健康包名 com.vivo.health。
- ADB 当前为 shell 用户，不是 root；run-as 返回 package not debuggable。
- 安装包声明的 STEP 权限为 signature|privileged，main.provider.permission 为 signature。不能通过普通 Android 运行时权限申请得到这些权限。
- 已发现步数、运动及分享相关 Provider 的注册信息。仅存在组件不证明对第三方开放，也不证明允许全量数据读取。本次没有访问受保护的数据或尝试绕过权限。
- 未确认不需要平台审批的通用健康数据读取 API；未确认可导出全部饮食和运动记录的入口。

## 可采用的路线

| 路线 | 可行性与边界 |
|---|---|
| 用户主动分享或导出文件 | 若 vivo 健康实际提供相应入口，可导入用户授予访问的图片/文件；需核实格式和字段，不能先承诺全部历史数据 |
| 用户选择截图，手机本地 OCR | 可以开发，按屏幕实际呈现的日期、食物、份量、热量、步数和运动指标识别；需预览确认，缺失字段仍缺失，不把点数换成千卡 |
| 用户主动授权的页面读取 | 需要验证页面文本是否可读、屏幕截图是否允许；只能覆盖用户打开并可见的页面。页面改版可能导致失效，不能等同数据库同步 |
| 普通 App 直接读 vivo 健康私有数据库 | Android 沙箱不提供这种通用访问权限。ADB / Shizuku 也不等于取得所有应用私有文件的权限 |

建议下一阶段优先验证截图/分享导入：原图由用户选择，中文识别在手机执行，结果标为“vivo 健康截图导入”，显示原文和日期供用户确认，避免与 SDK 同步混淆；同一截图与同一记录需去重。部分截图和食物照片不足以还原一天完整摄入，因此不得据此标记整日完整或计算可靠热量缺口。

本文件是路线论证，尚未新增截图导入代码，也未启用无障碍服务、Root 或后台屏幕采集。是否需要全自动同步，还是接受用户主动导入，将影响最终实现方式。

参考：[Android 私有文件](https://developer.android.com/training/data-storage/app-specific)、[签名权限](https://developer.android.com/guide/topics/permissions/overview)、[文件选择器](https://developer.android.com/guide/topics/providers/document-provider)、[中文图片文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。
