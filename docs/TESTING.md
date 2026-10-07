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



## 0.7.3 PDF 文字选取、滚动跳转、反色与 OCR 并行（2026-10-06）

### 范围与环境

- 本轮为普通版：versionCode 15，versionName 0.7.3 / 0.7.3-preview，不内置 OCR 模型。Room 仍为 v6，无数据库结构/迁移或源文件修改。
- 专用 `Pixel_6_API_36` / `emulator-5554`，Android 16 / API 36；启动现有 AVD，不 wipe-data、不卸载或 pm clear。测试使用自生成 PDF / 已有公开生成的裁边样书；模型为固定官方 tessdata_fast，放在测试设备 qa-models，不上传书籍。
- 最终构建：`assembleDebug testDebugUnitTest lintDebug assemblePreview --offline -Pkotlin.incremental=false` 成功。首次增量测试命中旧 ReaderSettings 构造器 ABI，出现 NoSuchMethodError；关闭本轮增量编译后重新验证，并非忽略失败。

### 已通过

| 验证 | 实际结果 |
| --- | --- |
| JVM | 55 tests，0 failures / errors / skipped；包括新增并行核数、native/位图预算与低内存调度测试 |
| Lint | 0 errors，63 warnings；未禁用检查/新增隐藏问题的 baseline |
| PDF 目标设备回归 | `PdfInteractionRegressionInstrumentedTest` 8/8：普通长按后真实引用、拖手柄引用确实扩大并落盘；横向及裁边；扫描页不创建伪文字/区域选区；基础/裁边列表双向滑块真实跳页和落盘；高级/横向/基础反色与重建恢复 |
| 高级跳页/手势 | `PdfFixVerificationInstrumentedTest` 验证原生 firstVisiblePage 而不只是乐观 UI 页码；`PdfTapRegressionInstrumentedTest` 原生纵向点击、横向三区翻页通过 |
| 裁边基础回归 | `PdfCropInstrumentedTest` 2/2：缓存预算/检测与高级裁边文字层搜索、原页坐标批注通过；不等于全部裁边手势已验收 |
| 转换隔离回归 | `PdfConversionInstrumentedTest` 4 项通过：损坏/密码/纯图失败不发布、文字层转换/去重/导出/删除、多扫描页缺模型与续算、取消恢复及损坏模型偏好保护；混合样书由 2 页扩为 4 页（3 页扫描），确实识别中英文正文 |
| OCR 性能与内容 | 独立 `OcrParallelPerformanceInstrumentedTest` 通过；多个识别器实际同时运行，结果正文和置信度断言通过 |
| Preview 压缩包 | R8/资源压缩成功；apksigner verify 成功（v2，1 signer）；在专用 AVD 覆盖安装并启动 MainActivity，am start Status ok，进程仍在，无该进程 AndroidRuntime 崩溃输出。未把安装启动当作压缩版全部阅读功能验收 |

### OCR 性能方法与数字

同一张自生成 1694×2400 位图，22 行英文；固定 tessdata_fast eng、PSM_AUTO；独立模型实例先预热，6 页/组，两轮串行与并行交替执行；排除模型初始化、PDF 提取/渲染、EPUB 打包和导入时间。不是整书端到端或真机基准。

- 设备可用核数 4，memoryClass 192 MiB，实际调度 3 个识别器；观察到最大 3 个同时识别。
- 最终隔离重跑：串行 4871 / 4672 ms，平均 **4771.5 ms**；并行 2338 / 2328 ms，平均 **2333 ms**；吞吐比 **2.045×**。
- 前一轮同样方法为 1.948×；不承诺固定倍数、持续满核、真机温控/功耗或复杂扫描件质量不变以外的广泛兼容。
- 首次性能测试遗漏 PDFBoxResourceLoader 初始化，造成类初始化失败并污染同进程转换测试。已修正测试初始化，停止那一次专用测试进程（没有清数据），随后隔离重跑通过；测试模型会话清理限 UUID 命名的本测试目录。

### 仍失败 / 未覆盖（不可计为全部通过）

