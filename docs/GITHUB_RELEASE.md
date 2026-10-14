# Git 管理与 GitHub 发布

本地仓库根目录是 `D:\AgentProject\codex\Application\Android\Health vivo`，一个仓库管理 `android`、`watch` 两个工程。
旧版曾附带第三个工程 `orbitv`（OrbitV 扩展），已在 0.14.1 移出代码树并存档到不纳入版本管理的
`archive/orbitv-extension-1.2.0/`，不再参与构建或发布。

当前已配置 `origin = https://github.com/shuang-ying/HealthVivo.git`，`main` 已上传并跟踪 `origin/main`。已发布 [v0.12.3 预发布](https://github.com/shuang-ying/HealthVivo/releases/tag/v0.12.3)。当前电脑不用重复初始化、添加 origin 或重复发布这个标签。

## 首次上传

1. 在 GitHub 新建空仓库，选择公开或私有。不要勾选自动创建 README、.gitignore 或 License，以免与已有本地首次提交冲突。
2. 在项目根目录运行以下命令，将占位地址换成自己的仓库地址：

```powershell
Set-Location -LiteralPath 'D:\AgentProject\codex\Application\Android\Health vivo'
git remote add origin https://github.com/你的用户名/你的仓库名.git
git push -u origin main
```

首次认证通过 Git Credential Manager 的浏览器登录完成；不要把令牌写进远程地址或提交到文件。若仓库已有提交，先获取和检查历史，再合并，不要强制推送覆盖。

本机 GitHub CLI 已使用浏览器授权 shuang-ying，便携程序在 `.tools/github-cli/bin/gh.exe`；本仓库的认证 helper 指向它，设置仅保存在未上传的 `.git/config`。Git HTTPS 已采用当前 Windows 系统代理；如果以后更换或关闭代理，在本仓库更新／取消 `http.https://github.com/.proxy`，不要关闭 TLS 证书校验。

## 后续开发

```powershell
git status
git add android watch scripts docs README.md THIRD_PARTY_NOTICES.md .gitignore .gitattributes
git diff --cached --stat
git commit -m "Describe the change"
git push
```

本地私钥、SDK 配置、API 设置、健康数据、缓存、历史试验目录和安装包已设置为忽略。不要使用 `git add -f` 加入这些文件。首次上传前检查的是最终 Git 文件清单，并非整个电脑目录。

## 准备 Release 附件

两套软件版本独立，仓库以手机版本作为套件标签，例如 `v0.15.0`。每次发布说明列出当次配套的手机与手表版本。

```powershell
./scripts/build-android.ps1
./scripts/build-watch.ps1
./scripts/prepare-release.ps1
```

脚本只整理已构建的两个安装包、安装说明、SHA256 校验值和许可证说明，不自动上传、安装或运行测试。
附件输出至被 Git 忽略的 `artifacts/releases/<标签>`。需要重新构建时先运行两个 build 脚本。

当前手机 APK 是调试签名版本，预发布建议勾选 **This is a pre-release**。正式发行应使用受保护的固定签名密钥构建不含 debug 入口的发布 APK；
不要直接换签名并卸载旧 App，卸载会删除本机记录。保留密钥用于以后覆盖更新，密钥不能上传到 Git。

## 网页发布

源码推送成功后：仓库页面 → **Releases** → **Draft a new release**。

1. 创建标签（例如 `v0.15.0`），Target 选择 `main`，确认它指向准备本次附件的提交。
2. 标题填写“活力记录 v0.15.0 · 预发布”。
3. 复制 `docs/releases/<标签>.md` 的内容。
4. 上传整理目录中的两个安装包、`SHA256SUMS.txt`、`INSTALL.md`、`LICENSE` 和 `THIRD_PARTY_NOTICES.md`。
5. 勾选预发布，可先 **Save draft** 检查附件，再 **Publish release**。

GitHub 会自动提供该标签的源码归档，安装包需额外作为附件上传。不要把整个 artifacts 文件夹上传，其中还包含本机诊断与历史报告。
旧标签 `v0.12.3` 的附件包含当时的 OrbitV 扩展包，那是已发布的历史记录，不要修改或删除。

## 可选：GitHub CLI

安装 GitHub CLI 后使用浏览器授权，密钥无需发到聊天中：

本机便携版未加入系统 PATH，可在项目根目录用 `& './.tools/github-cli/bin/gh.exe'` 代替下面的 `gh`。其他电脑安装 GitHub CLI 后正常使用 `gh`。

```powershell
gh auth login --hostname github.com --git-protocol https --web
```

若远程已配置并推送，可使用生成的 `RELEASE_NOTES.md` 和附件建立草稿。标签先通过 `git tag -a v0.15.0 -m "Vitality suite v0.15.0"` 创建，再 `git push origin v0.15.0`。不要重复创建已有标签或移动已发布标签。

```powershell
gh release create v0.15.0 `
  artifacts/releases/v0.15.0/vitality-phone-0.15.0.apk `
  artifacts/releases/v0.15.0/health-vivo-watch-0.8.0.rpk `
  artifacts/releases/v0.15.0/SHA256SUMS.txt `
  artifacts/releases/v0.15.0/INSTALL.md `
  artifacts/releases/v0.15.0/LICENSE `
  artifacts/releases/v0.15.0/THIRD_PARTY_NOTICES.md `
  --verify-tag --draft --prerelease `
  --title "活力记录 v0.15.0 · 预发布" `
  --notes-file artifacts/releases/v0.15.0/RELEASE_NOTES.md
```

确认草稿附件后再发布。项目原创代码已采用根目录 `LICENSE` 中的 MIT 许可证；第三方 SDK、模板、依赖和数据保留各自条款，详见 `THIRD_PARTY_NOTICES.md`。准备 Release 时一并附上这两个文件；它们不代表重新授权第三方材料。

来源：[上传本地源码](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)、[管理 Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)、[GitHub CLI release create](https://cli.github.com/manual/gh_release_create)。
