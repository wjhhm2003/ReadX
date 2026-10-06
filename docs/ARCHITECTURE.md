# 第一阶段架构与决策

## 数据流

MainActivity → LibraryViewModel → LibraryRepository → Room / 应用私有文件。

- Book 保存稳定 UUID、SHA-256 内容指纹、元数据、标签和阅读位置。
- Chapter 保存书籍 ID、spine/文本章节序号、标题、资源 href 和可搜索正文。
- Room 数据库为书库事实来源；无 destructive migration。
- files/books/<UUID>/source.<format> 为原始副本；content/ 为 TXT 生成 HTML 或 EPUB 解包资源。
- 文件选择器只授予导入时读取权限。后续阅读使用私有副本，不依赖原始 URI 的长期权限。
- 导入串行化；复制、哈希、解析在 IO 调度器；书籍和章节插入在数据库事务内。
- 进度普通滚动防抖 350ms；退出时的最终写入由仓库应用级 scope 完成，避免 Activity 销毁取消写入。

## 阅读引擎

当前使用 ParsedBook / ParsedChapter 和 Room 模型隔离解析层与书库，不把 WebView 句柄保存进数据库。

- TXT → 解码、分章、HTML 转义 → LocalWebReader。
- EPUB → 限额解包、container/OPF/spine/nav/NCX 解析 → LocalWebReader。
- PDF → 独立 PdfActivity：满足运行时条件时使用 AndroidX PdfViewerFragment，否则使用 PdfRenderer。

EPUB 自研适配器是基础实现，不是完整 EPUB 标准引擎。引入 Readium 后，必须迁移文字锚点而非简单沿用章节像素位置。0.3.0 的应用侧批注使用独立定位模型，保留后续引擎替换与迁移空间。

## 本地 HTML 安全

- ZIP 资源写入前检查规范化路径，拒绝绝对路径、Windows 路径和越界路径。
- 对实际解压字节数、资源数量和单资源大小设限，不仅依赖 ZIP 声明大小。
- 用户确认后，仅由原生 evaluateJavascript 执行内置 reader.js，书籍脚本与事件仍删除且 CSP script-src none。无 JS/native bridge，禁 file/content 访问。
- 只通过 appassets.androidplatform.net/content/ 拦截读取当前书籍的私有资源。
- HTML 清理脚本、内联事件、嵌入页面和危险 scheme；注入严格 CSP。
- 非本地链接拒绝导航；非本地资源返回 403；应用清单显式移除网络权限。
- 应用未对书籍内容做加密；关闭系统自动备份，直到实现一致性备份恢复。

## 设置与搜索

第一阶段少量阅读设置使用 SharedPreferences + StateFlow，后续复杂配置/提供器设置可迁移 DataStore。

搜索当前扫描 Room 中的章节文字。设计上支持中文子串检索，但不是 FTS；大书和大书库的索引、内存及速度优化仍待完成。PDF 书内搜索由 PDF 引擎承担，不加入全书库搜索。

## Expressive 与依赖版本

MaterialExpressiveTheme 来自固定的 Material 3 1.5.0-alpha01。该 API 为公开实验接口。Compose BOM 固定 2026.01.00，以匹配当前 SDK。

PDF Fragment 原生 View 层需要 Material 属性，所以 Activity 的 XML 主题继承 Theme.Material3.DayNight.NoActionBar；Compose 使用独立的 ReadXTheme。

Preview 构建启用 R8 与资源压缩，并以本机 Android 调试密钥签名，适合个人开发预览，不是正式发行签名。Release 构建不内置签名密钥。

## 首页约定

按照新的参考图要求：首页展示 ReadX 标题、最近阅读横向卡片、书籍列表和首页/书库/批注/设置底部导航。不展示宣传口号与隐私徽章。启动进入首页书架，不自动打开书籍。搜索与导入在顶栏/菜单，书库页提供筛选。

## 0.2.0 分页与 PDF 边距（历史设计，后续变更见 0.3.0）

TXT/EPUB 使用同一个隔离 WebView 的 CSS 多栏布局。分页大小来源于已测量的 AndroidView 可用宽高，再换算为 CSS 像素，不依赖尚未确定的 100vh 或默认视口。必须 doOnLayout 后才加载 HTML，否则初次加载会写入错误列宽，出现半页/错位。Chromium 排版稳定且完成视觉状态提交后才恢复位置、更新页码。原生按钮/手势按完整视口宽度跳转；目录、链接与搜索仍使用同一章节引擎。切换滚动布局不启用 JavaScript，也没有加入脚本桥。

