# vivo 智能终端 SDK

把 vivo 官方提供的 `device-rpc.aar` 放在此目录。SDK 二进制不提交到版本库。
把 `android/vivo-sdk.local.properties.example` 复制为 `android/vivo-sdk.local.properties`，填写手机应用的 App ID 和智能终端 SDK 密钥。

这不是 Health Kit 云云接入的 App Secret。不要混用两套凭据。
当前基础原型不依赖 SDK；取得 AAR 后按实际版本接入并执行通信测试。
