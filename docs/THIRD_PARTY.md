# 第三方组件、来源与许可证

ReadX 原创代码采用项目根目录的 [MIT License](../LICENSE)。以下组件、适配片段、资源和模型仍遵循**各自原始许可证**；项目 MIT 不替代上游授权，也不适用于用户书籍。汇总声明见 [NOTICE](../NOTICE)。

完整原文保存在 [`app/src/main/assets/licenses`](../app/src/main/assets/licenses)，并随两版 APK 打包，设置 → 开源组件与许可证可以离线查看。构建时模型许可另随模型资产分发。

## 应用依赖

| 组件 / 固定版本 | 原始许可 | 来源 / 用途 |
| --- | --- | --- |
| AndroidX / Compose BOM 2026.01.00 | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)；Compose、Activity、Lifecycle、Room 2.8.5、WebKit 1.15.0、PDF 1.0.0-beta01、WorkManager 2.11.2 等，实际坐标见 Gradle |
| Material 3 1.5.0-alpha01 | Apache-2.0 | AndroidX Compose Material3；实验 Expressive API，非稳定承诺 |
| Material Components 1.13.0 | Apache-2.0 | [material-components-android](https://github.com/material-components/material-components-android/tree/1.13.0)；原生主题与 HCT 色调角色 |
| Kotlin / 内置 2.2.10，coroutines 1.10.2 | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin)、[coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.2) |
| jsoup 1.21.2 | MIT | [jsoup](https://github.com/jhy/jsoup/tree/jsoup-1.21.2)；HTML 解析、安全处理及 EPUB 输出；原文 `jsoup.txt` |
| PdfBox-Android 2.0.27.0 | Apache-2.0＋资源声明 | [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)；PDF 文字、坐标、目录和资源检查；保留 `PdfBox-Android.txt` / `PdfBox-NOTICE.txt` |
| Bouncy Castle 1.72 | 上游 MIT-style | PDFBox 引入的 bcprov/bcpkix/bcutil-jdk15to18；[1.72 许可](https://github.com/bcgit/bc-java/blob/r1rv72/LICENSE.html)；原文 `Bouncy-Castle.txt` |
| Tesseract4Android Standard 4.9.0 | Apache-2.0 | [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0)；离线 LSTM OCR，不使用 OpenMP；`Tesseract4Android.txt` |

AndroidX、Kotlin、Material Components 等共用的 Apache-2.0 原文随 APK 保存在 `Apache-2.0.txt`。各组件的版权归上游作者/贡献者；使用依赖不代表 ReadX 是它们的官方产品或获其背书。

### OCR 原生组件

下列组件来自 Tesseract4Android **4.9.0 标签的 vendored 源码**，没有将主仓库其他版本的许可混入该二进制：

| 组件 | 原始许可 | 保留材料 |
| --- | --- | --- |
| Tesseract 引擎 | Apache-2.0 | `Tesseract.txt`；源 `tesseract4android/src/main/cpp/tesseract/src/LICENSE` |
| Leptonica | BSD-2-Clause-style | `Leptonica.txt`；上游 `leptonica-license.txt` 的版权、条款和免责原文 |
| libpng | PNG Reference Library License version 2 及文件内历史许可 | `libpng.txt`；上游完整 LICENSE，不能笼统改写为项目 MIT |
| libjpeg / IJG 9f | Independent JPEG Group 许可 | `IJG-libjpeg.txt`；保留原 README 全文及 LEGAL ISSUES；**This software is based in part on the work of the Independent JPEG Group.** |

原生源路径均可从 [4.9.0 的 cpp 目录](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0/tesseract4android/src/main/cpp) 核对。系统 PdfRenderer、Android/NDK 系统库仍是平台依赖，未改为 MIT。

### PDFBox 内置资源

PDFBox/FontBox/PaDaF、Adobe Glyph List、Core 14 AFM、CMaps、Unicode 数据等保留 PdfBox-Android 的原 LICENSE/NOTICE（含附带版权/资源许可）。不能因为 PDFBox 主许可为 Apache-2.0 就删除字体和映射资源的附加声明。

实际 2.0.27.0 AAR 包含 **LiberationSans-Regular.ttf 2.1.5**，已读取字体 name table 的版权/版本/许可核实：© 2010 Google Corporation、© 2012 Red Hat, Inc.，**SIL Open Font License 1.1**。保留原 [2.1.5 LICENSE](https://github.com/liberationfonts/liberation-fonts/blob/2.1.5/LICENSE)（含 Reserved Font Names）于 `Liberation-Fonts.txt`；字体未修改，不能将其标作项目 MIT 或仅 Apache-2.0。

PdfBox 的可选 `com.gemalto.jp2.JP2Decoder` 没有打包，仅为该可选类设置窄范围 R8 `dontwarn`。JPX 判为原图回退，实际位图由系统 PdfRenderer 渲染；未据此承诺所有 JPEG2000 文档都兼容。

## 有限 Kotlin 适配

| 项目 | 固定版本 / 提交 | 许可 / 版权 | 实际复用范围 |
| --- | --- | --- | --- |
| [pdf-craft](https://github.com/oomol-lab/pdf-craft) | `7d72c86bf4b77705767e90b8ae2d4881b37e79fa` | MIT；© 2025 Tao Zeyu | `PdfTextFlow.kt` 的有限 Latin split-word/段落连接规则参考 `extractor/chapter/jointer.py`，以及正文/资源组织流程；不是整个识别引擎移植 |
| [epub-generator](https://github.com/moskize91/epub-generator) | 0.1.7；模板参考 `8223cc33142f5682d581ee907f85de45b4c94970` | MIT；© 2025 Moskize91 | `EpubOutput.kt` 中 container/OPF/nav/spine、mimetype-first ZIP 输出结构的 Kotlin 适配 |

源文件头部标注适配来源，原 MIT 版权/许可文本分别为 `pdf-craft.txt`、`epub-generator.txt`。ReadX 的几何处理、检查点、模型管理与书库事务是应用整合代码。

**没有打包 Python、CUDA、Marker、Calibre、k2pdfopt、Readium、远程 OCR、LLM 或服务器。** 不将少量规则移植宣传为上游完整 AI 引擎或通用 PDF 保真转换。

## 可选 OCR 模型

- 官方来源：[tesseract-ocr/tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0)。固定标签 **4.1.0**、提交 **`65727574dfcd264acbb0c3e07860e4e9e9b22185`**，Apache-2.0。
- 原样使用 `chi_sim`、`chi_tra`、`eng`，不修改模型参数；每份大小及 SHA-256 见 [manifest.json](../app/src/ocrBundled/assets/ocr/manifest.json)。`tessdata_fast.txt` 和模型 assets 的 `LICENSE.txt` 保留上游原文。
- 普通版不打包模型，用户通过 SAF 本地导入；显式选定的内置版 `-PbundledOcr=true` 打包三份模型。两版同包名、同构建者签名，安装更新同一应用，不能并存。
- `.traineddata` 二进制不进入 Git；准备脚本显式在开发电脑下载固定提交并校验，或从本地目录完全离线复制；应用自身始终无网络权限，不自动下载。
- OCR 可能有错字/漏字，模型版本不会赋予用户书籍分发权。不要将真实用户书籍或生成转换版提交到仓库。

## 构建和测试工具

Gradle Wrapper 9.3.1、AGP 9.1.0、Compose compiler 2.2.10、KSP 2.3.12 为开发工具；各自使用上游许可。JUnit 4.13.2 使用 EPL-1.0（测试依赖），AndroidX Test 使用 Apache-2.0；不是预览 APK 的独立功能。

直接版本以 [`app/build.gradle.kts`](../app/build.gradle.kts)、根构建文件及 Wrapper 为准。未把全部传递依赖重新授权为 MIT。新增/升级依赖时需同步实际解析版本及许可/NOTICE；当前声明依据本轮源码、缓存 POM 及固定上游文件核对。


## 0.6.0 机制研究（未复制或打包）

- 研究 KOReader `frontend/document/pdfdocument.lua` 的 used bounding box、原页尺寸和页级缓存接口，以及按页选择边界的思路。仅用于确认“阅读裁边不等于改原文件/智能重排”的职责边界；ReadX 的预览像素背景估计、投影检测、规则优先级和 Kotlin 可逆变换是独立实现。
- KOReader 工程采用 AGPL-3.0 范围的许可证；本轮**不复制其 Lua/C++ 实现、不链接其运行时、不引入 KOReader/k2pdfopt 依赖，也不将其代码重新许可为 MIT**。ReadX 原创实现继续 MIT；若将来要实际复用上游代码，须重新确认许可并取得用户架构/分发授权。
- Android StaticLayout、PdfRenderer 和已固定的 AndroidX PDF 公开接口来自 Android/AndroidX；本轮没有升级依赖。API 签名以工程缓存的 beta01 AAR 与编译结果核对。裁边、文字层搜索、选择和链接使用公开 PdfDocument，不访问内部布局。
- 自生成 `androidTest/assets/crop-fixtures.pdf` 仅用于测试：单栏、双栏、扫描图片、空白、横版、脚注/页码/链接、暗背景，不含私人书籍。生成使用开发机 ReportLab，APK 不包含 Python/ReportLab/Lua/C++ 新运行时。


## 0.7.0 本地字体与可选在线模型

- 未新增大型依赖/云OCR/LLM。字体使用Android Typeface与用户自行导入的TTF/OTF，未将用户字体作为MIT资源分发；Markdown/TXT批注导出不打包字体。
- 在线下载沿用固定官方 tessdata_fast 4.1.0 清单/提交/SHA-256与Apache-2.0模型许可；仅将清单复制到普通assets，模型二进制仍不进Git。用户明确开启并点击下载后才访问raw.githubusercontent.com，关闭中断未完成任务，本地导入与内置版继续保留。
- 选区气泡、裁边手柄与磁吸为ReadX原创Compose/Kotlin实现；不复制KOReader或引入Lua/C++运行时。Unicode边界使用平台BreakIterator与Chromium Intl.Segmenter，有启发式限制，不宣传通用中文语义模型。