PDF Activity 的原生根布局独占 systemBars/displayCutout/IME 安全区；返回 consumed insets。Compose toolbar 的 windowInsets 设为 0，原生高度固定为 56dp，书名和页码在同一个工具栏中显示。PDF 容器紧接工具栏下方，权重填满剩余区域；PdfView 与基础回退均使用顶部对齐，区分 PDF 原文留白与应用额外边距。

数据库版本 2 增加 totalUnits 与 bookmarks。MIGRATION_1_2 保留书籍、章节、标签、进度，填充旧文本书籍的章节数，并建立级联删除书签表。PDF 总页数由渲染/加载时写回，不将单页章内 fraction 冒充整本阅读百分比。


## 0.2.1 阅读交互与安全区（历史设计，后续变更见 0.3.0）

- 页内过渡由原生 `ValueAnimator` 驱动 WebView 横向滚动，220ms 收敛到整页；拖动跟随手指，短拖动回弹，跨章短淡出/淡入。关闭系统动画时直接落位。不使用网页 JavaScript、桥接或截图缓存。动画/手势中不回报暂态页码，也不反复保存数据库进度，只在落位后回报；释放或重排前结束过渡。
- 测量宽度换算 CSS 像素后取整数，并以相同比例换算/向下取整页高，确保 viewport meta（Chromium 按整数解析）与多栏宽度一致，避免小数密度转换在长章节累积横向偏移。多栏文末使用净高度为零的尾部伪元素补足最后一页右边距的滚动范围，避免 Chromium 将最后一页钳制到非整页位置造成边缘裁字。
- 页码弹层只控制当前章。滑条拖动只更新 Compose 目标页码，松手调用一次原生 `scrollTo`，不更换章节、不加载 HTML、不逐页渲染长距离跳转。排版/导航变化会关闭弹层，未排版时不展示虚假的总页数。
- 主界面外层 Scaffold 独占 `safeDrawing` 安全区，并消费其 padding；书架/阅读内层 Scaffold、TopAppBar 和底部导航明确使用零系统边距。系统图标随实际主题亮暗更新，关闭系统额外导航栏对比度色层。PDF 仍由原生根布局单独处理安全区，只同步根背景，不再叠加 Compose 安全边距。
- 每次阅读导航分配递增编号。WebView 的位置回调绑定原导航，旧页面释放不能覆盖新章节或同章锚点跳转。关闭阅读器仍保存最终落位。所有延迟排版/搜索回调检查加载代次与释放状态；尺寸变化也走统一排版稳定/视觉状态恢复路径，移除原来的固定延迟二次恢复。
- 同文档 `#fragment` 不经过 `shouldOverrideUrlLoading`，改由 `doUpdateVisitedHistory` 纳入受控导航；来源相对位置在触摸/键盘激活前记录，页面完成回调每个加载代次只恢复一次。点击章节内/跨章本地链接时保存来源章节与相对位置，再加载目标 fragment。首次加载只对目标整页对齐，不回写来源位置；后续排版变化按当前相对位置恢复，不重复应用旧 fragment。用户主动「回到原处」或系统返回才弹出返回栈。栈限 32 层、仅在 ViewModel 阅读会话内保留，尚未覆盖不在 spine 的注释文件或进程终止后的返回链。
- 纯黑正文与阅读背景使用 `#000000`，弹层使用稍浅的暗面区分层级。设置重置复用 `ReaderSettings()` 与原有偏好写入路径，不涉及 Room schema 或书库迁移。


## 0.3.0 全书分页、受控选区与批注

### 定位与安全

- `reader.js` 是应用资产，由原生在排版稳定后加载，不来自书籍或网络。没有 addJavascriptInterface/WebMessage bridge，也没有网页调用数据库的通道。回调检查加载代次和当前导航，数据传递使用 JSON 编码。
- 文字坐标是准备后 body 文本的 UTF-16 偏移；选区保存 start/end、原文、前后各最多 40 字符及章节 href。标记插入 inline span，不改变文本串；重开先校验原偏移和上下文，再尝试唯一的原文/上下文匹配，歧义不猜测。重排用同一锚点定位，不把页码当文字定位。
- 原生长按与选区拖动保持优先；窗口 ActionMode 标识选字期间不触发翻页。原生选区菜单接入批注，另提供明确的顶部批注入口，避免依赖不同 WebView/系统的菜单布局。
- 批注集合支持荧光笔、下划线、笔记及旧位置书签，保留书籍 UUID 和 chapter/fraction 兼容。Room v3 新增 annotations，显式 MIGRATION_2_3 复制旧书签，保留旧表与历史 schema；新的位置书签双表写入/删除使用事务。删除一本书时批注由外键级联清理，只处理应用副本。

### 分页与交互

