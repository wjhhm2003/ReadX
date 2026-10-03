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