- 合并的 18 项回归最终一轮为 **15 通过 / 3 失败**，不是全绿：旧手动裁边规则保存用例、全局转换开关用例，以及图像样书阶段状态断言（预期 FAILED，实际 WAITING_MODEL）。图像样书用例在独立转换组重跑通过，组合状态不一致仍保留记录。
- `PdfCropPlatformInstrumentedTest.basicVerticalProgressManualRulesAndReopen` 在最后的手动裁边确认/规则断言处超时；没有据此声称裁边手柄全部通过，也未混入无关裁边架构替换。测试原始触摸确认方式已保留。
- `globalSwitchPersistsAndStartsOnlyWhenPdfIsOpened` 在未授权通知时被系统权限弹层遮住，出现 No compose hierarchies；专用设备预授予通知权限后，该用例仍在任务出现处超时，未完成定位。应用通知授权逻辑没有被绕过或修改。
- 隔离完整转换组为 **4 通过 / 1 失败 / 1 跳过**；跳过的是可选私人 PDF，未提供到专用设备，没有把用户工作区书籍上传或复制进行此测试。
- 未跑全量设备套件、旧系统、高字号/横屏、复杂多栏/竖排/旋转 PDF、跨页选字、长扫描件温控/低内存长期压力；基础旧系统无选字 API 时只能提示，不以框选冒充文字。
- RGB 反转也改变彩色图片；高级查看器自身的内部选区/搜索 UI 随其 RenderEffect 一起反转，应用独立批注与下栏不反转。

### 真实截图与交付

截图为自生成英文 PDF，不是原书或设计稿：

- `docs/screenshots/073-pdf-text-selection-generated.png`：真实文字手柄扩选。
- `docs/screenshots/073-pdf-night-generated.png`：实际页面 RGB 反色。
- `docs/screenshots/073-pdf-regression-overview.png`：上述原始截图等比例缩小并排，只增加标签。

本轮核实的普通版 APK（Preview 为本机调试签名，不是正式发行签名）：

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `E:/ReadX/app/build/outputs/apk/debug/app-debug.apk` | 119359914 | `EEDF3A2BE326598EA98EC29C9A04C6A9D5D9661EEAC6C29B36ED3B78A5B18F80` |
| `E:/ReadX/app/build/outputs/apk/preview/app-preview.apk` | 41235265 | `3B31FD10E06B8B141EBD78011E764A64E5E750D1D7D69AEE97772C2B4941FD2C` |

Debug / Preview 同 applicationId，安装会替换现有应用；普通版无内置模型，但保留应用私有目录内已经导入的模型。书库备份未实现，不能把一次模拟器覆盖安装当成完整备份验收。


## 0.7.4 EPUB / TXT 繁简显示（2026-10-07，用户要求只编译）

- 用户先要求提交上一轮；已提交 `1adc59c`（PDF 选字/跳页/反色与 OCR 并行），未推送。本轮新增繁简代码留在工作区，未自动提交。
- versionCode 16，普通版 0.7.4 / 0.7.4-preview。TXT 原生、TXT WebView、EPUB 接入原文/简体/繁体显示，原文批注与显示复制分离，模式/字典版本进入缓存键；固定 OpenCC 文本资源与完整 Apache-2.0 许可入包，无新增网络入口/Room 迁移。
- 最终执行 `assembleDebug assemblePreview --offline -Pkotlin.incremental=false`，**BUILD SUCCESSFUL**；包含 R8/资源压缩及 AGP 默认必需的 Preview lintVital 构建步骤。字典大小/SHA-256 构建准备检查通过。
- 首轮编译发现新增显示复制路径引用了未定义 source，已改为 IO 中从 e.source 读取；最终构建来自修复后源码，不交付首轮失败产物。
- **没有运行** JVM 单元测试、connectedDebugAndroidTest、设备安装启动、UI/性能测试或完整 lintDebug；不把以前 0.7.3 的报告/截图当作 0.7.4 繁简功能通过记录。本轮没有新增“已实测”截图。
- 切换/重排/恢复、复制、旧批注、原文搜索、复杂 EPUB CSS/ruby/跨节点词组、罕见扩展字与大文件内存均未做本轮运行验收。当前保持 UTF-16 长度的转换不覆盖变长扩展字，全文搜索仍按原文；不宣称完整 OpenCC 所有模式或台港词汇转换。

本轮核实的普通版 APK（同包名，Preview 为本机调试签名）：

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `E:/ReadX/app/build/outputs/apk/debug/app-debug.apk` | 120280562 | `79CC5E4C4E6E86769CA0DFFFC2DF4B5589D07A0BE729834CE60CACA8D719C100` |
| `E:/ReadX/app/build/outputs/apk/preview/app-preview.apk` | 41687421 | `D1FD5A85FDA3590B30313CB6DDAED3EE56C1E8A1FA0C445F0B298299B090517F` |

已读取 Preview 包元数据确认 `io.readx.app` / versionCode 16 / `0.7.4-preview` / min 28 / target 36；这不是设备安装或阅读验收。繁简字典已内置，不需 OCR 模型；普通版仍不内置 OCR 模型。


## 2026-10-07：CI/CD 重建（仅普通版）