- 第二个非交互、不可访问的 WebView 与正文同尺寸，串行按同一 LocalHtml/CSS 和字体缩放测量各章节；只加载一章，文件解析在拦截线程，所有 WebView API 显式在 Main 调度器运行，缓存 IO 在后台。退出/排版改变取消旧任务与回调。
- 缓存键包含引擎版本、书籍指纹、章节 href、实测宽高、密度、字号、行高、页边距、字体和系统字体缩放。应用 cacheDir 最多保留 24 个布局索引，部分结果每 4 章写回；不把未知章节估作一页。目录和全书滑条通过真实页数前缀映射章节/章内页，松手一次跳转。
- 工具栏显隐采用 Compose 短淡入/滑动，阅读区域保留固定的顶部/底部留白，避免每次中间点击触发全书重新统计。目录和设置禁部分展开；长目录为 LazyColumn。排版滑条只在松手后持久化设置、重载与重新统计。
- 应用强调色与阅读背景独立；动态取色只在 API 31+ 调用系统 palette，仍保留纯黑/暖色正文底色，PDF 原文不反色。

### PDF

- 高级纵向路径继续由 AndroidX PdfView 处理搜索、链接、密码与原生选字；选择菜单读取公开 TextSelection，用页信息归一化其矩形，存入应用侧批注。非交互覆盖层按公开 viewport pageLocations 绘制标记。
- 横向模式为 Compose HorizontalPager，复用高级 PdfDocument 的 BitmapSource/选区 API；基础系统才用互斥的 PdfRenderer。按需加载当前/邻页，单张渲染不超过约 1280×2300，不预渲染整本书。双指缩放与已放大平移优先，长按拖动选区优先于分页。
- 两个模式在 FrameLayout 中共享阅读区域。高级查看器在横向时 INVISIBLE 而非 GONE，保留实际测量：beta 引擎在零尺寸视口下可能发起非法位图请求。只有当前模式保存进度，切换以实际当前页同步，避免隐藏查看器覆盖横向页码。纵向页码仍沿用高级引擎的首个可见页；多页同时可见时可能与中心页/快速滚动指示不同，尚未改为主要可见页定位。
- PDF 侧载 locator 为页码 + 0..1 的矩形。无文字层或基础系统不支持文字提取时明确保存区域标记，不宣称 OCR。选区源关闭与最终进度使用仓库生命周期任务；无后台循环。尚不将标记写回原 PDF、不支持导出批注。

0.3.0 长章节校正：CSS 多栏保留测量换算得到的小数宽高，不再把整页列宽强行取整；initial-scale=1 下 CSS 像素仍跟随设备密度，而 viewport meta 只是整数布局声明。锚点页定位使用实际 body 列步长，不以取整的 innerWidth 代替，避免长章节累计横向误差。


## 0.3.1 沉浸阅读、选区菜单与配色修正

- 用户参考图只作为布局与交互数据，不导入截图中的私有书籍/正文，不实现听书或 AI 按钮。TXT/EPUB 正文填满已测量的系统安全区域；Dock 和返回为覆盖层，初始隐藏，不再固定占用 64dp/100dp。展开进度/纸张/排版时也不改变分页尺寸。PDF 同样移除常驻标题高度，保留系统能力检查。
- 原生选字保留拖动柄，清空系统操作条，选区原文/矩形由内置脚本只读返回；Compose 非聚焦 Popup 定位到选区旁，不通过脚本桥。拖动时按用户输入有界更新矩形，不设置后台轮询。复制只在明确点击时写入所选片段；书籍脚本及事件仍由清理和 CSP 拦截。
- 标记绘制采用区间事件扫描，分割为不重叠片段，每片段只用一层背景；颜色/边线按最新更新时间选择。PDF 高亮在单层离屏画布使用一次透明合成，底层以不透明色覆盖，重复矩形不会累积 alpha；下划线/笔记单独画线。
- Room v4 增加 color/anchorKey/updatedAt，以及非唯一的查询索引。MIGRATION_3_4 给旧标记补默认色与稳定区间键，保留全部旧 ID、引用位置和笔记，不静默合并或删除冲突笔记。事务 upsert 防止新增相同书籍/选段/类型的重复记录；旧重复记录可由用户直接取消。附带笔记的高亮取消后转换为笔记，避免取消视觉样式顺便丢失文字。
- 主题根因是仅复制动态方案少数 primary/secondary 字段，其他容器仍固定蓝色，且继续阅读卡片硬编码绿色。改用完整 dynamicLightColorScheme/dynamicDarkColorScheme；静态主题生成全套不透明表面与容器角色，移除硬编码卡片颜色。阅读纸张/文字是有意的独立覆盖，不将应用主题色当阅读背景。
- 按官方 Material 3 Compose 文档与当前固定依赖源码核实：动态取色要求 Android 12+，由系统壁纸色决定；开启时优先于自定义种子色，旧系统回退静态色。设置页显示实际渲染 primary 的十六进制预览，区分“颜色值已存储”与“界面已应用”。依据：developer.android.com/develop/ui/compose/designsystems/material3；固定 1.5.0-alpha01 的 MaterialTheme.kt 和 DynamicTonalPalette.android.kt，诊断副本在 .research，不作为运行时依赖。


