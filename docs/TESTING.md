# ReadX 验证记录

## 0.5.0 Material 3 Expressive 统一与系统手势区（2026-10-04）

### 本轮范围

- 共享字阶/圆角/间距、语义表面与 HCT 强调色配对；首页/书库/批注/设置、继续阅读与真实封面、600dp 宽屏侧栏、阅读设置分组及文本/PDF 覆盖操作栏。
- 手势导航底色连续、正文工具栏/原生 PDF 手势带补画、统一 IME 避让；不增加正文安全区、不因控件显隐改变分页尺寸。
- 首页不继承书库筛选；文本进度标“约”。修复显式退出后旧 WebView 销毁回调覆盖最后阅读页的问题：退出前保留有效进度快照，仍维持原章节/相对位置语义。
- 未升级依赖、未新增网络权限、未改变 Room schema。本次统一提交按用户要求包含先前未提交的 0.4.0 转换/分页工作；那些功能的历史验收见下节，不将其全部视为本轮重跑。

### 实际验证

- 设备：专用 `Pixel_6_API_36` / `emulator-5554` / Android 16 API 36，手势导航。启动已有 AVD，未 wipe-data、pm clear 或卸载。字体 1.5 倍/强制横屏/深色测试结束恢复字体 1.0、系统夜间 no；测试样书、批注和偏好在自身范围清理/恢复，未删除现有书库。
- 最终 `assembleDebug testDebugUnitTest lintDebug assemblePreview --offline`：全部通过。JVM **36 项通过，0 失败/错误/跳过**；包括固定/自定义主题语义配对与六种纸张主题对比度。Lint **0 错误，39 警告**，没有禁用检查或添加基线。
- 设备选定回归 **10 项单次全过**：MaterialDesign 2、ReferenceUi 1、ThemeSettings 1、ImmersiveReader 1、ReaderNavigation 2、PdfTapRegression 1、AsyncPagination 2。之后额外 GestureInsets **1 项单独通过**。共 11 个不同用例通过，不是单次运行全部历史设备测试。
- 覆盖：首页→书库→批注→设置；格式筛选与首页隔离；实际 PDF 首页/EPUB 嵌入封面；主题切换与动态取色；分段分页/滚动；1.5倍字体、横屏侧栏与深色；真实文字长按/颜色/取消标记/重开/改变字号；精确 Chromium 页数缓存、章末衔接、模式切换、搜索和退出恢复；PDF 后续页任意位置显隐与横向三分屏、页跳转。
- 手势区验收：实际截图采样书架、文本阅读与 PDF 底部像素，验证与对应表面颜色一致；阅读控件显隐高度不变由沉浸阅读测试断言。单独 IME 用例打开真实键盘，验证书库导航仍显示、退出键盘后恢复；`md3-keyboard.png` 为实际结果。
- 使用 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`；参数不是书库备份。Debug/Preview 同 applicationId，最终仅在专用模拟器安装 Preview（`adb install -r`）并冷启动：Status ok、包版本 `0.5.0-preview` / code 9，启动进程无 AndroidRuntime 错误。
- 两个 APK `apksigner verify --verbose` 通过，APK v2 签名、本机调试密钥。Preview 已启用 R8 与资源压缩；**完整设备交互回归运行的是 Debug，Preview 本轮仅安装启动冒烟验证**。

### 本轮排查与修正

- 早期主题测试发现浅绿面板辅助文字对比度不足，调整文字色后 36 项最终通过。自定义 RGB 是主题种子，旧测试对“显示色等于原始 RGB”的断言改为 Material 公开 API 的实际色调角色，并另测对比度，未删除相关验证。
- 旧设备用例沿用“控件默认展开”和旧原生选字入口，改为真实中区显隐/进度面板/选区旁浮层；同名批注标签与两个字号滑条改为确定的卡片/设置弹层范围。未通过加长任意等待掩盖故障。
- 退出恢复用例实际观测到目标第3页 fraction=0.16666667 被旧销毁回调覆盖成 fraction=0.083333336；修复进度提交所有权后，模式切换/搜索/关闭重开最终通过。用例等待实际 visual state 与旧 reader 退出，仍严格核对已保存位置。
- 早期一次 Lint PSI 内部异常发生在源码编辑过程中；完成编辑后最终重跑正常，未屏蔽检查。

### 本轮产物

| 产物 | 版本 | 大小（字节） | SHA-256 |
| --- | --- | --- | --- |
| `E:\ReadX\app\build\outputs\apk\debug\app-debug.apk` | 0.5.0 / 9 | 119609597 | `c4220a6a959d0d7d0e818ad1b72e9f5b52a5d4c44f19efe54042c8bed2aa3a95` |
| `E:\ReadX\app\build\outputs\apk\preview\app-preview.apk` | 0.5.0-preview / 9 | 40892196 | `9cd163566dee9c1f8212a8a1c360adc11072450a0d3e9fe4713bf6c115464f01` |

本轮所有截图在 `E:\ReadX\docs\screenshots\md3-*.png`。`md3-overview.png` 为实际截图拼图；`md3-home-covers.png` 使用自生成样书的实际封面资源，`md3-gesture-compare.png` 是本轮修正前后底部区域对比；不是设计稿或用户私有书籍截图。

### 未覆盖

- API 28–35 真机、其他厂商手势导航、三按钮导航完整回归、折叠屏/平板铰链、2倍以上字体、完整 TalkBack/键盘导航、长时间压力测试未运行。
- AndroidX PDF 内部原生搜索 UI 不承诺实时跟随自定义 Compose 色板；本轮仍未全面验收扫描件 OCR 搜索、密码/复杂 PDF、裁边与字体兼容。基础 PdfRenderer 强制回退完整测试未重跑。
- 离线转换/模型管理与历史迁移设备全套未重跑；不以本轮界面回归宣称这些能力新增全量通过。

---

## 0.4.0 离线 PDF→EPUB（2026-10-03）

### 已实现与本轮范围

- 全局默认关闭开关、按需独立 EPUB、原 PDF 保留、同来源/配置去重、后台真实页数进度/取消/续算/等待模型、SAF 模型管理、转换版阅读/原页回看及文件导出。
- 采用 PdfBox-Android 2.0.27.0、Tesseract4Android Standard 4.9.0、WorkManager 2.11.2；pdf-craft/epub-generator 仅有限规则和 EPUB 模板的 Kotlin 适配，不是完整识别模型移植。准确来源/许可记录见 THIRD_PARTY.md。
- Room v5，显式 4→5 迁移及历史路径扩展；所有历史 schema 保留，原 PDF 与转换版定位/批注独立。无 INTERNET / ACCESS_NETWORK_STATE，新权限仅后台任务及通知所需。

### 实际验证

- 专用 Pixel_6_API_36 / emulator-5554 / Android 16，复用已有模拟器；没有 pm clear、卸载或 wipe-data。生成 PDF、隔离迁移 DB 和测试偏好只在自身范围清理。OCR 模型从官方 tessdata_fast 4.1.0 本地导入，未打包/提交；测试通知授权在最终验收结束恢复。
- JVM **34 项通过，0 失败**：包括中文规范化/连接、英文断词、双栏顺序、复杂跨栏回退、EPUB mimetype/ZIP/元数据转义/独立解析和新增 XML 控制字符检查。
- 分批验证 **12 个不同设备用例的最终结果均通过**：ConversionMigration 1；PdfConversion 6（文字层/混合扫描/模型/取消后台续算/开关/真实 PDF/损坏及密码及无正文）；PdfTapRegression 1；ImmersiveReader 1；ColorMigration 1；历史 Migration 1、AnnotationMigration 1。不是单次设备报告“12 全过”：最后一轮 12 项有 11 通过、1 旧主题测试用 Switch 角色定位歧义；改为明确标签后，与真实 PDF 一起重跑 2 项均通过。
- 验证转换结果有实际英文和中文文字，不以图片冒充；缺模型 WAITING_MODEL、导入后版本配置切换、损坏模型不覆盖活动指针、取消后 checkpoint 续算、Activity 停止后后台仍完成、唯一任务去重、来源删除不删转换版、导出后再导入内容去重。
- 原 PDF 滚动到后续位置的任意区域单击显隐下栏、横向左/中/右三分屏均通过；文本阅读长按批注/颜色、主题、搜索入口与结果也有回归。
- R8 首次构建因 PdfBox 可选 JPEG2000 解码器未提供失败；仅对该可选类加窄范围 dontwarn，JPX 原图回退避免调用 PDFBox 解码。没有整体忽略 missing classes；JPEG2000 系统渲染兼容仍未设备验收。
- 早期发现并修复：仓库 cancel 与协程 cancel 名字匹配错误；中文兼容部首使查询不一致；旧主题测试假设只有一个 Switch；真实 PDF 目录含 XML 不允许的 U+0002 控制字符。最终 EPUB 打包后增加 XML/XHTML 完整解析，清理非法元数据字符后才允许导入。没有通过长等待或删除用户内容来掩盖问题。
- 自生成扫描中英混排确实输出 OCR 正文，但“扫描”的“描”曾识别为“拉”；验收检查真实识别主句，不声称 OCR 百分之百准确，也没有自动猜词替换。

### 用户 PDF 与导出结果

- 本地 152 页 PDF 保持不变，生成独立 EPUB；**14 页保留原图**，余页进入文字重排。最终验收日志端到端 **39075 ms**（包含转换、验证 EPUB、导入并打开结果及操作进度面板，非纯 OCR benchmark）。前轮 11–15 秒结果尚未加入最终完整 XML 验证，不能当最终交付耗时。没有上传或记录正文，没有保存用户书的公开截图。
- 导出：`E:\ReadX\pdf-converted.epub`，4107418 字节。所有 XML/XHTML/OPF 再在宿主严格解析通过，mimetype 首项且不压缩；不是正式 EPUBCheck 全规范认证。此文件是用户内容，已被根目录 EPUB 忽略规则排除 Git。
- 原 PDF 与测试临时副本/生成条目按测试范围分离；交付 EPUB 不依赖 ReadX 读取正文，但原页跳转需要应用内源书关联。

### 最终产物

- Debug 的 `assembleDebug testDebugUnitTest lintDebug` 完成；Lint **0 错误、39 警告**。首次 `assemblePreview` 因可选 JP2 类缺失失败，窄范围处理后 `assembleDebug assemblePreview --offline` 成功，未关闭压缩或全部忽略 R8 检查。
- Preview **0.4.0-preview / versionCode 8**，R8/资源压缩，40843044 字节（38.95 MiB）。APK 变大来自 PDFBox/OCR 原生运行时；**不包含 OCR 模型、EPUB/PDF 样书或私人数据**。
- `E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`；SHA-256 `5cf416860a8bf8690d66d2dc697d0536b5b12fd9f1120d67b4c0d4f95647a2bc`。Debug 在 `E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`。
- 签名 v2 验证、16 KB ZIP 对齐、arm64/x86_64 原生 PT_LOAD 16384 对齐检查通过；APK reader.js 与最终源码逐字节一致。Preview 在专用模拟器覆盖安装、冷启动首页成功（约 994 ms）。没有在 Preview 重跑全部转换/OCR 设备用例，Debug 的转换质量测试不能描述为 R8 全量验收。
- 测试临时通知授权已恢复为未授权；应用仍允许用户拒绝通知授权。原始工作区 PDF/EPUB 未修改、未提交，输出 EPUB 也被 Git 忽略。

### 截图与未覆盖

- `E:\ReadX\docs\screenshots\pdf-040-scrolled-controls.png`、`pdf-040-paged-controls.png`：自生成 PDF 的后续页控件及横向三分屏真实截图，不是私人 PDF。
- 未完整验收复杂扫描表格/插图/公式/脚注、竖排、手写、繁体 OCR、所有 ABI/旧系统/低端真机、长期电量/内存/系统后台配额。native OCR 取消可等待当前页完成；无可重排正文会失败，密码文档回退原版。
- 资源区域检测采取保守启发式，不是完整语义识别，扫描图内的复杂对象仍可能漏判。导出仍应与原 PDF 对照；普通阅读进度没有升级到跨进程通用 CFI。


## 0.3.3 PDF 点击热修复（2026-10-03）

- 修改高级纵向查看器的单击监听，取消底栏对 AndroidX 滚动沉浸状态回调的依赖；横向单页补齐左/中/右三等份点击。代码维持原生滚动、缩放、长按选区与标记编辑路径。
- **用户明确要求不做测试，直接产出 APK**：本轮不运行单元测试、设备测试、Lint、安装或手势运行验收。此前 0.3.2 的测试结果不代表本次改动已通过设备验证。
- `assembleDebug assemblePreview -x lintVitalPreview --offline` 构建成功；Preview 使用 R8/资源压缩及本机调试签名。仅构建成功，**未做设备手势验收或签名验证**。
- 版本：**0.3.3-preview / versionCode 7**。Preview：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`，5304422 字节（5.06 MiB）。SHA-256：`d4dfc07e751c1d0112c2267eb108b73fc54fc47a87aa6e795636527fb9389d91`。
- Debug：`E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`。两种包同 applicationId，安装会互相覆盖；不等同于书库备份。实际点击行为仍需用户验证。


