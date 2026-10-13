# OrbitV BlueOS SDK

这是用于 **BlueOS 官方项目**的插入式 SDK，不是手表项目模板。

开发者应先在 BlueOS Studio 中使用官方模板创建应用，OrbitV 只提供需要插入现有项目的 SDK 和代码片段。

## 接入

### 1. 复制 SDK

将本目录的：

```text
src/common/orbitv/
```

复制到官方工程的：

```text
<BlueOS 项目>/src/common/orbitv/
```

### 2. 初始化 `app.ux`

将 [snippets/app.ux](snippets/app.ux) 中的 import、`appInfo`、`orbitv` 和 `export default` 字段合并到现有 `src/app.ux`。不要覆盖官方模板中已有的生命周期或业务代码。

SDK 会直接读取 `manifest.json.package`，开发者不需要再填写一次包名。

### 3. 合并 `features`

将 [snippets/manifest-features.json](snippets/manifest-features.json) 中的三项能力合并到官方工程 `manifest.json.features`。

### 4. 在页面中使用

```js
export default {
  data: {
    orbitv: null,
  },

  onInit() {
    this.orbitv = this.$app.$def.orbitv
  },

  async loadPhoneData() {
    const response = await this.orbitv.http.request('/api/phone-data', {
      responseType: 'json',
    })
    console.log(response.data)
  },
}
```

## 文件下载

```js
const selectedFiles = await this.orbitv.file.list()

// 每一项是用户在手机扩展程序页面中选择的文件：
// { name, size, mimeType, createdAt }
const selectedFile = selectedFiles[0]

if (selectedFile) {
  const localUri = await this.orbitv.file.download(selectedFile)
  console.log(localUri)
}
```

开发者不需要读取或拼接内部文件标识。`file.download(file)` 会内部处理 GET、Range、下载 token 和手表保存路径。

## HTTP 方法

- 读取 JSON、列表和文件：GET；
- 手表向手机提交 JSON 或上传文件：POST；
- Range 只用于 GET 下载，由 SDK 和 OrbitV 自动处理。

## 完整示例

- 通用双向传输：`examples/blueos_generic_companion/`
- 小说业务示例：`examples/blueos_novel_companion/`

这两个目录是参考 Demo，不是 OrbitV 生成的手表工程。