## 0.3.2 首屏优先与渐进分页

### 选择同一排版引擎，而非独立 StaticLayout 计数

- 当前 TXT 导入会生成 HTML，EPUB 保留图片、样式和本地链接，二者实际由 Chromium 多栏排版。另用 StaticLayout 只统计纯文字会与实际页面不一致，不能标为精确全书页数。本轮不引入未使用的 FastPaginator、不替换渲染引擎；TXT 原生显示/分页的一致性与性能对照留作后续评估。
- 前台在实测布局完成后加载。通过资源状态（图片完成、字体 loaded）、连续渲染帧的滚动范围/高度一致，以及 VisualStateCallback 提交确认恢复，不再要求固定的六轮 50/60ms 最低等待。稳定检查限时 8 秒；统计单章加载限时 10 秒。失败明确提示，不用超时兜底发布“精确”页数。
- 初始恢复、文字批注和位置跳转完成并视觉提交后，才回报 onReady。此时复用当前章节实测页数，不让隐藏 WebView 与首屏同时初始化、重复解析同章。缺失工作至少让出一个绘制帧；完整缓存命中不创建第二个 WebView，完成或失败后释放。

### 分页状态与取消

- PaginationCoordinator 的 StateFlow 只保存实测 BookPageIndex 和错误，未知章节为 null。全部章节已测量才公开绝对总页数及全书滑条映射；统计过程中显示真实的本章页码和已测章节数，不用字符密度估算伪装精准页数。
- 按当前章→后邻→前邻→向外扩展的顺序测量缺失章节。结构化 LaunchedEffect 持有任务，章节/布局/重试变化或离开阅读页取消旧工作；WebView 调用及取消清理显式使用 Dispatchers.Main.immediate，文件和缓存操作使用后台 IO。没有应用级全局分页循环。
- 每个已测章节独立落盘；中断后复用已持久化部分，当前前台结果为准。若前台与同键缓存页数不同，丢弃该布局的旧索引并重新测量，而不是继续显示一个已经失效的精确总数。超时被转换为可重试的统计错误，真正的协程取消继续传播。

### 布局指纹与缓存

- LayoutConfig 的 SHA-256 输入包含引擎版本、原书内容指纹、章节 href 顺序、实测原生宽高、设备密度、系统字体缩放、字号、行高、边距、衬线选择、WebView 包及版本、系统构建版本、语言。输入用长度前缀编码，避免 href 分隔符碰撞。主题色、批注颜色不影响页数，不进入键。
- PageIndexCache 使用 cacheDir/page-indices-v2 的定长二进制文件，仅保存页数，零表示未知；无正文、笔记或无实际消费者的页偏移数组。写入临时文件后同文件系统原子替换，读取校验 magic、章节数、长度、页数范围和总数溢出；损坏视作未命中。单书保留最近五种布局、全局最多 24 份，单份最多 10000 章。超过缓存限额只跳过持久化，不禁止分页。缓存 IO 失败不影响正文与内存中统计结果。
- 缓存是可丢弃派生数据，不修改 Room v4、不新增迁移，也不改原书、批注或进度定位语义。旧 page-indices JSON 不再读取，留待系统清理应用缓存。

### 重排文字锚点与 Chromium 视口

- HTML 中由原生添加不可由书籍伪造的加载代次标记，稳定检查同时校验该标记，避免同 URL 重排时旧 onPageFinished 被误当作新文档。连续快速修改排版时复用尚未完成恢复的原始文字锚点/回退进度，取消旧加载，不读取半排版 DOM。
- 在旧 DOM 的原生翻页已视觉提交后捕获 viewportAnchor；再设置新模式/textZoom、清零新文档初始原生滚动位置并加载。避免 scrollTo 后立即 evaluateJavascript 读到上一页 DOM 视口，以及继承旧原生 scrollX 导致锚点重复偏移。
- 使用现有 UTF-16 偏移+原文+前后文定位，不新增与 Chromium 不一致的 StaticLayout 字符范围。找到锚点时只应用文字位置，不先应用相对进度，避免先原生滚动再 DOM 滚动造成竞态；定位失败才回退旧 fraction。最终视觉提交之前不回报进度或发布前台页数。
- 普通阅读进度的跨进程持久化仍是原有章节/fraction；本轮改进的是会话内重排/模式切换恢复，不宣传为通用 EPUB CFI 或所有复杂版式完全兼容。