- 远程 main 原 `.github/workflows/build.yml` 已先单独删除并推送，保留旧运行历史与已有 Release。
- 新流程：PR main 验证；main push（含合并）按 verify → package → publish 门禁顺序生成普通版 R8 Preview 和独立预发布；按用户新要求不下载／内置 OCR 模型。仅 publish 使用 contents write，PR 无签名 Secret。
- 本地 actionlint 1.7.12（官方发布 ZIP SHA-256 校验后使用）：新 workflow 通过；交付脚本 7 项 stdlib 单元测试通过，含真实普通产物／哈希保留、误用内置版拒绝、越界路径、空产物、篡改、运行身份及错误包名／构建类型拒绝。
- 本地 Gradle --offline --no-daemon testDebugUnitTest lintDebug assembleDebug assemblePreview（ciVersionCode=100001、ciVersionSuffix=-ci.1.1）：通过，耗时 8m 3s；55 项 JVM 测试，无失败／错误／跳过；Lint 0 错误、63 警告。普通版 R8 Preview 从真实 AGP metadata 暂存成功、apksigner 验证通过，ZIP 检查没有 .traineddata 模型。此本机包仍使用本机调试签名，不冒充 CI 签名产物。
- 本机 adb devices 无在线设备，未启动／安装／清理本机设备；API 36 设备验证交由专用 CI AVD。云端设备测试与真实 Release 尚待实际运行结果，不视为已通过。
- 一次性 CI 专用签名密钥的自动创建／上传操作被执行安全策略拦截；未上传本机密钥，未将密钥写入仓库。管理员需按 `docs/CI_CD.md` 手动设置 Secret；缺失时 package 明确失败，不发布不可持续更新的随机签名包。


## 2026-10-07：首轮 CI 设备套件失败与修正

- 核实手动运行 37564691119（源码 4cc9af8）：JVM／Lint／设备测试 APK 编译通过；专用 API 36 AVD 实际执行 62 项，18 失败、10 跳过、34 通过，发布 job 按门禁跳过，未产生新 Release。完整日志／XML／每用例 logcat 已下载到忽略的 .research/ci-37564691119，不提交诊断或书籍内容。
- READX_CI_PREVIEW_KEYSTORE_BASE64 Secret 已由用户设置（只查询名称，不读取／输出值）。本次失败不是缺 Secret；私钥正确性还需后续签名步骤验证。
- 更新旧 WebView 专属 TXT 验收样书，按书显式选 WEBVIEW；原生默认引擎继续由 NativeTxtInstrumentedTest 验收。分页缓存预期补 fontId 与 textScript／字典版本，不使用过期键等待不存在的缓存。
- 更新沉浸式正文／PDF 控件的真实点击路径、全部设置、目录搜索与书签弹层，使用当前 content description／页码；PDF 按覆盖式布局和居中偏移验收，不强迫生产界面恢复常驻工具栏或旧文案。
- 无 OCR 模型时，图形页转换验证 WAITING_MODEL、无结果书／无正文写入，而非读取不存在的 qa-models；有模型时仍验收正文失败。未下载模型、未内置模型、未增加跳过或忽略失败。
- CI 明确 bundledOcr=false；全套 connectedDebugAndroidTest 仍为发布门禁。测试生成 QA 截图在模拟器退出前收集到报告，不上传用户样书。
- 本地 assembleDebug／assembleDebugAndroidTest／lintDebug --offline --no-daemon 通过（7m）；交付脚本 7 项通过、actionlint 通过。本机专用 Pixel_6_API_36 启动后长期 offline，已停止本轮启动的两进程，未安装、清空、卸载或 wipe-data；未把编译成功称为设备测试通过。
- 修正后的云端全套设备测试和后续签名／Release：待实际复验。

- 检查固定提交的 emulator-runner src/main.ts 与 script-parser.ts，确认 script 输入按每行拆分，逐条 sh -c 执行；原 YAML 多行 Bash 函数／pipefail 与启动脚本不能直接运行。已改为单行 bash scripts/ci-device-tests.sh 和 bash scripts/ci-preview-smoke.sh。新增 4 项模拟 adb／Gradle 的 Bash 合约回归测试，连同原交付 7 项共 11 项本地通过，确认 Gradle 非零状态不会被收集截图掩盖，启动异常／crash 会阻止交付。


### 第二轮云端复验（548a54e / run 37568343088）

