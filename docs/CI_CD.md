# GitHub Actions 自动测试与 Release 交付

## 触发与门禁

唯一流程：`.github/workflows/ci-release.yml`（ReadX CI and Release）。

```text
PR → main ──────────────→ verify（脚本测试 / JVM / Lint / API 36 设备测试）
main push（含 PR 合并）─→ verify → package（普通版 R8 Preview + 压缩包安装启动 / 签名）
                                      → publish（草稿上传完整附件后公开预发布）
```

- 不监听 PR `closed` 来再次发布；PR 合并对 main 的 push 就是交付事件。
- 不按路径过滤，文档 push 也验证交付；main 每个 push 的运行不自动取消。同一 PR 新提交可取消其过时验证。
- `workflow_dispatch` 可手动运行；只允许 main 的手动运行进入交付。
- API 36 / google_apis / x86_64 / Pixel 6 是每个 job 新建的专用临时 AVD，绝不连接用户设备或上传私人验收样书。R8 与设备测试分阶段执行。
- 普通构建跑现有全设备套件；私有样书、主动联网、未准备模型等可选用例按现有 Assume 条件跳过，不宣传为全部场景已验收。
- 按用户要求只交付普通版，CI 始终 bundledOcr=false，不运行模型准备脚本，不下载／内置 OCR 模型，也不构建内置版。package 在新的专用 AVD 安装并启动实际 R8 Preview，检查启动状态、进程与 crash buffer；这不是完整 minified 设备套件。
- 任一必需步骤失败都不发布；报告通过 `always()` 保留供排障。无 `continue-on-error`、Lint 基线或 destructive migration。

## 一次性签名设置（必需）

仓库 Secret：**`READX_CI_PREVIEW_KEYSTORE_BASE64`**。

这是专门为 CI 新建的 Preview 调试签名，不是正式发行签名；禁止将本机现有调试密钥或正式签名私钥上传来代替。固定格式：alias `androiddebugkey`，store/key password 均为 `android`。密钥本身才是秘密，密码采用 Android Preview 调试签名惯例。

由仓库管理员在自己的 PowerShell 中执行以下一次性操作。密钥生成在工程外，Secret 不写入 Git；请在安全位置另行保存这把 CI 专用密钥，丢失后更换签名将无法覆盖旧 CI 安装：

```powershell
. E:\Android\android-dev-env.ps1
# 使用新的专用文件；若已存在，先核实用途，不覆盖任何现有密钥。
$keyPath = Join-Path $env:TEMP 'readx-ci-preview.keystore'
if (Test-Path -LiteralPath $keyPath) { throw '请选择一个全新的 CI 专用密钥文件路径' }
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $keyPath -storetype JKS `
  -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 `
  -validity 10000 -dname 'CN=ReadX CI Preview,OU=Development,O=ReadX,C=CN'
if ($LASTEXITCODE -ne 0) { throw '生成失败' }
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath)) |
  gh secret set READX_CI_PREVIEW_KEYSTORE_BASE64 --repo wjhhm2003/ReadX
if ($LASTEXITCODE -ne 0) { throw '保存 Secret 失败' }
# 安全保管工程外的 CI 密钥；不要把 Base64 输出到日志、聊天或仓库。
```

也可在 GitHub Settings → Secrets and variables → Actions → New repository secret 手工保存同名 Base64 Secret。没有配置 Secret 时，package 会明确失败，不用临时随机签名冒充可持续交付。配置后在 Actions 选择 main 手动重跑。

签名只恢复到 runner 临时目录，访问权限为 0600，job 尾部清理；不缓存／上传私钥。PR 没有签名步骤。不要删除 Secret 或重复生成密钥来处理普通构建失败。

## 版本与安装兼容

- 本机不传 CI 参数时，仍使用源码原有版本号与本机调试签名，正式 release buildType 仍不绑定签名。
- CI Preview 显式传 `ciVersionCode=100000 + github.run_number`、`ciVersionSuffix=-ci.<run_number>.<run_attempt>`；普通版版本名为 `<版本>-preview-ci.N.A`。
- `run_number` 与此工作流关联，重跑 attempt 不增加 versionCode。长期维护不要随意重命名／重建工作流导致计数回退；如更换流水线需设计兼容的 versionCode 区间。
- 普通版使用包名 io.readx.app，可在同一 CI 签名下更新。CI 包与本机历史 Preview 签名不同，不能直接覆盖。**不可用卸载、清空应用数据解决冲突**，完整书库／批注一致性备份恢复尚未实现。

## 产物与可追溯性

每次成功运行创建独立 `ci-v<版本>-<run_number>.<run_attempt>-<sha7>` **prerelease**，不设为正式 Latest。Release 指向实际测试与构建的 `github.sha`，不会从可变 main 头部重新取源码。

| Release 附件 | 用途 |
| --- | --- |
| `app-preview.apk` | 普通版 R8 Preview，明确 `bundledOcr=false` |
| `SHA256SUMS.txt` | 校验所有其他附件（包括说明文件） |
| `build-info.json` | 源码 SHA、run 链接、真实版本／大小／哈希、签名证书 SHA-256 |
| `release-notes.md` | 安装提醒与验证边界 |

`scripts/package-ci-release.py stage` 从真实 AGP output-metadata.json 验证包名、Preview variant、普通版 CI 版本、唯一通用 APK 与非空输出，拒绝误用内置 OCR 版，并另存 APK 与 R8 mapping。finalize 校验真实产物哈希未变、版本与运行身份匹配。只有实际压缩 APK 安装启动、验签与 manifest 生成均成功，才上传交付附件。

发布 job 先下载同一 run 的签名产物并校验 SHA-256，再由 `gh release create --draft` 指定源码 SHA、一次上传所有附件，最后公开草稿。中途失败保持草稿，不公开半份交付。每次重跑 attempt 使用新标签，不覆盖旧发布。草稿上传失败时由管理员核实／清理，不删除原有正式 Release。

## 权限、缓存与维护

- 默认 `contents: read`；checkout 不保留凭据；不使用 `pull_request_target` 执行 PR 代码。
- 只有 publish job 获得 `contents: write`；该 job 不 checkout、不运行项目构建脚本。package 使用只读 token 与专用签名 Secret。
- Action 固定到已核实的完整提交 SHA，注释记录版本；升级时检查官方 action 配置与输入，不能只修改版本注释。
- Gradle action 选择 **basic GitHub cache**，不使用增强型第三方商业缓存；只有 main push 的 verify 写缓存，PR 和 package 只读。Wrapper 仍保留分发包 SHA-256。
- 仓库 Actions 开关需启用；发布权限由 job 请求，不必把全仓库默认 GITHUB_TOKEN 改为可写。若组织策略禁止 contents write 或分支／标签规则拦截，工作流明确失败后由管理员核实。
- 此流程自动运行 PR 验证，但不擅自修改分支保护或 merge 规则。如需强制「测试通过才合并」，在 main 的规则中要求 `Tests and lint (API 36)`，这是单独的管理决策。
- `verification-<run_id>-<attempt>`、`packaging-reports-<run_id>-<attempt>` 与签名交付暂存附件保留 14 天；公开 Release 资产不依赖该临时保留期。

## 验证命令

```powershell
python -m unittest discover -s scripts/tests -v
# 安装了 actionlint 时：
actionlint .github/workflows/ci-release.yml
. E:\Android\android-dev-env.ps1
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assemblePreview `
  '-PciVersionCode=100001' '-PciVersionSuffix=-ci.1.1'
```

配置／静态验证不代表云端全流程已成功；实际云端结果与未完成项见 `docs/TESTING.md` 和对应 Actions run。