## 0.3.2 首屏与全书分页性能（2026-10-03）

### 范围与真实输入

- 专用 `Pixel_6_API_36` / `emulator-5554`，Android 16 / API 36，复用已在线 AVD。没有卸载、pm clear 或 wipe-data；设备测试使用保留 APK 参数，偏好在各用例结束恢复。
- 自生成 TXT 六章、EPUB 六章用于基线/优化对照；另有自生成多章 EPUB，含外部本地 CSS、标题、SVG 图片和 emoji，用于比较统计缓存与前台实际分页。
- 用户提供的本地 EPUB：766705 字节，导入后 33 个可读章节。原 ZIP spine 包含 34 项，实际可读章节以解析结果为准。用户 PDF：1051981 字节，实际 152 页。文件仅推送到专用模拟器的临时验收目录，用例只删除自己导入的副本与测试输入，不触碰原文件或原有同内容书籍。根目录 EPUB/PDF 已加入 Git 忽略；不提交文件、正文或私人批注，不保存真实书的公开截图。
- Room 仍为 v4，无数据库结构/迁移、PDF 生产代码、依赖或权限变更。

### 性能记录

同一模拟器、Debug、自生成六章样书；计时为打开请求到前台 `!restoring && alpha >= .99`，全书时间为 UI 出现真实绝对页码。测试轮询/调度有误差，不是正式 Macrobenchmark，也没有控制 OS/WebView 编译缓存、所有进程冷启动或资源负载。

