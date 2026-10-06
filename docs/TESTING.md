# ReadX 验证记录

## 2026-10-06 / 0.7.2 / versionCode 14（稳定性与交互体验修复）

### 完成本轮闭环

1. **批注跳转闪退修复（NPE / IllegalStateException 根除）**：
   - `PdfActivity.kt`：`ReadXPdfFragment` 内部增加 `pendingTarget` 延迟跳转保护机制，在 `pdfDocument == null` 时杜绝直接调用 `PdfView.scrollToPage` 导致的未捕获异常；在 `onLoadDocumentSuccess` 触发时安全消费跳转；在 `PdfActivity` 建立安全唤醒与 `try/catch` 容错。
   - `LibraryViewModel.kt` & `ReadXApp.kt`：点击批注时进行书籍格式精确分流，禁止 PDF 格式创建空章节 `ReaderSession`；书籍未在内存缓存时异步查库跳转至 `PdfActivity`。
2. **PDF 线性流式文字选区**：
   - 新增 `PdfFlowSelection.kt`：实现 `orderPoints` 端点排序与 `buildFlowBoxes` 逐行流式选区算法。
   - `HorizontalPdfScreen.kt` & `CroppedPdfScreen.kt`：选区拖拽时绘制多行流式高亮条预览；选区提取按流式顺序规范化并支持逐行流式 box 备选高亮/下划线。
3. **WebView TXT 滚动模式翻页交互对齐**：
   - `LocalWebReader.kt`：滚动模式（`!paged`）下 `tapZone` 上报统一设为 `0`。
   - `ImmersiveReaderScreen.kt`：`ReadingLayout.SCROLL` 模式下拦截三分屏翻页调用，仅做工具栏显隐切换。
4. **原生 TXT 滚动模式物理惯性滑动**：
   - `NativeTxtReader.kt`：集成 `OverScroller`、`VelocityTracker`、`ViewConfiguration.scaledMinimumFlingVelocity` 与 `postOnAnimation(flingRunnable)`，`ACTION_DOWN` 打断正在进行的惯性滚动，`ACTION_UP` 计算速度平滑减速，并修复空章节边界保护。
5. **OCR 繁体中文模型支持与在线下载**：
   - `ConversionData.kt` & `ReaderPreferences.kt`：增加 `chi_tra` 繁体中文模型支持，并配置官方 Fast 校验哈希（`10427807` 字节，SHA-256 `eb8da5839bceae72b1527e53f1604a43c22cfc16b67e05e55fe46006c9a93077`）。
   - `LibraryViewModel.kt` & `PdfConversionUi.kt`：支持单语言模型独立下载，UI FilterChip 增加“繁体中文”选项并在各语言状态行提供一键下载按钮。

### 实际验证与环境

