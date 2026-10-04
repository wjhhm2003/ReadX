# ReadX

**一款离线的 Android 本地阅读器。** 支持 EPUB、TXT、PDF；原生 Kotlin + Jetpack Compose + Material 3 Expressive，无账号、无广告、无网络权限。

[下载预览版](https://github.com/wjhhm2003/ReadX/releases)  [第三方声明](docs/THIRD_PARTY.md) [MIT 许可证](LICENSE)

Material 3 Expressive 与 AndroidX PDF 使用实验/预发布 API。

![ReadX 运行界面](docs/screenshots/md3-overview.png)

## 功能

| 功能 | 当前实现 |
| --- | --- |
| 本地书库 | SAF 多文件导入、私有副本、SHA-256 内容去重；真实 EPUB 封面/PDF 首页缩略图；修改书名/作者/标签 |
| TXT / EPUB | 按实测阅读区域分页或滚动；滑动和三分屏点击；目录、本地链接、中文搜索、字号/字体/行高/页边距与纸张主题 |
| 全书页数 | 当前章先显示，渐进测量全书；多维布局缓存；统计完成前显示真实已测章节数，不用估算值冒充精确总数 |
| 文字批注 | 高亮、划线、笔记、颜色、删除与重开恢复；DOM UTF-16 偏移＋原文/上下文校验，不宣称通用 EPUB CFI |
| PDF 原版 | AndroidX 高级纵向查看器＋系统 PdfRenderer 回退；横向单页、缩放、页跳转与页/区域批注；功能按实际系统能力启用 |
| PDF → EPUB | 可关闭、默认关闭；文字层提取＋离线 OCR；后台真实页数进度、取消/续算；生成独立 EPUB，不修改原 PDF；原页回看与 SAF 导出 |
| 界面 | Material 3 Expressive、浅色/深色、动态/自定义主题色、沉浸正文、统一系统手势区；宽屏侧栏与内容限宽 |

启动进入书架，不自动打开上次的书。TXT/EPUB 左右区域上一页/下一页，中间显隐工具栏；PDF 纵向任意位置单击显隐，横向左/中/右三等份翻页或显隐。选区、链接和已有标记仍有相应交互。

## 两种 APK：包名相同

| 预览包 | OCR 模型 | 适用情况 |
| --- | --- | --- |
| 普通版 `readx-0.5.0-preview.apk` | 不内置；从本地导入 | 安装包较小，自行选择模型 |
| 内置版 `readx-0.5.0-ocr-preview.apk` | 简中 `chi_sim`、繁中 `chi_tra`、英文 `eng` | 安装后离线可用，手动准备模型 |

**两版 applicationId 都是 `io.readx.app`，不能并存，安装会更新替换同一应用。** 本仓库的个人 Preview 使用本机调试密钥，不是正式发行签名；自己构建可能使用不同密钥，不能保证覆盖安装他人的 APK。请先导出需要保留的原书和转换版，**不要通过卸载/清空应用解决签名冲突**。

普通版在设置中通过系统文件选择器导入 `.traineddata`，单文件最多 64 MiB；内置版首次在后台校验并部署模型，已有可用用户模型不会被覆盖。语言可选“简中＋英文 / 繁中＋英文 / 英文”。三个模型不能保证所有文档都准确识别。

## 使用 PDF 转换

1. 在 **设置 → 将 PDF 转为电子书** 开启开关；不会批量转换整个书库。
2. 选择识别语言；普通版扫描件需要先导入相应模型，有正常文字层不要求 OCR 模型。
3. 点开 PDF，观察实际原文页数进度。缺模型时任务等待，导入后点击“继续 / 重试”；可先读原 PDF。
4. 完成后打开独立转换版，使用现有 EPUB 排版/搜索/批注；进度面板可 **查看原 PDF / 导出 EPUB**。

复杂或不可靠内容保留原图，未获得可重排正文则明确失败。原 PDF 与转换版的进度、页码和批注独立；PDF 页坐标标记不自动变成文字标记。密码 PDF 首版不转换；原版查看器仍按系统支持处理。后台任务可能受系统配额、电量和进程限制中断。

## 隐私与数据

- 应用无 `INTERNET` 权限；没有远程 OCR、LLM、服务器、账号或广告。应用不会自动下载模型。
- 导入后读取应用私有副本，不修改原文件；移除书库条目不删除原始文件。
- 书籍、数据库、笔记和模型在私有目录；当前不额外加密，也不把应用当作加密保险库。
- 系统自动备份关闭，尚未完成数据库/文件一致性备份恢复。**卸载会删除应用副本和私有批注**，原始文件不受影响。
- 通知只报告阶段和页数；通知权限可拒绝，应用内仍可观察状态。生成 EPUB 可导出为独立文件。
- 仓库不收录用户书籍、笔记、签名密钥、模型二进制或测试日志。第三方模型及组件保留各自许可证。

## 构建

### 环境

Android Studio / JDK 17+（Java 源码目标 17），Android SDK 36.1；最低 Android 9 / API 28，target API 36。使用项目 Gradle Wrapper，不依赖全局 Gradle。

固定组合：Gradle 9.3.1（含分发包 SHA-256）/ AGP 9.1.0 / AGP 内置 Kotlin 2.2.10 / Compose compiler 2.2.10 / KSP 2.3.12 / Compose BOM 2026.01.00 / Material 3 1.5.0-alpha01 / Room 2.8.5 / AndroidX PDF 1.0.0-beta01。实际值以构建配置为准，不需要为了编译自行升级依赖。

在 Android Studio 打开仓库，或设置 `JAVA_HOME` / Android SDK。`local.properties` 仅是本机配置，不提交；Windows 路径示例：`sdk.dir=E\:/Android/Sdk`。仓库脚本在本机环境脚本存在时加载，否则使用调用者环境。

### 普通版

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assemblePreview
```

macOS/Linux 使用 `./gradlew`。依赖已下载时可添加 `--offline`，首次解析依赖需要开发电脑联网。普通版不需要下载 OCR 模型才能编译。

输出：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/preview/app-preview.apk`。正式 Release 不内置签名凭据。

### 内置三个模型的版本

先显式准备固定版本模型，再构建。准备脚本访问的是开发电脑，不赋予 Android 应用联网权限。

```powershell
.\scripts\prepare-ocr-models.ps1
.\gradlew.bat assemblePreview -PbundledOcr=true
```

或跨平台：

```sh
python3 scripts/prepare-ocr-models.py
./gradlew assemblePreview -PbundledOcr=true
```

已有模型可完全离线准备：PowerShell 加 `-LocalModelDirectory /path/to/models`，Python 加 `--local-directory /path/to/models`。脚本和 Gradle 会核对文件大小及 SHA-256；没有模型或校验不符时不生成假内置包。

模型来自官方 [tessdata_fast 4.1.0](https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0)，固定提交/哈希见 [模型清单](app/src/ocrBundled/assets/ocr/manifest.json)，原许可证一同打包。源码仓库只保存清单和许可证，`.traineddata` 被 Git 忽略。内置版仍输出同一构建路径，请将产物复制另存，避免与下一次普通构建混淆。

### 设备验证

```powershell
adb devices
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
# 内置版定向验证（先准备模型）
.\gradlew.bat connectedDebugAndroidTest -PbundledOcr=true '-Pandroid.testInstrumentationRunnerArguments.class=io.readx.app.BundledOcrInstrumentedTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

仅在专用模拟器/测试设备执行；Gradle 会安装更新 APK，保留 APK 参数不等于书库备份。R8 构建和设备 UI 测试分阶段运行，不同时争用资源。

## 已知边界

- EPUB 是基础本地 WebView 适配器，不是完整 Readium 引擎；不支持 DRM、固定版式、字体混淆、音视频及复杂脚注全兼容。
- 普通阅读进度仍保留章节/相对位置兼容，文字锚点优先用于会话内重排及批注；书架文本百分比是章节等权估算。全书页数只对当前排版有效。
- OCR 会有错字、漏字；竖排、手写、复杂双栏、扫描图表/公式和脚注顺序没有完整验收，启发式复杂区域检测不能代替语义理解。应与原 PDF 对照。
- 原版扫描 PDF 默认不承诺全文可搜；OCR 转换版只能搜索已识别正文，原图回退页不是可搜索文字。
- 批注导出、完整一致性备份、多语言本地化、PDF 裁边/反色和原 PDF 批注写回未实现；大字体、旧系统、低端设备、折叠屏和长期压力仍需扩展验证。
- 256 MB 源文件 / TXT 32 MB；EPUB 解压合计 160 MB、单资源 24 MB、单章 8 MB、最多 10000 资源。异常或超限明确失败，不清空书库规避。

## 项目结构与贡献

- `data/`：Room v5、显式历史迁移、导入去重与私有副本。
- `reader/`：TXT/EPUB、受控 HTML/选区脚本、分页与布局缓存。
- `pdf/`：高级查看器、基础回退、PDF 生命周期与批注。
- `conversion/`：PDF→EPUB、任务状态、检查点和本地 OCR。
- `ui/`：Compose 界面、主题、设置及 ViewModel。

详见 [架构](docs/ARCHITECTURE.md)、[设计](docs/DESIGN.md)、[To Do](docs/ROADMAP.md)、[贡献指南](CONTRIBUTING.md)。历史验证是指定设备/版本的记录，不代表所有场景或本次发布全部重新测试。

## 许可证与致谢

ReadX 原创代码采用 **[MIT License](LICENSE)**，版权所有 © 2026 wjhhm2003 及贡献者。第三方代码、适配片段、字体资源与模型**不因项目采用 MIT 而改为 MIT**，仍遵循各自条款。

完整归属和版本见 [NOTICE](NOTICE) 与 [第三方清单](docs/THIRD_PARTY.md)；原文许可证保存在 [应用资产](app/src/main/assets/licenses)，设置页也可查看。感谢 AndroidX、Jetpack Compose、Material Components、Kotlin、jsoup、PdfBox-Android、Tesseract4Android、pdf-craft 和 epub-generator 等项目。