| 样书/操作 | 修改前 | 最终修改后 |
|---|---:|---:|
| TXT 无分页缓存打开 | 2795 ms | 403 ms |
| TXT 缓存后进程内重开 | 973 ms | 290 ms |
| EPUB 无分页缓存打开 | 1469 ms | 340 ms |
| EPUB 缓存后进程内重开 | 781 ms | 308 ms |
| TXT 全书首次完成 | 4375 ms | 997 ms |
| EPUB 全书首次完成 | 3154 ms | 913 ms |

两轮首屏都包含各自当时的 WebView/系统缓存状态；不能把表中的百分比推广为任何真实书籍的固定收益。两轮当前章均为 17 页，计数没有为了加速降为估算值。

用户 EPUB 最新定向记录：正文首次就绪 **718 ms**，全书初次实测完成 **5196 ms**，更换排版后的缓存重开 **444 ms**；默认 20px 为 **883 页**，24px 为 **1266 页**。总页数依赖本次设备、视口、字体和样式，不是书的印刷页码。用户 PDF 横向首张位图就绪 **1505 ms**（PDF 引擎未改，此值不是本轮优化收益）。

原始诊断仅在忽略目录 `.research/pagination-baseline.txt`、`pagination-optimized-final.txt`、`pagination-final-device-tests.log`，不作为运行时依赖。