- JVM 单元测试：`.\gradlew.bat testDebugUnitTest --offline` 通过，全部通过（0 失败 / 0 跳过）。
- 构建产物（本轮源码真实生成）：
  - Debug：`E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`，118,646,357 字节，SHA-256：`3C53AFE7A2DB998D27A99C09625455A96309C0F00043F2E31A23DE86147DD150`。
  - Preview：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`，41,218,885 字节，SHA-256：`58B93372A261EFD6B4801DBABE39E388B679F1E25ED6B22CDC5401772B9E5D65`（已开启 R8 与资源压缩）。

---

1. **本地字体管理与删除闭环**：
   - `LocalFontStore` 增加 `delete(id)`，支持清理私有 `.font` 存储文件、SharedPreferences 别名映射与内存 Typeface 缓存。
   - `LibraryViewModel` 增加 `deleteFont(font)`，删除生效字体时自动平滑回退为系统字体。
   - `ReadingTools` 字体选择列表为已导入字体增加删除交互按钮与二次确认对话框。
2. **PDF 批注绘制性能与零分配优化**：
   - `PdfAnnotationOverlay` 引入 `RenderedOverlayMark`，在 `updateAnnotations` 阶段预解析 `@ColorInt`、样式与分类，彻底消除了每帧 `onDraw` 内部对 `Color.parseColor`、`new Paint()`、`DashPathEffect` 与动态 `filter` 的频繁内存分配。
   - `CroppedPdfScreen` 预缓存 `ParsedCroppedPageMark`，复用内部 `Paint` 与图元坐标 `drawRect(l,t,r,b)` / `drawLine`，避免每帧分配 `RectF`。
3. **全局深浅外观与主题控制**：
   - `ReaderPreferences` 与 `Theme.kt` 新增 `appTheme` 设置项（跟随系统 / 日间 / 夜间 / 纯黑），与阅读正文纸张解耦。
   - 书库设置页增加“深浅外观”选择 FilterChip。
4. **单条批注快速复制与分类筛选**：
   - 批注卡片 `AnnotationCard` 顶栏增加一键“复制批注”按钮（自动拼接引文与笔记），附带色彩圆点标识。
   - 批注页增加“全部 / 高亮 / 划线 / 笔记 / 书签” FilterChip 过滤，并支持空结果快速重置。
5. **选区气泡响应式排版**：
   - `SelectionToolbar.kt` 与 `ImmersiveReaderScreen.kt` 选区微调按钮支持横向平滑滚动与动态展开图标，防止在小屏或大字体缩放下发生文本挤压或截断。
6. **搜索与元数据交互闭环**：
   - 搜索对话框提供正文关键词 `AnnotatedString` 高亮以及软键盘 `ImeAction.Search` 联动。
   - 精确页码弹窗支持 `ImeAction.Go`。
   - 书籍元数据对话框改用暂存 `pendingCover`，点击保存原子化生效，避免取消污染；空书库增加一键重置筛选。

### 实际验证与环境

- JVM 单元测试：**52/52 全部通过（0 失败 / 0 跳过）**。包括新增的字体校验测试与批注复制文本格式校验。
- Lint 检查：`.\gradlew.bat lintDebug` 通过，**0 错误，60 警告**（无新增警告与错误）。
- 设备测试：通过 `adb devices` 检测，当前无在线专用测试设备，按协作规范未启动 AVD 或做无通知设备安装。
- 构建产物（本轮源码真实生成）：
  - Debug：`E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`，119,711,426 字节（114.17 MiB），SHA-256：`F2242AD02570F2BE755378B8F3271FB80CACE21CA14BC6E2841D92EB898E83CC`。
  - Preview：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`，41,186,117 字节（39.28 MiB），SHA-256：`8A5EE1CDE78A376909640C073B44A05866315ADDC532A7F245336F4F3F9C9621`（已开启 R8 与资源压缩）。

---

## 2026-10-05 / 0.7.0 / versionCode 12

### 完成本轮闭环

- 合并书库主页，折叠/常驻最近在读；懒加载网格和五种排序，真实文件大小由IO读取。长按编辑书名/作者/标签与独立自定义封面，原书文件不修改。
- 阅读下栏真实页码滑条、章节/原页微调、点击/长按精确页码输入；未知页总数明确禁用精确输入。正文排除临时IME Insets，修正输入数字页后背景重排导致跳回其他页的问题。
- 默认单行选区气泡，颜色、词/句和跨页动作按需展开。原生空白选区吸附到附近词而不折叠，保护UTF-16代理对；WebView使用受控Intl.Segmenter/回退，启发式不等于完整中文语义分词。批注跳转短淡入与临时闪烁，不写入新的标记。
- PDF 完整原页预览、8手柄、磁吸、范围草稿应用/取消；手柄触摸区域单独排除系统返回手势，其他区域保留系统返回。验证原页数据/文件长度不变。
- 整书 Markdown/TXT 批注导出与复制，历史笔记保留；复制超过10万UTF-16单位时明确要求文件导出。独立本地字体TTF/OTF导入、大小/表目录校验、私有副本及双引擎缓存键；坏字体准备失败不使书库启动崩溃。
- 转换失败按原页/阶段给出原因类别，继续使用页检查点，只重新处理缺失/损坏页；没有增加破坏性迁移，Room仍v6。
- 经用户2026-10-05明确确认，加入默认关闭、可关闭的模型网络下载。仅固定官方模型清单/提交、大小+SHA256+本地初始化，真实字节进度；关闭取消和失效代次不激活旧任务，已有模型保留。没有书籍/笔记上传API。