## 0.3.3 PDF 点击热修复

- 固定版本 AndroidX PdfViewerFragment 在 setupPdfView 中安装单击 GestureDetector，并在 onPdfViewCreated 后允许宿主覆盖监听。旧批注 OnTouchListener 覆盖该监听，却仍以 onRequestImmersiveMode 作为切换底栏的唯一入口；该回调还受滚动位置影响，不代表每一次单击。
- 高级纵向阅读在现有 PdfView 监听里合并应用 GestureDetector，仅 onSingleTapConfirmed 显隐 ReadX 底栏，不依赖页码或滚动位置。触摸流不消费，原生滚动、缩放、双击和长按选区仍接收事件；已有标记点击仍优先编辑。AndroidX 的滚动驱动沉浸请求不再反向切换应用底栏。
- 横向单页（高级与基础回退共用）以屏幕阅读区域横坐标划分三等份：左/右调用 Pager 翻页，中间显隐底栏或编辑已有标记；到书首/书尾不越界，正在滚动时不排队启动点击翻页。原有滑动、双指缩放和长按批注路径不变。
- 本轮按用户要求只构建 APK，不运行单元/设备测试、Lint 或安装启动验收；手势实际表现待用户验证。数据库、源文件、隐私边界及依赖不变。


## 0.4.0 离线 PDF → EPUB

### 用户数据与派生文件

- 默认关闭的全局 `pdfToEpubEnabled` 在下一次打开 PDF 时路由到转换任务；模型语言配置独立持久化。不开启时不改原有 PdfActivity。生成成功后使用新 UUID EPUB 书籍；原 PDF 的页码、进度、批注和副本不变。
- Room v5 增加 `pdf_conversions`，有源指纹/配置指纹唯一键、源/结果书籍的 nullable 外键（SET NULL），保存状态、页数、原图页数、配置和 runId。显式 `MIGRATION_4_5` 保留书库/章节/书签/批注；历史 schema 保留。删除来源先取消/等待该来源资源释放，再只删除原副本；转换版不级联删除。删除转换版使结果关联为空，下次按需重建。
- 检查点存 `files/pdf-conversions/<sha256>/page-<n>.json` 和真实页图，不塞入 SQLite。每页有限额、临时文件原子替换，只读完整检查点；部分转换失败不新增半本书。最终 EPUB 经既有 BookParser 验证并复用导入事务，同时写入书籍/章节/来源关联，runId+IMPORTING 校验阻止取消/旧任务发布。取消保留检查点供继续；删除源文件清理本任务目录；完成后清理中间文件。

### 识别与资源界限

- PdfBox-Android 2.0.27.0 提取原始文字、TextPosition 和 PDF 目录。中文字形兼容部首局部 NFKC 规范化（不全局规范化数学上标）；单栏/基本双栏几何排序和断词连接，不执行书籍脚本。
- 每页独立决定是否 OCR。Tesseract4Android Standard 4.9.0 使用本地 LSTM 与 hOCR 位置输出；一个任务一次一页，不以全书截图/整书内存识别。模型不可联网获取；64 MiB 受限复制、SHA-256 版本目录、Tesseract 初始化成功才更新活动指针。运行任务引用固定哈希，替换模型不覆盖正在使用的数据；切换模型的续算仅移用未经 OCR 的已完成页，防止混合识别版本。
- 图像/矢量复杂内容、非横排、公式/小单元格或阅读顺序歧义采用保守整页原图。它不是智能版面语义检测，扫描图中的插图/公式仍可能漏判；提供原版回看。无可重排正文则失败，不把纯图片包装成转换成功。
- 源书 256 MB，PDF 文档解析采用 8 MiB 内存/256 MiB 临时存储设置；单页最多 200000 字形；一次位图最长边 2400 px、最多 400 万像素。转换暂存及 EPUB 解压资源累计 160 MiB，单资源 24 MiB、单章 8 MiB、总资源数最多 10000；超限明确失败。首版不处理密码 PDF，不存密码。

### 后台生命周期