### 测试及发现的问题

- JVM：**28 项，通过；0 失败**。新增布局指纹各维度、部分/完整缓存、损坏输入、五种/24 份限额、取消续算、前台不一致时失效、失败重试、邻章优先，以及书籍不能伪造加载代次标记。
- 定向设备：**6 项，通过；0 失败、0 错误、0 跳过**。`AsyncPaginationInstrumentedTest` 两项；`PaginationPerformanceInstrumentedTest` 一项；`ImmersiveReaderInstrumentedTest` 一项；`LocalBooksAcceptanceInstrumentedTest` 两项（只在本地提供输入时执行，缺失时明确跳过）。
- 前台各章页数与自生成 HTML 的完整缓存一致；完整缓存没有遗留隐藏 WebView。验证真实右侧点击、左滑、章末衔接/上一章末页、分页↔滚动、搜索结果跳转、关闭重开位置恢复、字号/边距重排、快速连续修改排版和真实长按批注/颜色。
- 早期验证曾失败：后台清理错误线程调用 WebView、分页↔滚动先恢复比例再定位文字的竞态、原生翻页后立即捕获上一页 DOM，以及同 URL 快速重排读取旧完成回调。分别用显式 Main 调度、找到锚点时不先恢复比例、视觉提交后捕获/新文档滚动清零、受控加载代次校验和待恢复锚点延续修复。没有增加任意长等待来掩盖失败。
- 本地 EPUB 验证初次统计、首/中/末章抽查、改字号文字锚点、全书重新统计与缓存重开。PDF 验证位图真实生成、翻页、重建恢复、切换高级纵向以及搜索入口打开；**不代表全文搜索准确性、扫描件 OCR、复杂 PDF 或低端设备全部验收**。

### 构建与交付