### 实际验证与环境

- 专用 Pixel_6_API_36 / emulator-5554，API36、x86_64。最初模拟器黑屏/窗口服务失去响应；按用户要求关闭重开、不加载快照、不擦数据，最终以本次临时 -gpu swiftshader 启动恢复。没有修改AVD持久配置或wipe-data/pm clear/卸载。
- 当前设备测试使用软件图形渲染，**不用于与0.6.0硬件渲染的性能对照，不代表天玑700实机结果**。
- JVM：**50/50，通过，0失败/0跳过**；包括Markdown转义/历史笔记、词句/emoji/空白吸附、手柄磁吸、居中坐标往返、排序、字体布局键及既有解析/定位单测。
- Gradle connectedDebugAndroidTest：**11/11，通过，0失败/0跳过**，保留应用和测试APK。包含本地封面/TTF、模型默认关闭及代次校验、检查点保留、网格/排序/导出/复制/气泡/精确页码、同章跨页/引擎切换/字号重排/恢复、PDF真实手柄拖动/应用/取消/重建、v1–v6迁移链。
- 独立在线测试：**1/1，通过**。在用户允许的固定来源真实下载英文模型4,113,088字节，SHA256 `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`，本地Tesseract初始化/激活成功；随后关闭下载取消下一请求。测试恢复原模型偏好/设置，不删除已有模型。
- 模拟器默认网络仅部分连通，系统网络验证约束曾使任务一直等待；改为用户主动提交的有限下载任务直接尝试固定模型地址，离线则明确失败，不增加无目的轮询或自动重试。真实英文下载重新验证通过。
- 首轮黑屏、JPEG严格像素断言、测试时钟/触摸节点变化及未满足网络约束的失败/超时**不计通过**，没有通过增加任意长睡眠或清数据修复。原始诊断仅在忽略的.research中。
- 编译与Lint：通过，**0错误，60警告**；未关闭检查或建新baseline。
- 普通Preview：R8/资源压缩通过、apksigner验证通过、专用模拟器覆盖安装/启动Status: ok。最后保留0.7.0-preview和测试APK。本次Preview仅做安装启动冒烟；完整设备测试来自对应Debug源码。

### 已核实本轮成品

