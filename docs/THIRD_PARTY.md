# 离线 PDF 转换：第三方组件与适配记录

运行时没有 Python、CUDA、Marker、Calibre、k2pdfopt、LLM 或远程 OCR；应用仍无 INTERNET 权限。

| 组件 | 固定版本 | 许可 | 使用范围 |
|---|---|---|---|
| PdfBox-Android | 2.0.27.0 | Apache-2.0 | PDF 文字/坐标/目录与资源检测；仍使用系统 PdfRenderer 渲染 |
| Tesseract4Android Standard | 4.9.0 | Apache-2.0 | 本地 LSTM OCR，不使用 OpenMP；模型需用户从 SAF 导入 |
| WorkManager | 2.11.2 | Apache-2.0 | 唯一后台任务、前台通知、系统重新调度 |
| pdf-craft | `7d72c86bf4b77705767e90b8ae2d4881b37e79fa` | MIT | `extractor/chapter/jointer.py` Latin split-word 规则的 Kotlin 适配，以及章节/正文/资源组织流程参考 |
| epub-generator | 0.1.7；模板参考 `8223cc33142f5682d581ee907f85de45b4c94970` | MIT | container、OPF、nav、spine 模板结构与 mimetype-first ZIP 输出的 Kotlin 适配 |

`conversion/PdfTextFlow.kt` 和 `conversion/EpubOutput.kt` 的顶部标明适配来源。不是把整个 pdf-craft 识别模型或复杂章级推理引擎移植到 Android；几何排序、模型管理、检查点、任务/书库事务由 ReadX 实现。没有复制上游依赖云服务或 GPU 的代码。

完整许可证保存在应用资产 `licenses/`，随 APK 打包；设置页可查看。PdfBox-Android LICENSE 包含上游及第三方声明，Tesseract4Android LICENSE 随原生运行时保留。实际模型不放入源码、APK 或 Git。

Tesseract 测试使用官方 `tessdata_fast` 标签 4.1.0 的 chi_sim/eng，下载至忽略的 `.research/ocr-models`，模拟器通过本地文件导入；这不等于应用自动下载。OCR 会有错字，用户需要自行选择可信来源及许可证适用的模型。

新增 JitPack 仓库只允许 `cz.adaptech.tesseract4android` group，不将所有依赖切换至第三方源。4.9.0 的 arm64-v8a/x86_64 四个原生库 PT_LOAD 对齐为 16384，最终包另验 ZIP 对齐；其他 ABI/旧系统实际 OCR 表现未因此自动验收。

PdfBox 的可选 `com.gemalto.jp2.JP2Decoder` 未打包，仅为该类添加 R8 dontwarn；JPX 图像直接判为原图回退，ReadX 不请求 PDFBox 解码 JPEG2000，实际渲染由系统 PdfRenderer 负责。未据此宣称所有 JPEG2000 PDF 均兼容。