- 最终 `assembleDebug testDebugUnitTest lintDebug assemblePreview --offline`：通过。Lint **0 错误、31 警告**；其中统计 WebView 启用受控 native evaluateJavascript 的通用安全提示已审核，书籍脚本仍删除并由 CSP 禁止，无桥接/联网/文件权限放宽。没有整体禁用 Lint 或新建基线。
- Preview 启用 R8/资源压缩，`apksigner verify --verbose` 通过（v2、本机调试密钥）。APK 内 `assets/reader.js` 与源码逐字节相同，没有打包 EPUB/PDF 测试书。
- 已在专用 emulator-5554 覆盖安装 Preview，核实 **0.3.2-preview / versionCode 6**。冷启动进入首页成功；打开内置示例真实正文并显示 **2 / 3** 精确页码。未将 Debug 的完整定向用例全部再次用于 Preview。
- Preview：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`，**5304422 字节（5.06 MiB）**。SHA-256：`72c5605ef6edb58a412096337658c8945a3bb2b470eaf845c351d5863fdeb19a`。
- Debug：`E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`，74646653 字节。Debug/Preview applicationId 相同，互相安装会替换；Preview 不是正式发行签名。

### 截图与边界

- `E:\ReadX\docs\screenshots\reader-032-async-pages.png`：最终 Debug 真实运行、自生成 EPUB，连续重排后正文与实际全书页数。不是用户 EPUB 的截图，也不是设计稿。
- 未运行全量设备套件、旧系统/低端真机、大字体/旋转/多窗口压力、完整 EPUB 固定版式兼容、完整 PDF 深度套件；TXT 原生引擎对照尚未实现。普通阅读进度跨进程仍为章节/fraction，会话内重排才优先文字锚点。不承诺任意书籍毫秒级秒开或零卡顿。


## 0.3.1 阅读界面重构（2026-10-03）

### 本轮验证范围

本轮只运行两项定向设备用例，不重跑 PDF 长回归或全量设备套件：

1. `ColorMigrationInstrumentedTest`：真实创建 v3 SQLite 数据库，再显式迁移 v4。保留书籍、章节、标签、进度与两条旧重复标记及各自笔记；补默认色/区间键；同区间 upsert 不新增重复记录；取消附带笔记的高亮保留 NOTE；书籍删除后的批注级联。
2. `ImmersiveReaderInstrumentedTest`：自生成 EPUB 真实运行，检查默认隐藏、正文高度使用超过 80% 物理窗口且显隐不改变测量高度；真实长按选区弹出菜单；选择蓝色保存；重复标记原记录更新；阅读页点击标记取消；设置页紫色强调色与系统动态配色实际渲染值一致。

最初设备未连接；启动已有专用 Pixel_6_API_36（headless，不清空数据），完成后在 emulator-5554 执行。冷启动时最初等待失败，随后按真实异步节点/绘制状态等待，未增加无界等待。测试坐标只来自自生成样书；偏好在结束恢复。早期测试的空 DOM 结果强制转换导致测试进程退出，已改为安全解析与条件等待，不是通过卸载/清空应用解决。

### 本轮已实现

- TXT/EPUB 正文填满安全阅读区域，取消固定 64dp/100dp 留白；上方只有覆盖式返回，下栏为目录/批注/进度/背景/排版，默认隐藏。
- 选区旁浮动操作条：复制、荧光笔、划线、写想法；六色选择；点已有标记改色、取消或编辑笔记。不添加听书、AI 或无实现的查询按钮。
- 同选段同类新标记事务更新旧记录；HTML 按不重叠片段绘制，PDF 单次透明合成，重复/交叠不加深。历史记录和私有笔记不静默清理。
- 完整动态配色、静态主题和 RGB 自定义色；纸张独立；继续阅读卡片不再固定绿色，设置页提供实际颜色预览。
- PDF 默认隐藏控件和满安全区域，移除固定标题高度；标记颜色/upsert/点击取消接入。PDF 专项未做设备复验，不宣称复杂文档全面通过。
- Room v4，新列 color/anchorKey/updatedAt；历史 schema 1/2/3 保留，新 schema 4 已导出。未新增应用依赖或网络权限。

### 交付验证

- 最终 `assembleDebug` / `assemblePreview`：通过，Preview 启用 R8/资源压缩。
- `testDebugUnitTest`：20 项通过，0 失败；`lintDebug`：0 错误、30 警告（未增加整体基线或关闭检查）。
- 当前报告的定向设备用例：2 项，0 失败、0 错误、0 跳过。未跑全量设备/复杂 PDF 套件。
- `apksigner verify --verbose`：通过，v2、本机调试密钥。最终 Preview 在专用 emulator-5554 覆盖安装并冷启动成功，reader.js 已逐字节核对最终源码。
- 版本：0.3.1-preview / versionCode 5，Room v4。
- Preview：E:\ReadX\app\build\outputs\apk\preview\app-preview.apk，5288038 字节（5.04 MiB）。SHA-256：`547d488733826218e86a139ef35e7462044315b285208287dd228c160312e4c6`。
- Debug：E:\ReadX\app\build\outputs\apk\debug\app-debug.apk。

### 真实截图（测试 EPUB，不是用户参考书或设计稿）

- E:\ReadX\docs\screenshots\reader-031-immersive.png：默认沉浸与全尺寸正文。
- E:\ReadX\docs\screenshots\reader-031-dock.png：仅返回按钮及新下栏。
- E:\ReadX\docs\screenshots\reader-031-selection.png：长按浮动菜单与六色选择。
- E:\ReadX\docs\screenshots\theme-031-live.png：动态取色/主题色真实预览。

### 已知限制

- 多颜色或交叠标记显示最新修改色，透明度固定，不画成多层深色；旧重复记录不为去重而丢弃私有笔记。批注导出/一致性备份尚未实现，升级不要先卸载。
- 应用主题色不等于纸张反色；Android 12+ 才支持动态取色，开启时壁纸色优先于自定义色。PDF 仍保留原文颜色，不做 OCR 或重排。
- 真机手感、厂商选字柄、复杂/横版 PDF、超长章节、字体缩放/横屏与大量批注的完整压力验收未覆盖。本轮没有 FPS 基准。

---

## 0.3.0 全书分页与批注（2026-10-03）

### 验证范围与状态

- 用户要求停止继续扩展设备测试，优先交付安装包；最终源码不再重跑完整设备套件。
- 开发中曾有一轮 10 项定向设备测试全部通过，覆盖 TXT/EPUB 三分屏、全书进度、批注重开/32sp 重排、注释返回、PDF 横向选字笔记/切回纵向、高级搜索/恢复、基础回退、横版布局、迁移与主题设置。
- 随后的 PDF 页码优化试验出现横版页的超时/零尺寸位图异常；测量后加载保护又造成加载等待。两项试验已撤回，恢复先前的直接加载及首个可见页逻辑。撤回后的最终包未重新执行这些设备测试，不能把历史通过记录视为最终全量通过。
- Room 为版本 3：显式 MIGRATION_1_2 + MIGRATION_2_3；历史 schema 1/2 保留，新 schema 3 已导出。v1→v3 和 v2→v3 设备迁移用隔离测试数据库验证，保留书籍/章节/标签/进度/封面路径及旧书签，删除测试书时验证批注级联。
- 最终源码 `assembleDebug` / `assemblePreview`：通过；JVM 17 项通过，0 失败；Lint 0 错误、18 警告；未以基线或关闭检查隐藏错误。
- 最终 Preview `apksigner verify --verbose`：通过（本机 Android Debug 签名、v2）。已在专用 emulator-5554 覆盖安装，MainActivity 冷启动成功；仅启动冒烟，不代表最终 PDF/批注全量复验。
- 产物：E:\ReadX\app\build\outputs\apk\preview\app-preview.apk，0.3.0-preview / versionCode 4，5221506 字节（4.98 MiB）。SHA-256：`1856f155f83927e475a3342ae385588528e04ce1d77bcb3af507e9443c12fc6b`。
- Debug：E:\ReadX\app\build\outputs\apk\debug\app-debug.apk。APK 内 reader.js 已与最终源码逐字节核对。

### 功能与使用方式

- TXT/EPUB 默认分页：左/右三分区点击翻页，中间显隐；链接和原生选字优先。阅读区域不随工具栏显隐重新测量。
- 底部直接显示目录、全书进度与页码；初次按当前排版逐章测量，未完成显示统计章节数。拖动预览章节，松手跳转；字号等改变后重新统计。
- 目录/设置全展开，排版滑条松手提交；设置页有主题色与动态取色。
- TXT/EPUB 长按并拖动选字，然后点顶栏「批注所选文字」选择荧光笔/下划线/笔记。定位为 UTF-16 偏移、原文/上下文和 href；歧义不猜测。系统菜单布局依 WebView/系统而异，顶栏是明确的备用入口。
- PDF 可从顶栏切换横向/纵向；横向左右滑页，双指缩放，长按拖动选中文字/区域后创建批注。较旧系统和无文字层文档支持区域标记，不宣称 OCR；横向搜索需切回高级纵向。
- 批注按书籍分组，兼容旧位置书签，支持编辑笔记/删除/返回正文。全部保存在应用侧，不修改原文件、不上传书籍。

### 必须手动补验

- PDF 横版短页、模式切换、复杂字体/密码/扫描件以及 beta 引擎的零尺寸请求异常；最新回退版本未复验，不能宣传 PDF 全面通过。
- 真机手感、厂商导航条、横屏/折叠屏、旧系统、超大字号、超长章节与大量批注的性能；没有 FPS 或压力基准。
- 全书分页是当前排版的页数，首次大书统计可能较慢；普通阅读进度与旧位置书签仍兼容章节/相对位置，书架百分比仍为章节等权估算。
- 注释非 spine 资源、固定版式 EPUB、通用 CFI、批注导出和一致性备份尚未完成。卸载会丢失应用侧书库/批注，升级不要先卸载。

### 本轮真实样书截图

- E:\ReadX\docs\screenshots\reader-030-highlight.png：测试 EPUB 真实选区标记。
- E:\ReadX\docs\screenshots\reader-030-large-font.png：32sp 重排后批注恢复。
- E:\ReadX\docs\screenshots\pdf-030-horizontal-annotation.png：PDF 横向文字笔记标记。
- E:\ReadX\docs\screenshots\pdf-030-vertical-annotation.png：切回纵向后的标记（来自开发中测试，非最终回退复验）。
- E:\ReadX\docs\screenshots\theme-030-settings.png：主题色/动态取色入口。

---

## 0.2.1 阅读体验修复（2026-10-03）

### 本轮结果

- 版本：0.2.1 / versionCode 3，Preview 为 0.2.1-preview；Room 仍为版本 2，无数据库迁移、无新增依赖或网络权限。
- `assembleDebug` / `assemblePreview`：通过，Preview 启用 R8 和资源压缩。
- `testDebugUnitTest`：15 项通过，0 失败、0 错误、0 跳过。新增纯黑安全 HTML 与小数密度下 viewport/多栏宽度一致性检查。
- `lintDebug`：0 错误、12 警告；保留依赖版本、KTX 风格和原生触摸无障碍建议等非阻断项，未关闭整体检查或增加基线。
- 定向 `connectedDebugAndroidTest`：3 项通过，0 失败、0 错误、0 跳过。设备为现有专用 Pixel_6_API_36（emulator-5554，API 36）。本轮没有运行全量 PDF、数据库迁移等设备套件。
- `apksigner verify --verbose`：通过，1 个签名者、v2 签名；使用本机调试密钥，不是正式发行签名。
- 当前 Preview 使用 `adb -s emulator-5554 install -r` 安装成功，冷启动 `am start -W` 返回 `Status: ok`，进程存活。Preview 只做安装/启动冒烟；本轮完整阅读定向测试运行在 Debug。
- `git diff --check`：通过。

### 定向覆盖与实际修复

1. TXT / EPUB 两项原有分页回归：同章多页、按钮/实际水平手势、退出重开、滚动切换、重新分页、章末衔接、上一章末页、书签。测试改为等待动画落位，不以任意延长超时掩盖问题。
2. 一项生成 EPUB 的综合回归：真实点击同章 `#note` 与跨章 `notes.xhtml#cross` 链接；检查目标停留、主动返回；滑条实际拖动至末页，检查同一 WebView 和同一加载代次，保证跳页不重新加载 HTML；检查末页整屏对齐、纯黑导航图标和重置全部默认设置。
3. 根因：同文档 fragment 不经过普通 URL 拦截，原页面的 `onPageFinished` 再次执行旧位置恢复。已为同文档历史更新增加受控返回导航，并保证每次加载只恢复一次；旧视图与旧加载回调不能覆盖当前导航。
4. 视觉检查发现末页还会被 Chromium 自身滚动边界钳制，补足净高度为零（1px 高、-1px 上边距）的尾部范围，避免最后一页偏移裁字。另统一整数 viewport 与多栏宽度，避免小数 CSS 尺寸累计漂移。
5. 系统栏由单一外层处理，阅读底部控件不与手势条重叠，纯黑背景延伸到安全区；页面/弹层真实截图已检查。PDF 原生安全区模型保持不变，仅同步根背景和系统图标颜色。