- Debug：`E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`，119,660,570 bytes（114.12 MiB），SHA256 `5bbab48621860880434417349fa46208824f05351ccc08b6238ff2ed642f9dd9`。
- Preview：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`，41,153,349 bytes（39.25 MiB），SHA256 `27bf6b1f6b67cb6985513ec2bb8eb6c053609f9b3b691684c4784028aec925a2`。

两包同applicationId；Preview使用本机Android Debug签名而非正式发行签名，普通APK不内置traineddata。先前历史章节中的同名APK路径已被本轮构建覆盖，旧哈希仅作历史记录。

### 报告与截图

- JVM：`E:\ReadX\app\build\reports\tests\testDebugUnitTest\index.html`。
- Lint：`E:\ReadX\app\build\reports\lint-results-debug.html`。
- 设备：`E:\ReadX\app\build\outputs\androidTest-results\connected\debug` 与 `E:\ReadX\app\build\reports\androidTests\connected\debug`。
- `docs/screenshots/070-grid-generated.png`、`070-selection-generated.png`、`070-crop-handles-dragged.png` 与 `070-polish-overview.png` 均来自自生成验收样书的真实运行，没有设计稿或私人小说截图。

### 未覆盖 / 限制

- 中文两个模型的真实联网下载未单独重跑；它们共用下载路径/清单校验，但不能称作已做所有语言下载实测。弱网/代理/长时间断网/后台系统杀进程、所有下载与模型激活竞争仍需扩展。
- 字体实测为模拟器系统Roboto TTF；其他复杂OTF/CJK/可变字体、损坏字体的全部平台差异、低端设备内存与性能、全部大字体/横屏/无障碍场景未全面验收。
- 文件导出写入和格式、复制实际通过；系统SAF提供器的所有外部网盘/存储路径未覆盖。导出是阅读整理文本，不是完整书库/定位数据恢复备份。
- 词句吸附是局部分段启发式，跨章节选段仍不支持，不宣称通用EPUB CFI。批注跳转是定位后短过渡/提示，不保证所有复杂版式均能平滑长距离滚动。
- PDF复杂布局/扫描文字选择仍依实际系统与文字层；更多角/边组合、原页旋转四边形、所有链接页内目的坐标、超长文档内存压力未全面验收。自动裁边保守检测不是智能重排。
- 转换检查点复用/坏页重试入口与错误类别已验证；并未构造每一种底层解析/OCR失败并重新做整书识别质量验收。没有联网OCR/LLM、时长统计或随机摘抄的伪造数据。


## 2026-10-05 / 0.6.1 / versionCode 11

- PDF 单页分页居中修复，包括裁边/非裁边绘制与触摸、标记坐标；纵向连续列表不变。
- 用户明确要求不测试：本轮不运行 JVM、设备测试或安装启动，不沿用下方 0.6.0 的结果作为此补丁验收。
- 构建成功：`assemblePreview --offline`，R8/资源压缩开启。普通版 Preview：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`，41,053,850 bytes（39.15 MiB），versionCode=11 / versionName=0.6.1-preview。SHA-256：`c5059652fb4266eb2fa996c328d0d18fbaad80cc288fb7f2c5e866ab4fb4425d`。本轮未运行测试、未安装或启动验证。


## 2026-10-04 / 0.6.0 / versionCode 10

仅记录本轮实际运行；构建成功、截图或历史测试数量不等于全面验收。普通 Debug/Preview 均未内置 OCR 模型。

### 环境、输入与方法

- 专用 `Pixel_6_API_36`，`emulator-5554`，Android 16 / API 36 / x86_64；没有天玑 700 实机。
- 用户指定的本地 TXT：8,922,848 字节。仅复制为模拟器临时测试输入，不加入 Git、测试资产或公开截图；本报告只记录数值，根目录 TXT 已加入忽略规则。
- 原版 v9/0.5.0 只加数值诊断；优化版使用最终对应源码。相同 AVD、同一文件、默认 ReaderSettings/视口。最终 20 次重测单独启动 instrumentation，不混用前面的 PDF/双引擎测试进程。
- **冷缓存**：每轮只删该测试书的派生文字/分页缓存并复位该测试书定位；不是每轮杀进程冷启动，不清系统 WebView 缓存。**缓存重开**：保留同一书的派生缓存。原版首轮包含首次 WebView 初始化。此对照不能代替完整进程冷启动 10 次或实机测量。
- Native 首次可交互等待真实 StaticLayout 绘制及下一帧提交条件；首次翻页等待 drawnOffset 变化。WebView 原版等待恢复完成/视觉提交，翻页等动画结束。测试轮询存在约 30–40 ms 粒度，不能把几毫秒差值当作精确硬件延迟。
- 从打开到各阶段的计时为**累计节点时间**，包含 IO/调度，并非每阶段独立 CPU 时长；原生排版与定位恢复节点部分重合。主线程数据是 16 ms heartbeat 的最大超额延迟，不是 Perfetto 严格阻塞归因。帧数据是 Window.FrameMetrics；内存为 Java heap 与 Debug.getPss。
- APK 覆盖安装并保留测试 APK；没有卸载、pm clear 或 AVD wipe-data。测试图书/测试数据库按范围删除，偏好恢复；结束时删除本次创建的模拟器临时 TXT 副本，原始电脑 TXT 保留。

### 首次可阅读 / 首次翻页对照

P95 使用 nearest-rank，n=10 时取该组最大值。