- WorkManager 2.11.2 按来源/配置唯一排队，应用内全局 Mutex 串行转换、来源 lease 保护文件生命周期。Worker 从开始即设置 foreground 通知；新增 POST_NOTIFICATIONS、FOREGROUND_SERVICE/DATA_SYNC，WorkManager 声明的 WAKE_LOCK/RECEIVE_BOOT_COMPLETED 用于调度。仍删除 INTERNET 和 ACCESS_NETWORK_STATE，无服务器或云模型。
- 阶段为 QUEUED/EXTRACTING/OCR/WAITING_MODEL/PACKAGING/IMPORTING/COMPLETE/CANCELLED/FAILED。进度只报告已完成原文页数，打包不是伪造预计百分比。通知不放书名、正文或笔记；拒绝通知授权仍可在应用内观察状态。Android 系统任务配额仍可能中断，不能承诺永久后台运行。
- CancellationException 继续传播，检查点在恢复后重用；native OCR 正在识别当前页时取消可能需等该页调用结束，随后不再发布结果。runId 防止老任务的状态/事务覆盖新一轮任务；取消阶段更新在 DAO 条件中不可反向覆盖。

### EPUB 输出与原页映射

- `EpubOutput` Kotlin 适配 epub-generator 的 mimetype-first ZIP、container/OPF/nav/spine 模板；XHTML 采用 XML 序列化。优先真实 PDF 目录，其次基础标题识别，最后按 24 原页/250000 字符拆章。文字跨页连接保留源页 span，英文跨行断词不引入重复字；封面为真实 PDF 首页图。
- EPUB 正文/图片完全自包含，不要求 ReadX 才能阅读。`data-source-page` 是数据，仅应用受控脚本只读当前可见位置；来源映射通过 Room 关联开放到原 PDF 页，不在书内拼接脚本或接受任意 file/content URL。通用阅读器能读正文但不会自动拥有 ReadX 原文件关联。
- 转换版内的进度面板通过 ViewModel/Repository 导出至 SAF，后台复制有取消检查。来源删除后关联流更新，禁用原 PDF 入口。既有 EPUB 搜索、排版分页缓存、UTF-16 标记直接复用，PDF 页坐标批注不迁移。
- 开源许可及精确复用范围见 `docs/THIRD_PARTY.md`。不宣传这是 pdf-craft 全模型移植、通用 PDF 保真重排或高准确 OCR 引擎。


## 0.5.0 共享设计系统

- 新增 `ui/DesignSystem.kt` 作为间距、形状和应用字阶入口，通过 `MaterialExpressiveTheme` 注入，书架/批注/设置和阅读覆盖控件复用语义表面。正文 WebView 排版仍由 ReaderSettings/实际测量控制，不把 Compose 应用字体替换成正文设置。
- 主题种子通过已有 Material Views 依赖的公开 `MaterialColors.getColorRoles` 生成 HCT 色调配对；不使用其受限 utilities API，也不新增依赖。完整动态颜色保留为另一条分支；阅读纸张覆盖与品牌主题隔离。颜色预览展示实际强调色，用户输入仅作为种子。
- 根据 BoxWithConstraints 的实际可用宽度在 600dp 选择 NavigationBar/NavigationRail，内容最大 840dp。未引入新导航框架或更换 ViewModel/Repository，不影响数据库 UUID、Room schema、PDF 引擎能力检查和私有文件。
- 书架列表基于当前页面决定筛选：筛选仅应用于书库，不让用户不可见的书库筛选影响首页。继续阅读进度仍采用现有 progress()，文本加“约”说明其估算语义。
- 阅读/PDF 操作栏只改覆盖层形状和选中态，不修改容器尺寸/安全区和手势路径。设计与限制详见 `DESIGN.md`。


### 系统手势条与退出快照

- 紧凑书架的外层只处理顶部/横向 safeDrawing 和 IME；NavigationBar 自己消费底部 navigationBars，并将表面背景画到手势条下。宽屏和正文仍保留原 safeDrawing 区域，不重复加底部距离。
- `SystemNavigationProtection` 只在系统手势带画同色背景，不添加 padding；正文工具栏展开时通过回调选择 surfaceContainerHigh，隐藏时选择纸张色。因此显隐不改变 WebView 高度，也不触发分页。
- PDF 的原生根布局继续唯一负责 systemBars/cutout/IME padding。根布局 dispatchDraw 只补画已保留的 navigationBars 底色；Compose 不再次消费 PDF 系统边距。旧系统导航栏颜色由同一显示层同步，Theme 仅管理系统栏明暗图标。
- 设备回归确认程序关闭阅读后，销毁中的 WebView 可返回已偏移的 scrollX（目标第3页，后续旧回调曾覆盖为第2页）。`LibraryViewModel.close` 在移除 session 前持久化最后一次有效进度；关闭后不再接受旧 view 的 final 覆盖。UI 退出先结束翻页动画并 report；仍保留活动 session 的生命周期最终落盘，数据库与位置语义不变。


## 0.6.0 原生 TXT / 文字锚点 / PDF 裁边

### 双引擎与兼容坐标