定向命令：

~~~powershell
. E:\Android\android-dev-env.ps1
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=io.readx.app.PaginationInstrumentedTest,io.readx.app.ReaderExperienceInstrumentedTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --offline
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assemblePreview --offline
~~~

测试只导入自生成样书，结束删除各自测试副本并恢复偏好；未执行应用清空、卸载或 AVD wipe-data。

### 当前产物与截图

- Debug：E:\ReadX\app\build\outputs\apk\debug\app-debug.apk。
- Preview：E:\ReadX\app\build\outputs\apk\preview\app-preview.apk。
- Preview 大小：5071644 字节，约 4.84 MiB。
- Preview SHA-256：`f8fd1e54dfe2432e8ab7b1ea7027d6639fe87d7c1d3c962f5e2a22cebe297b4c`。
- E:\ReadX\docs\screenshots\reader-021-note.png：测试 EPUB 的真实注释目标与「回到原处」。
- E:\ReadX\docs\screenshots\reader-021-progress.png：当前章节滑条实际跳到末页。
- E:\ReadX\docs\screenshots\reader-021-black.png：纯黑阅读、整页末页对齐与系统手势条。

Debug / Preview 使用同一 applicationId，互相安装会替换。升级没有主动清空书库；不要先卸载旧版本。

### 明确未覆盖与边界