| 场景 | n | 原版可交互中位数 | 原版 P95 | 原生可交互中位数 | 原生 P95 | 原版翻页 P95 | 原生翻页 P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 冷缓存打开 | 10 | 3662.5 ms | 12519 ms | 296 ms | 978 ms | 303 ms | 53 ms |
| 缓存重开 | 10 | 3651 ms | 3750 ms | 108 ms | 140 ms | 298 ms | 58 ms |

模拟器达到本轮对照的快速可交互目标；**天玑 700 首次可交互 P95≤5s / 缓存 P95≤2s 仍未验收**。不将模拟器结果冒充实机达标。

### 帧时间、主线程延迟与内存

| 最终原生场景 | 每次运行的帧 P95：中位数 / 最大 | heartbeat 超额延迟：中位数 / 最大 | Java heap 中位数 / 最大 | PSS 中位数 / 最大 |
| --- | --- | --- | --- | --- |
| 冷缓存 | 167.8 / 218.8 ms | 32 / 154 ms | 8.89 / 11.89 MiB | 153.41 / 157.00 MiB |
| 缓存重开 | 118.2 / 150.8 ms | 21.5 / 32 ms | 8.25 / 10.46 MiB | 153.63 / 156.80 MiB |

帧 P95 仍有明显长帧，**不能称为全部流畅或无主线程卡顿**。原版没有独立 FrameMetrics/PSS/heartbeat，因此这部分只能记录优化版，不能作同口径前后比较。PSS 包含进程/图形/测试框架等；32 MiB 是裁边应用侧 LRU，不是进程总内存。

### 额外私人 TXT 压力场景（本地数值报告）

每个场景连续翻页 20 次，等待实际 drawnOffset 变化。全书统计在可阅读状态之后进行，首屏不等待它。

| 场景 | 可交互 | 翻页 P95 | 全书统计累计节点 |
| --- | ---: | ---: | ---: |
| 首次打开 + 全书后台统计 | 216 ms | 94 ms | 31793 ms |
| 超大当前章中部锚点恢复 | 111 ms | 33 ms | 82 ms |
| 同一测试书 1000 条高亮后的中部打开 | 82 ms | 35 ms | 83 ms |

后两项复用已完成的布局缓存；其几十毫秒统计节点是缓存命中，不能当作从零统计速度。原版中部/大量批注/全书统计的完整对照未记录，不能补填伪造基线。

### 实际功能与迁移验证

- **JVM：43/43，通过，0 失败/0 跳过**。包括原生 display/canonical UTF-16 映射与旧 DOM 兼容、中文/emoji/重复原文歧义、PDF 空白/暗背景/投影脚注页码保留、规则优先级及坐标逆变换。
- **最终定向设备：11/11，通过**（直接 instrumentation，含上述 2 个私人压力测试类）；另以 Gradle `connectedDebugAndroidTest` 运行 **9/9，0 失败/0 跳过** 的功能/迁移类，留下正式 XML/HTML 报告。未声称仓库全部旧 UI/OCR 测试本轮重跑。
- `NativeTxtInstrumentedTest`：真实三分屏事件、连续翻页/前后回退、引擎双向切换、字号/模式变化、中部锚点、笔记 upsert 不换布局、实际长按同章跨页选段、跨章真实第3页跳转、上一章末页衔接。
- `ReaderV6MigrationInstrumentedTest` + 历史 v1/v2/v3/v4 测试：显式迁移链至 v6，书籍、标签、章节、相对进度、旧书签、重复笔记、转换关联/级联行为保留；5→6 新列可空/默认关闭，schema 1–6 保留。
- PDF 样书为自生成 7 页：单栏、双栏、纯图片扫描页、空白、横版、页码/脚注/边注和内外链接、暗背景。高级路径搜索 `Searchable` 得到真实结果、原页矩形批注重开保留；强制基础路径的实际纵向滑动、原页进度/重建、手动拖左边/应用/取消、原文件长度不变；7 类预览自动检测/页码脚注保留及 LRU≤32 MiB。超大页尺寸的 renderSize 数值回归确保不因最低缩放比例产生超大位图。
- 开发中发现并修正快速翻页的协程赋值/IO时机、旧视图导航回调、PDF 视觉位置与懒列表过渡/持久化竞争、透明控件命中、手动规则草稿和截图未提交等问题；曾失败/终止的运行**不计通过**，原始诊断只在忽略的 .research 中。
- WebView 节点/文字缓存，触摸不全文重扫；已有标记只换色/编辑时原位 restyle，不替换全文 text nodes/重新分页。书籍脚本/CSP/加载代次安全约束不变。
- **编译 / Lint：通过，0 错误，58 警告**；没有整体关闭检查/建立新 baseline。预发布 API 和旧系统覆盖限制仍保留。

