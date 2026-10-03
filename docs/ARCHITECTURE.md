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

EPUB 自研适配器是基础实现，不是完整 EPUB 标准引擎。引入 Readium 后，必须迁移文字锚点而非简单沿用章节像素位置。正式批注功能应等稳定定位设计完成后再加入。

## 本地 HTML 安全

- ZIP 资源写入前检查规范化路径，拒绝绝对路径、Windows 路径和越界路径。
- 对实际解压字节数、资源数量和单资源大小设限，不仅依赖 ZIP 声明大小。
- WebView 禁 JavaScript，无 JS/native bridge，禁 file/content 访问。
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

按照新的参考图要求：首页展示 ReadX 标题、最近阅读横向卡片、书籍列表和首页/书库/书签/设置底部导航。不展示宣传口号与隐私徽章。启动进入首页书架，不自动打开书籍。搜索与导入在顶栏/菜单，书库页提供筛选。

## 0.2.0 分页与 PDF 边距

TXT/EPUB 使用同一个隔离 WebView 的 CSS 多栏布局。分页大小来源于已测量的 AndroidView 可用宽高，再换算为 CSS 像素，不依赖尚未确定的 100vh 或默认视口。必须 doOnLayout 后才加载 HTML，否则初次加载会写入错误列宽，出现半页/错位。Chromium 排版稳定且完成视觉状态提交后才恢复位置、更新页码。原生按钮/手势按完整视口宽度跳转；目录、链接与搜索仍使用同一章节引擎。切换滚动布局不启用 JavaScript，也没有加入脚本桥。

PDF Activity 的原生根布局独占 systemBars/displayCutout/IME 安全区；返回 consumed insets。Compose toolbar 的 windowInsets 设为 0，原生高度固定为 56dp，书名和页码在同一个工具栏中显示。PDF 容器紧接工具栏下方，权重填满剩余区域；PdfView 与基础回退均使用顶部对齐，区分 PDF 原文留白与应用额外边距。

数据库版本 2 增加 totalUnits 与 bookmarks。MIGRATION_1_2 保留书籍、章节、标签、进度，填充旧文本书籍的章节数，并建立级联删除书签表。PDF 总页数由渲染/加载时写回，不将单页章内 fraction 冒充整本阅读百分比。


## 0.2.1 阅读交互与安全区

- 页内过渡由原生 `ValueAnimator` 驱动 WebView 横向滚动，220ms 收敛到整页；拖动跟随手指，短拖动回弹，跨章短淡出/淡入。关闭系统动画时直接落位。不使用网页 JavaScript、桥接或截图缓存。动画/手势中不回报暂态页码，也不反复保存数据库进度，只在落位后回报；释放或重排前结束过渡。
- 测量宽度换算 CSS 像素后取整数，并以相同比例换算/向下取整页高，确保 viewport meta（Chromium 按整数解析）与多栏宽度一致，避免小数密度转换在长章节累积横向偏移。多栏文末使用净高度为零的尾部伪元素补足最后一页右边距的滚动范围，避免 Chromium 将最后一页钳制到非整页位置造成边缘裁字。
- 页码弹层只控制当前章。滑条拖动只更新 Compose 目标页码，松手调用一次原生 `scrollTo`，不更换章节、不加载 HTML、不逐页渲染长距离跳转。排版/导航变化会关闭弹层，未排版时不展示虚假的总页数。
- 主界面外层 Scaffold 独占 `safeDrawing` 安全区，并消费其 padding；书架/阅读内层 Scaffold、TopAppBar 和底部导航明确使用零系统边距。系统图标随实际主题亮暗更新，关闭系统额外导航栏对比度色层。PDF 仍由原生根布局单独处理安全区，只同步根背景，不再叠加 Compose 安全边距。
- 每次阅读导航分配递增编号。WebView 的位置回调绑定原导航，旧页面释放不能覆盖新章节或同章锚点跳转。关闭阅读器仍保存最终落位。所有延迟排版/搜索回调检查加载代次与释放状态；尺寸变化也走统一排版稳定/视觉状态恢复路径，移除原来的固定延迟二次恢复。
- 同文档 `#fragment` 不经过 `shouldOverrideUrlLoading`，改由 `doUpdateVisitedHistory` 纳入受控导航；来源相对位置在触摸/键盘激活前记录，页面完成回调每个加载代次只恢复一次。点击章节内/跨章本地链接时保存来源章节与相对位置，再加载目标 fragment。首次加载只对目标整页对齐，不回写来源位置；后续排版变化按当前相对位置恢复，不重复应用旧 fragment。用户主动「回到原处」或系统返回才弹出返回栈。栈限 32 层、仅在 ViewModel 阅读会话内保留，尚未覆盖不在 spine 的注释文件或进程终止后的返回链。
- 纯黑正文与阅读背景使用 `#000000`，弹层使用稍浅的暗面区分层级。设置重置复用 `ReaderSettings()` 与原有偏好写入路径，不涉及 Room schema 或书库迁移。