- 真机动画手感、厂商手势/三键导航、旧系统、横屏、超大字体与长文档性能由后续手动验收补充，本轮没有 FPS 或压力基准，不能承诺所有书籍永不卡顿。
- 滑条页码仅对应当前章节和当前排版，不是全书固定页码；拖动预览、松手一次跳转。
- 注释返回仍是章节/相对位置，不是精确文字锚点；仅支持当前可阅读 spine 中的本地资源与锚点。返回链最多 32 层、只在会话中保留，不做弹出脚注或进程终止后的返回链恢复。
- PDF 完整功能回归未重跑；主题不等于 PDF 页面反色。

---

## 0.2.0 历史全量验收

以下是 0.2.0 的历史结果，不代表本轮全部重跑；产物请以上方 0.2.1 信息为准。

### 环境

- Pixel_6_API_36，Android API 36，S 扩展 17。
- Gradle 9.3.1 / AGP 9.1.0 / SDK 36.1。
- 验证日期：2026-10-03。

### 最终结果

- assembleDebug / assemblePreview：通过。
- testDebugUnitTest：13 项通过，0 失败。
- lintDebug：0 错误；仍有版本提示与 KTX 风格建议等非阻断警告。
- connectedDebugAndroidTest：12 项通过，0 失败，0 跳过。
- apksigner verify：通过，本机调试密钥签名，仅供个人开发预览。
- 压缩 Preview APK 已安装到模拟器；启动、书架、真实 TXT 页面、返回书架和重启均手动验证通过。

