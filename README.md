<p align="center">
  <img src="docs/assets/readx-icon.svg" alt="ReadX application icon" width="96" height="96" />
</p>

<h1 align="center">ReadX</h1>

**一款离线的 Android 本地阅读器。** 支持 EPUB、TXT、PDF；原生 Kotlin + Jetpack Compose + Material 3 Expressive，无账号、无广告；阅读与转换离线，模型在线下载需主动开启。

> [!IMPORTANT]
> 本项目为一个 AI 开发的项目，代码、架构及文档主要由 `gpt-6.1-sol Harness: Codex` 生成。欢迎提出Issue或PR。

[下载预览版](https://github.com/wjhhm2003/ReadX/releases)   [第三方声明](docs/THIRD_PARTY.md)  [MIT 许可证](LICENSE)

Material 3 Expressive 与 AndroidX PDF 使用实验/预发布 API。

![ReadX 运行界面](docs/screenshots/md3-overview.png)

---

## 软件介绍

ReadX 是个人自用的本地轻量 Android 阅读器，旨在提供纯粹、流畅的中文阅读体验。

### 核心功能

| 功能 | 说明 |
| --- | --- |
| **本地书库** | SAF 多文件导入、私有副本、SHA-256 内容去重；真实 EPUB 封面/PDF 首页缩略图；支持修改书名、作者与标签，按不同维度排序与网格/列表切换 |
| **TXT / EPUB 阅读** | TXT 默认原生 StaticLayout 排版，可按书切换 WebView；EPUB 采用 WebView 引擎。支持按屏幕可用区域精确分页或滚动阅读、三分屏点击翻页、目录跳转、本地链接与中文全文搜索 |
| **全书页数与测量** | 采用渐进式全书测量与多维排版缓存；章内优先显示，统计完成前明确显示已测章节数，不使用估算值伪造总页数 |
| **文字批注与导出** | 支持高亮、划线、笔记与颜色切换；使用 DOM UTF-16 偏移与上下文校验精准定位；可将整书历史批注导出为 Markdown / TXT |
| **繁简转换显示** | 内置 OpenCC 离线字典，支持 **原文 / 简体 / 繁体** 实时排版转换；仅作用于正文显示，不改动原书文件、数据库或选段原文定位 |
| **PDF 原版阅读** | AndroidX 高级纵向查看器＋系统 `PdfRenderer` 回退；支持横向单页、平滑缩放、真实文字选取与跳转、自定义四边/自动裁边及夜间反色模式 |
| **PDF 转电子书** | 可选离线增强功能（默认关闭）。通过文字层提取与离线 OCR（Tesseract），将 PDF 转换为可重排的独立 EPUB 电子书，支持后台检查点与中断续算 |
| **界面设计** | Material 3 Expressive 风格，支持浅色/深色及动态主题色；正文沉浸式布局，统一系统手势安全区，针对宽屏提供侧栏与内容限宽适配 |

---

## 快速上手

### 1. 下载与版本选择

ReadX 提供两种 Preview APK 供选择（均使用包名 `io.readx.app`，安装会互相更新替换）：

| 预览包 | OCR 模型 | 适用情况 |
| --- | --- | --- |
| **普通版** `app-preview.apk` | 不内置；支持从本地导入 `.traineddata` 或在线下载 | 安装包体积小，按需准备模型 |
| **内置版** `app-ocr-preview.apk` | 内置简中（`chi_sim`）、繁中（`chi_tra`）、英文（`eng`）模型 | 安装后离线即可使用完整 OCR 功能 |

> [!NOTE]
> 个人 Preview APK 使用调试密钥签名。安装新版本或在不同来源的构建间切换时，请先通过应用导出需要保留的原书与转换文件，**切勿通过卸载或清空应用数据来解决签名冲突**。

### 2. 基础使用指南

- **导入书籍**：在书架顶栏点击加号或选择菜单项，通过系统文件选择器（SAF）批量导入 TXT、EPUB 或 PDF 文件。
- **阅读手势与交互**：
  - **TXT / EPUB**：点击屏幕左侧 / 右侧区域分别进行上一页 / 下一页翻页；点击中间区域显隐顶部与底部工具栏。章末前进自动进入下一章。
  - **PDF**：纵向模式下任意位置单击显隐工具栏；横向模式下左 / 中 / 右三等份进行翻页或显隐操作。
- **页码与精确跳转**：底部常驻真实页码滑条与章节微调按钮，点击或长按页码数字可直接输入精确页码跳转。

### 3. 特色功能使用

#### 繁简转换
在阅读下栏中选择 **排版 → 繁简转换**，可在 **原文 / 简体 / 繁体** 之间切换（亦可在阅读设置中全局配置）。转换基于内置 OpenCC 字典离线完成，选段复制使用显示文字，而批注引用与全文搜索仍按原文匹配。

#### PDF 裁边与夜间反色
- **裁边**：在下栏点击 **裁边**，可开启自动白边检测，或在原页预览中通过拖动四边/四角手柄进行手动裁边。范围支持当前页、全书、奇数页或偶数页。
- **夜间反色**：在下栏或颜色面板中开启 **PDF 夜间反色**，实时反转页面颜色，覆盖纵向、横向、裁边及回退引擎，不改变原始文件或位图缓存。

#### PDF 转电子书 (EPUB)
1. 进入 **设置 → 将 PDF 转为电子书** 开启功能开关。
2. 选择识别语言。若使用普通版且涉及扫描件，可通过本地导入 `.traineddata` 文件，或开启 **允许在线模型下载** 主动下载官方模型。
3. 打开 PDF 图书，观察后台转换进度；完成后即可打开生成的独立 EPUB 电子书，享受重排、搜索与文本批注体验。

---

## 技术与专业说明

### 隐私与数据安全

- **本地优先**：读取应用私有副本，不修改用户原书文件。移除书库条目仅删除应用私有数据，不删除原始文件。
- **无账号与广告**：全应用不包含第三方 SDK、账号系统或广告服务。
- **网络权限说明**：仅包含 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限，专门用于用户**主动开启**的一键官方 OCR 模型下载（仅限 GitHub 官方 `tesseract-ocr/tessdata_fast` 仓库固定 SHA-256 校验的资源）。默认关闭，不上传任何书籍内容、选段、笔记或个人数据。
- **数据备份提示**：由于系统自动备份已关闭，**卸载应用会删除应用私有目录下的副本及私有批注**，请务必提前导出重要文件。

### 项目构建

#### 环境要求
- **Android Studio** / **JDK 17+** (Java 源码目标 17)
- **Android SDK 36.1** (Compile SDK 36.1 / Target SDK 36 / Min SDK 28)
- **Gradle 9.3.1** / **AGP 9.1.0** / **Kotlin 2.2.10** / **Compose BOM 2026.01.00**

#### 构建命令

**普通版构建**：
```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assemblePreview
```

**内置 OCR 模型版构建**：
```powershell
# 准备固定版本模型
.\scripts\prepare-ocr-models.ps1
# 编译内置版
.\gradlew.bat assemblePreview -PbundledOcr=true
```

产物路径：`app/build/outputs/apk/preview/app-preview.apk`。

#### CI/CD 自动构建 (GitHub Actions)
本项目已配置 GitHub Actions 自动构建工作流（位于 `.github/workflows/build.yml`）：
- **触发时机**：当代码被 `push` 或提交 `pull_request` 到 `main` / `master` 分支时，后台会自动运行单元测试并编译 APK。
- **获取 APK**：构建完成后，可在 GitHub 仓库页面的 **Actions** 标签页中找到对应的 Workflow 运行记录，在页面的 **Artifacts** 区域下载生成的 `ReadX-APKs` 压缩包（内含 `app-debug.apk` 及 `app-preview.apk`）。

#### 设备测试
```powershell
adb devices
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

### 已知边界与限制

- **EPUB 引擎**：基于基础本地 WebView 适配器，非完整 Readium 引擎；暂不支持 DRM、固定版式 (Fixed-layout)、字体混淆、嵌入音视频等。
- **OCR 识别率**：离线 OCR 受限于设备算力与模型表现，手写体、复杂双栏、扫描表格/公式及竖排文本可能存在错字或排版错乱，请与原 PDF 对照使用。
- **搜索能力**：原版扫描 PDF 默认不支持全文搜索；OCR 转换后的电子书仅可搜索已识别的正文文本。
- **文件与资源限制**：支持单文件上限 256 MB (TXT 上限 32 MB)；EPUB 解压上限 160 MB、单资源 24 MB、单章 8 MB、最多 10000 个资源。

### 项目结构

```text
app/src/main/java/io/readx/app/
├── data/        # Room 数据库模型、DAO、历史迁移及私有文件管理
├── reader/      # TXT/EPUB 解析器、HTML 安全控制、分页与排版缓存
├── pdf/         # AndroidX PDF 查看器集成、基础 PdfRenderer 回退与裁边/反色
├── conversion/  # PDF→EPUB 转换管道、离线 OCR 任务管理与检查点
└── ui/          # Compose 界面、Material 3 主题、设置与 ViewModel
```

更多设计细节请参阅：
- [架构设计文档](docs/ARCHITECTURE.md)
- [界面与交互设计](docs/DESIGN.md)
- [路线图 (To Do)](docs/ROADMAP.md)
- [测试与验证记录](docs/TESTING.md)

### 许可证与致谢

ReadX 原创代码采用 **[MIT License](LICENSE)** 开源。

完整归属及第三方开源软件声明请参阅 [NOTICE](NOTICE) 与 [第三方清单](docs/THIRD_PARTY.md)。感谢 AndroidX、Jetpack Compose、Material Components、Kotlin、jsoup、PdfBox-Android、Tesseract4Android、pdf-craft 及 epub-generator 等开源项目。