- 真实 62 项设备测试：44 通过、8 失败、10 跳过；JVM 55 项通过，脚本 11 项与 Lint 通过。失败未被忽略，package／publish 仍跳过，无新 Release。
- 原失败中的 EPUB 实际分页缓存、文字标记、分页／滚动切换、恢复位置、长按选区、PDF 真文字批注与链接返回等已在这次执行通过；尚余样例树歧义、漏设 WebView 的 TXT 样书、PDF 基础路径现已使用 cropped tag、原生安全区容器层级断言及转换设置页瞬态根节点检查。
- 两项此前通过的 PDF 交互用例这次暴露高级 AndroidX BitmapFetcher 的 0×0 渲染请求。核实固定 beta01 上游源码：SandboxedPdfDocument／PdfDocumentRemoteImpl 使用传入 bitmap size，应用自己的 renderSize 已限制最小 1。采用小范围生命周期修正：在宿主容器、刚创建的 Fragment 根 View 完成有效测量后才设置 documentUri，避免未测量视口触发首次请求；不升级依赖、不绕过系统能力、不给 PDF 添加空白边距。
- 此运行时修正递增本地版本 0.7.5 / versionCode 17；数据库与签名边界不变。新的编译与云端复验待结果，不能把异常已消失作为已验收结论。


### 暂停后继续验证（2026-10-07）

- 复核本地未提交修改、main 与 Actions run，无覆盖用户修改。暂停时的本地构建已中断，恢复后重新执行。
- 找到转换设置测试缺 Compose 根节点的直接原因：PDF 转换开关主动请求 POST_NOTIFICATIONS，API 36 系统通知权限对话框遮住应用。测试通过真实系统权限按钮拒绝（恢复 accessibility flags），继续验证无通知权限也能转换；不用新增依赖、临时授予／撤销权限或清空数据处理。
- 恢复后的本地 testDebugUnitTest／lintDebug／assembleDebug／assembleDebugAndroidTest --offline --no-daemon 最终通过（1m 1s，前次因尝试使用未引入的 GrantPermissionRule 编译失败，已改用已有 UiAutomation 并重新编译）；JVM 测试报告 55 项通过，Lint 无错误。交付脚本 11 项通过、actionlint 与 git diff --check 通过。
- 本轮提交高级 PDF 测量门禁及剩余 6 项旧测试定位修正，版本 0.7.5 / 17；云端全套设备复验与 Release 结果待实际运行，仍保留全门禁与普通版无模型策略。


### 第三轮云端复验（9e264df / run 37578836743）

- API 36 全套 62 项：48 通过、4 失败、10 跳过；JVM／Lint／脚本通过。通知系统弹窗处理、原生 TXT 章节路径与 PDF 安全区／重建断言通过。发布继续被失败门禁跳过，未产生新 Release。
- 剩余 UI 测试需滚动设置列表到目标、等待目录弹层退出及真实原生正文 ready 后再注入点击；未增加任意延时。
- 基础 PDF 仍显示加载状态，退出日志伴随 NativePdfSource 重复 close 的 Document already closed。资源 effect 调整为捕获组合时的稳定实例（与 key 一致），不在 effect 应用时重新读取已变化的 mutableState；NativePdfSource 在同一 Mutex 内幂等关闭，NonCancellable 保证 descriptor 释放，关闭后 render／select 提前拒绝。新增两项用自生成 PDF 的资源回归用例（重复／并发 close 和替换 source 独立性），等待设备实测。
- 高级 PDF 0×0 仍偶发，宿主测量门禁不足。核实 beta01 PdfViewerFragment 上游：onLoadDocumentSuccess 在内部 PdfView 赋文档、从 GONE 切 VISIBLE 之前调用。改为在实际 PdfView 下一次正尺寸布局后，才向 Activity 发布 document；应用自有 loading container 保持可测量，被自有正文层覆盖，不改 AndroidX 的尺寸／裁边布局。回调检查 View 与 document 身份，避免旧回调跨重建写回。
- 修改后本地 testDebugUnitTest／lintDebug／assembleDebug／assembleDebugAndroidTest 一次通过（1m 46s）；之前新 fixture 曾错误使用 Android PdfDocument.use 导致编译／Lint 分析失败，已改 try/finally 并重跑成功。进一步云端设备／R8／发布复验仍未完成。


### 第四轮云端复验（b365fe8 / run 37581565194）

- 64 项设备测试（含新增 2 项资源回归）：53 通过、1 失败、10 跳过；全部 PDF 用例、本轮资源回归、示例目录／搜索与转换权限闭环通过。该单轮通过不代表 PDF 全设备／长期压力已验收。
- 唯一失败是 MaterialDesignInstrumentedTest 仍找旧标题「应用主题」，源码实际分为「深浅外观／强调色与壁纸」。更新到真实标题与实际滚动目标；同一用例的阅读模式切换也通过可滚动父容器定位。
- JVM／Lint／交付脚本通过；失败仍阻止 package／publish，无新 Release。仅测试／记录修改不再递增功能版本，待完整套件与发布首轮真正通过。