### 验收覆盖

1. 13 项 JVM：编码识别、TXT 章节与转义、EPUB 元数据/spine/nav、ZIP 安全、HTML 清理；新增默认分页/CSS 退出分页与跨章节总进度计算。
2. 4 项仓库设备测试：导入去重与进度、元数据/删除不修改原文件、失败导入回滚、长章节分块查询。
3. 1 项数据库升级：创建真实 v1 SQLite 数据库，再迁移到 v2；检查书籍、章节、标签、进度与章节数均保留。
4. 2 项分页设备测试：分别导入 100 段的 TXT 与 EPUB，断言一章分成多页、按钮翻页、真实水平手势、垂直位置不滚动、退出重开恢复页码、滚动模式、重新分页、章末自动进入下一章、前翻回上一章末、添加与展示书签。
5. 3 项 PDF：高级加载/页码跳转/重建恢复/搜索入口；基础回退；横版 PDF 的工具栏高度 <=57dp、内容紧接工具栏、文档使用超过70%的窗口空间。生成真实截图检查顶部留白。
6. 1 项阅读 UI：示例、目录、主题、搜索与结果跳转。
7. 1 项参考界面：生成带真实 PDF 首页与 EPUB 内嵌 PNG 封面的验收样书；检查实际封面、底部导航与书库筛选，保存模拟器截图。样书仅是 UI 验收数据，测试结束清理，不是用户的真实收藏。

### 修复过的实际问题

- WebView 未测量就加载 HTML，会将默认列宽/高度写入正文：已改为 doOnLayout 后加载，并使用实际可用区域的 CSS 像素。
- 100vh 在初次原生布局尚未完成时不可靠：改为测量后的固定页高，并等待 Chromium 排版稳定/视觉状态提交。
- Snackbar 覆盖底部导航：根据阅读/书架页面留出底部控件空间。
- PDF 系统栏与 Compose 标题栏重复边距：由原生根布局统一处理安全区，子工具栏 windowInsets=0，高度固定56dp。
- 横版短页面被居中：高级 PdfView 与基础图像回退均采用顶部对齐。
- 文本书库百分比只使用当前章 fraction：改为章节等权的全书估算；PDF 使用真实页数。

### 构建与设备验收

建议依次运行，避免 R8 构建与模拟器同时争用内存：

~~~powershell
cd E:\ReadX
. E:\Android\android-dev-env.ps1
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assemblePreview
.\gradlew.bat connectedDebugAndroidTest
~~~

如果只检查已下载依赖，可添加 --offline。

### 预览包（历史信息，以下路径现已被 0.2.1 覆盖）

- 路径：E:\ReadX\app\build\outputs\apk\preview\app-preview.apk
- 版本：0.2.0-preview / versionCode 2。
- 大小：5055260 字节，约 4.82 MiB。
- SHA-256：`1add6747d85d1743fc370a2c6a606a44f90bb8a8b0deb6a95e966741a91fc23d`。
- Preview 与 Debug 使用同一 applicationId 和本机调试签名；升级不会主动清空书库。正式发布签名仍需独立配置。

### 截图

- E:\ReadX\docs\screenshots\ui-preview.png：书架、屏幕分页、PDF 适配实机拼图。
- bookshelf-reference.png：新的参考书架（验收样书）。
- txt-paged.png / epub-paged.png：同章多页的真实截图。
- pdf-landscape-page.png：横版 PDF 顶部对齐。
- txt-bookmark-tab.png：持久化书签列表。
- preview-reader.png：R8 压缩包实际阅读。

### 仍需验证

真实旧系统、平板/折叠屏、大字体、复杂 EPUB 版式及大量真实 PDF 的完整兼容性与长期压力测试尚未完成。当前分页位置恢复仍使用章节/相对位置，不是精确文字锚点。PDF 搜索入口测试不代表所有 PDF 的搜索质量已全面验收。
