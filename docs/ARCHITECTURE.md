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

MaterialExpressiveTheme 来自固定的 Material 3 1.5.0-alpha01。该 API 为公开实验接口，不能把它宣传为全部稳定组件。Compose BOM 固定 2026.01.00，以匹配当前 SDK；不会为了追新自动升级到要求 SDK 37 的依赖。

PDF Fragment 原生 View 层需要 Material 属性，所以 Activity 的 XML 主题继承 Theme.Material3.DayNight.NoActionBar；Compose 使用独立的 ReadXTheme。

Preview 构建启用 R8 与资源压缩，并以本机 Android 调试密钥签名，适合个人开发预览，不是正式发行签名。Release 构建不内置签名密钥。

## 明确不做的过早承诺

- 基础滚动位置 ≠ 精确文字锚点。
- beta PDF 查看器集成 ≠ 优秀 PDF 支持已全面验收。
- EPUB 标题解析 ≠ 自动生成完整多级目录。
- 暖色主题 ≠ 医学意义上的护眼效果。
- API 36 模拟器通过 ≠ 已验证所有 Android 9+ 设备。

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