- EPUB 继续走受控 WebView；TXT 的 `Book.textEngine` 可空，空值语义是 NATIVE，不批量改旧行。切换通过 ViewModel 在卸载旧视图前捕获文字锚点，不传另一引擎的页码。`ReaderLocation` 保留章节、TextAnchor 和兼容 fraction；数据库只存可验证定位数据，不存 WebView/StaticLayout/PdfDocument 句柄。
- `NativeTextSource` 流式读取**现有应用生成的 TXT HTML**，UTF-16BE 派生文件保存 canonical body 文本（包含 HTML 序列化缩进）及 native display 文本（块间两换行）；Run 表记录二者的对应关系，二分映射。数据库正文含不同标题/空行，绝不能当作旧 DOM 批注坐标。派生索引不是对任意 EPUB HTML 的通用解析器；EPUB 不走该索引。
- TXT 索引、读取、验证在 IO 调度器，StaticLayout 在单并行度 Default 调度器，每次窗口由实际宽高/字阶约束在 1024–8192 UTF-16 单位；Canvas 只绘制当前窗口、滚动时的一个邻窗。源内容不被修改，字符代理对端点安全校验。
- 前台用户输入让后台测量退让；全书只有一个 Compose 会话拥有的可取消任务，逐页测量、分批原子保存真实边界，逐章保存部分统计。布局缓存键包括源指纹、章节、引擎版本、视口、density/fontScale、字号/字体/行高/页边距、系统/locale，不包括纸张或标记色。TXT 中部无缓存恢复可先显示锚点附近文字窗口，未知全书总数/页码不能精确化。
- 旧 DOM 位置首先验证偏移+引用+上下文；不符时分块搜索唯一候选，歧义不猜测。原生选段转换为 canonical UTF-16 后存储，因此 WebView 可继续识别。书籍中原有 h1/p/href 和章节编号不变。
- `Book.readingAnchor`、`Bookmark.textAnchor` 可空，Room `MIGRATION_5_6` 仅追加列；旧 chapter/fraction 完全保留。标记 upsert 和历史重复笔记策略不变。阅读页单独订阅当前 bookId，书架的全库批注订阅在文本阅读时暂停。
- WebView 脚本缓存文本节点/合并文字，DOM 标记范围变更后失效；仅颜色/笔记元数据变化时原位 restyle，不 normalize/替换 text nodes；无标记的初始加载不调用全章 marks/normalize。触摸不在每次 DOWN 重扫全文；视口变更在视觉提交后捕获。隐藏统计渲染器在触摸时取消未完成工作，空闲 600ms 才恢复。原生脚本入口仍有 URI/加载代次检查、CSP 和 JSON 参数编码，无 JS 桥与网络。

### PDF 裁边与坐标

- `PdfCropConfig` 是 v6 的按书 JSON 配置：开启/自动、全书规则、奇偶规则、当前页覆盖；零起始 page 的奇偶判定使用用户显示的 page+1。关闭规则不删原批注。规则优先级 PAGE > ODD/EVEN > ALL > AUTO。
- 自动检测用至多 384px 的预览，边缘 RGB 中位数、边缘一致性/亮度检查、横纵内容投影与 2.5%/至少4px 安全边距。保留小页码/脚注/边注投影；空白、暗背景或不可靠检测回退 FULL。不是 OCR、语义页码检测或智能重排。
- 开启裁边后使用应用 Canvas + HorizontalPager/LazyColumn。高级系统使用既有 PdfDocument 的公开渲染、selection、searchDocument、getPageLinks；基本系统只用 PdfRenderer 的渲染和区域坐标。关闭后继续原高级纵向 fragment。没有反射修改 AndroidX 布局或绕过系统能力条件。
- `PdfCoordinateTransform` 同时描述裁边、适配缩放、缩放倍率、平移、居中；绘制、触摸、链接、搜索矩形和 sidecar 批注统一逆变换/正变换。批注保存原页归一化坐标；阅读进度仍为原页+原页 y fraction，不产生裁边页数。原页内不可见位置在手动裁掉区域外时钳制到可见区域，而不是伪造已读进度。
- 每个会话拥有按 `Bitmap.allocationByteCount` 计费的 32 MiB LRU，不回收仍被可见 Canvas 引用的位图；缓存上限不等于总 PSS。当前视口按需渲染与自动检测任务在滚走/改配置/退出时取消，不预渲染全书。PDF BitmapSource 总在 finally 关闭，基础 PdfRenderer 由仓库有限生命周期关闭任务释放。
- 选区菜单复用复制、标记色、高亮/划线、笔记编辑/取消语义；新 PDF 矩形端点在原页空间维护，变换后拖动。扫描件无文字层时明确区域选择。旋转原 PDF 页的四边形、批注导出、智能重排不在本轮范围。