### APK / R8 / 签名

最终源码：versionCode=10，普通版 versionName=0.6.0；Preview 带 `-preview` 后缀。Debug/Preview 同包名 `io.readx.app`，互相替换，使用本机 debug 签名，不是正式发行签名。

- Debug：`E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`，119,250,107 bytes（113.73 MiB）。
- Preview：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`，41,053,850 bytes（39.15 MiB），R8/资源压缩开启。
- Debug SHA-256：`c373f4af90348106631d2e9f483bc7f278eade9efb7ed9ada41b672614b74c49`。
- Preview SHA-256：`13dabce8ea2acdf5257ca674edafe0fac247526167b0e7d0615d9dd012d79e3e`。
- Preview `apksigner verify` 通过，专用模拟器覆盖安装成功、MainActivity 冷启动 `Status: ok`，确认 versionCode 10 / 0.6.0-preview；最后保留 Preview 与测试 APK。此为启动冒烟，不是 Preview 所有阅读路径都重新执行设备测试。

### 报告及真实截图

- JVM HTML：`E:\ReadX\app\build\reports\tests\testDebugUnitTest\index.html`。
- Lint：`E:\ReadX\app\build\reports\lint-results-debug.html`。
- 设备 XML/HTML：`E:\ReadX\app\build\outputs\androidTest-results\connected\debug`、`E:\ReadX\app\build\reports\androidTests\connected\debug`。
- `docs/screenshots/060-native-txt-generated.png`：自生成中文/emoji TXT，实际原生阅读及笔记标记。
- `docs/screenshots/060-pdf-crop-manual-generated.png`：自生成双栏 PDF 的完整原页裁边预览。
- `docs/screenshots/060-pdf-crop-vertical-generated.png`：自生成 PDF 纵向视图。
- `docs/screenshots/060-reader-crop-overview.png`：上述真实截图的带说明拼图，没有设计稿或私人小说内容。

### 未完成 / 未覆盖（不是通过）

1. 天玑 700 实测；每轮进程冷启动 10 次；原版中部/1000批注/完整帧与内存的完整同口径基线；Perfetto 严格阻塞归因。
2. 超长 PDF 真实内存压力/系统回收、全部复杂背景/字体/密码文档、所有高级文字端点拖动/链接目的页内坐标、原页旋转四边形、大字体/横屏/无障碍完整回归。普通 PDF 页内恢复当前基于原页 y fraction，不宣称完整 x/y/zoom 会话恢复。
3. 跨章节文字选段、通用 EPUB CFI、任意复杂 EPUB/DRM 兼容、批注导出、联网、智能 PDF 重排和完整 KOReader 集成均不在本轮范围。
4. 自动裁边是保守像素启发式，不保证任意原文检测正确；手动仍可能裁掉重要内容。扫描 PDF 没有文字层就不能默认全文搜索。


## 2026-10-06 / 0.7.1 / versionCode 13

### 变更与修复范围

1. **版本号升级**：`app/build.gradle.kts` 中 `versionCode` 升级为 13，`versionName` 升级为 `0.7.1`（内置 OCR 时为 `0.7.1-ocr`），解决安装后仍显示 0.7.0 的问题。
2. **主题自定义配色调色盘**：
   - 依赖集成 `com.github.skydoves:colorpicker-compose:1.1.2`（经 Maven Central 解析成功）。
   - 在外观设置中将原本的 HEX 输入弹窗升级为 HSV 调色盘：包含 `HsvColorPicker` 色盘轮盘、`BrightnessSlider` 明度滑块，并保留双向同步的 6 位 HEX 输入框与颜色色块预览。
   - 选取颜色后即时生成全套 Material 3 配色方案并持久化到 `ReaderPreferences`。
3. **PDF 滚动模式页码滑块与跳转修复**：
   - 根因定位：① Compose `Slider` 在滑动与松手瞬间因状态重组时差导致 `onValueChangeFinished` 读取 `preview` 为 null；② 原生 `PdfView.scrollToPage` 派发异步滚动期间，旧页面的 `onViewportChanged` 回调提前触发 `pageChanged` 将 `page` 状态强制重置为旧页，导致用户体感“滑块弹回无效”。
   - 修复方案：① `ReadingProgressControl` 增加即时值引用持有器，确保松手瞬间 100% 捕获落点；② `PdfActivity` 统一实现 `requestJump` 机制，在跳转执行期间锁定 `requestedPage`，忽略旧视口暂态回调，直到抵达目标页或超时解开；③ `CroppedPdfScreen` 裁边模式采用 250ms 有界超时替代无界挂起。
4. **主界面「最近在读」取消常驻吸顶**：
   - 将 `ReadXApp.kt` 书架列表中的 `stickyHeader` 改为普通列表项 `item`，滑动时随书架自然滚出屏幕，彻底解决遮挡问题。
5. **PDF 批注菜单双重重叠消除**：
   - 修复 AndroidX `PdfView` 的原生上下文菜单（荧光笔、下划线、写批注）点击后直接触发保存或调起笔记输入框，不再二次设置 Compose `selection` 状态；在原生菜单准备时显式清空旧的自定义浮层。

### 验证记录

- **JVM 单元测试**：52/52 全部通过。
- **Lint 静态分析**：0 错误，60 警告。
- **设备交互与真机截图**：
  - 专用模拟器 `Pixel_6_API_36`（`emulator-5554`）在线验证。
  - `ThemeSettingsInstrumentedTest`：调色盘对话框呼出、HSV 轮盘选色、HEX 输入、应用落库与 Material 3 界面动态配色实时更新，2/2 测试通过。
  - `PdfTapRegressionInstrumentedTest`：纵向/横向滚动、触控唤起底栏、三区分屏翻页、导航栏底色无缝对齐，通过。
  - `PdfFixVerificationInstrumentedTest`：纵向滚动模式下底部进度控制翻页、滑块拖拽松手跳转、多页定位与数据库章节落盘，测试通过。
  - 真实运行截图：
    - 主界面自然滚动无吸顶：`C:\Users\WJHHM\.gemini\antigravity\brain\00ec0a1c-3378-4f9d-b049-48e3e1324a46\screen_scrolled.png`
    - HSV 调色盘弹窗交互：`C:\Users\WJHHM\.gemini\antigravity\brain\00ec0a1c-3378-4f9d-b049-48e3e1324a46\screen_colorpicker_opened.png`
    - 选色后动态全套主题预览：`C:\Users\WJHHM\.gemini\antigravity\brain\00ec0a1c-3378-4f9d-b049-48e3e1324a46\screen_applied_color.png`

### 交付产物

- **Preview APK**（开启 R8 代码混淆与资源压缩，使用本机调试签名）：
  - 路径：`E:\ReadX\app\build\outputs\apk\preview\app-preview.apk`
  - 大小：41,218,885 字节（39.31 MiB）
  - SHA-256：`CAA2F557301C993846C03540074E03BACC83E676D3D9FBC41CEF34969B52CF31`
- **Debug APK**：
  - 路径：`E:\ReadX\app\build\outputs\apk\debug\app-debug.apk`
  - 大小：120,366,862 字节（114.79 MiB）
  - SHA-256：`91DAF6FC82FD698487985657A160F11769D015EC8084E7B025B731AE2B468553`