参考研究范围和许可边界见 THIRD_PARTY；实现与验证边界见 TESTING，不以论文/参考引擎机制冒充已验收功能。


## 0.6.1 PDF 单页分页居中（2026-10-05）

- 单页分页在实际可用阅读区域以适配后的页面宽、高计算 left/top 居中偏移，原版横向查看器、裁边横向查看器及基础单页回退一致。连续纵向列表不增加每页上下屏幕空白，高级纵向查看器保持既有布局。
- `PdfCoordinateTransform.top` 是可选视图偏移，放在既有参数之后，默认 0；正变换先加入 top 再缩放/平移，逆变换先去平移/缩放再减去 top。原页归一化定位/Room schema 不变。
- 非裁边横向绘制、批注和触摸反算同步增加 top；裁边同步修改位图起点、裁剪矩形、批注/搜索绘制、选区端点及菜单边界。
- 本次用户明确要求不测试：仅构建 Preview，不运行 JVM/设备测试或安装启动验收。


## 0.7.0 打磨与隐私边界（2026-10-05）

- 书库合并主页，最近在读可折叠；网格按懒加载行组织，窄屏2列/宽屏3列，与列表复用真实封面和书籍操作。文件大小由 Repository IO 调度器读取应用私有源副本，排序不引入 Room 新字段/扫描整本内容。长按只编辑元数据与自定义封面，封面限16 MiB、采样至1024边长后存为独立JPEG；默认封面和原书不修改。
- 按书批注导出以 DAO 当前记录快照分章/时间排序，包含旧重复笔记。Markdown 转义标记/HTML字符，TXT 保留原文；SAF 写入在IO任务中进行，复制限10万UTF-16单位，避免大Binder载荷，过大明确切换文件导出。UUID、DOM定位与PDF原页矩形不改变，无schema迁移。
- 阅读页下栏共享 ReadingProgressControl；仅真实页总数可输入/拖动，未完成统计禁用精确页码并解释。原生引擎跨章使用真实页边界请求，不把全书页码换算为DB正文偏移；PDF滑条仍为原文页体系。
- 选区工具栏是 Compose Material3 anchored bubble（不声称使用不存在的原生接口），短操作行、按需颜色、词/句与同章跨页。原生使用有界窗口+BreakIterator，WebView 通过受控Intl.Segmenter/回退维护 Range；吸附保护代理对和16384单位限制。批注跳转短淡入/临时闪烁，闪烁仅绘制不落库、不重新分页。
- 裁边底部预览编辑器使用8个可见手柄，raw边框累积拖动，显示边框磁吸到原页/预览检测边缘，避免每事件吸附导致手柄无法离开。范围、启用、自动配置在草稿中，取消全部丢弃；确认才保存。原页矩形仍由PdfCoordinateTransform统一变换。
- 字体来源只接受本地 SAF TTF/OTF，检查sfnt表目录/范围、大小、系统加载有效性。复制到私有 reader-fonts/<sha256>.font；缓存布局含fontId，排版变更先保存文字锚点。WebView字体仅通过同源受控/reader-fonts/<hash>.font读取；仍blockNetworkLoads/CSP/无file/content访问。字体名只展示不插入CSS，字体二进制不进Git，最多12个以限制资源。
- 转换继续任务仍检查各页原子检查点，已完成页不重扫；失败诊断记录原文页/处理阶段及有限原因类别，不记录正文或原始敏感异常栈。模型变化使旧OCR结果失效时不宣传为全量无损复用。
- 在线模型为唯一新增网络入口：默认onlineModels=false，官方固定提交/三语言清单，HTTPS、拒绝重定向、大小/SHA256双校验及本地Tesseract初始化。WorkManager有限任务、真实字节进度、失败提示；不依赖系统已验证互联网标志，以免阻塞仍能访问固定模型地址的部分连通网络，离线请求明确失败且不自动循环重试；关闭/重置使generation失效并取消任务，下载与激活都检查当前代次，未完成临时文件清理。最迟阻塞读取超时后释放连接；已安装模型不删除。读取/识别始终本地，无上传API。
- 2026-10-05 用户已确认此可关闭隐私边界变化；加入 INTERNET/ACCESS_NETWORK_STATE，其他联网增强依然需独立授权。在线清单与内置清单一致，普通版只打包清单，不打包模型。

- 本轮输入法只影响弹层/输入控件，文本正文背景层排除临时IME Insets，避免精确页码输入后回到另一套分页。空白选区吸附到附近词边界而不折叠；裁边手柄仅对自身触摸区设置系统返回手势排除，其余区域仍可返回。
