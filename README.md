# ReadX

本地优先的个人 Android 阅读器。原生 Kotlin / Jetpack Compose / Material 3 Expressive。

## 当前版本：0.2.0 开发预览

本仓库正在从空项目实现第一阶段阅读闭环，不是全部需求已完成的正式版。

### 已实现

- 系统文件选择器多文件导入 EPUB / TXT / PDF；复制到应用私有目录。
- SHA-256 内容去重；失败导入回滚；移除应用副本不会修改原文件。
- Room 本地书库、章节、阅读进度、TXT/EPUB 书签；编辑书名、作者和逗号分隔标签；书库筛选。数据库 1→2 自动迁移，保留原书籍与进度。
- TXT：UTF-8 / BOM / UTF-16 / GB18030 识别，中文和英文章节标题识别，基础目录。
- EPUB：OPF 元数据、spine 阅读顺序、EPUB 3 nav / EPUB 2 NCX 章节名称，正文、图片、章节内/章节间链接。
- EPUB / TXT：默认按屏幕分页，左右滑动/上一页/下一页；章末连续翻页会进入下一章，向前返回上一章末页。可在阅读设置切换滚动模式。字号、行距、页边距、字体与主题变化后重新排版。
- EPUB / TXT 正文搜索（逐章扫描，中文可用，最多 100 个结果），结果跳转和 WebView 查找标记。
- 阅读章节和相对滚动位置保存；启动直接进入书架，点开书籍后恢复进度。
- 满足系统条件时使用 AndroidX PDF：连续页面、缩放、文本选择、书内搜索、页码跳转、基础页码恢复。
- 较旧系统回退为 PdfRenderer：单页、前后翻页、缩放；不支持选字/搜索/密码输入。
- 首页采用参考图的 ReadX 标题、继续阅读横向卡片、浅色书库列表与四项底部导航。导入入口位于顶部加号与菜单，不再用悬浮按钮遮挡书籍。
- PDF 使用固定 56dp 紧凑工具栏；系统栏/安全区只处理一次，文档顶部对齐。横版 PDF 页面不会被放到阅读区域的垂直中间。
- 无网络权限、无账号、无广告。EPUB 页面禁 JavaScript、禁外部资源、禁 file/content 访问。

### 尚未实现 / 已知边界

- 高亮、划线、笔记、笔记导出、内置查词/翻译、联网元数据刮削、完整备份恢复尚未实现。
- PDF 目录、持久化批注、裁边、OCR、反色/重排尚未实现。PDF 文档仍保留原色，应用主题不等于 PDF 反色。
- 当前 EPUB 是安全本地 WebView 基础适配器，不是完整 Readium 阅读引擎。暂不支持 DRM、字体混淆、固定版式、音视频、弹出脚注、完整引用跳转/返回栈。
- 目录按 spine 章节呈现；同一章多个目录锚点尚未完整呈现。
- 排版变化后的滚动恢复使用相对滚动比例，不是精确文本定位；后续批注阶段必须升级为稳定文字锚点。
- 大书全文搜索尚无 FTS 索引，属于第一阶段直接扫描实现；扫描 PDF 不可全文搜索。
- 正文字号会跟随系统字体缩放，但超大字体、横屏、小屏等无障碍布局仍需完整验收。
- EPUB 内嵌位图封面与 PDF 首页缩略图已接入；无封面/无法解码的文件显示格式封面。书库百分比：PDF 使用实际页数，TXT/EPUB 使用章节等权与章内位置估算，不宣称是按全文字数加权的精确进度。
- 中文优先，暂未提取所有界面文案完成英文/多语言本地化。
- 首版限制：源文件 256 MB，TXT 32 MB，EPUB 解压合计 160 MB / 单资源 24 MB / 单章 8 MB / 10000 个资源。

## 环境

| 项目 | 路径 |
|---|---|
| Android Studio | E:\Android\AndroidStudio\android-studio |
| SDK | E:\Android\Sdk |
| AVD | E:\Android\Avd |
| Gradle 缓存 | E:\Android\Gradle |
| Studio 配置 | E:\Android\StudioUser |
| 环境脚本 | E:\Android\android-dev-env.ps1 |

- Gradle 9.3.1（Wrapper 带官方 SHA-256 校验）/ AGP 9.1.0。
- SDK 36.1；target SDK 36；最低 Android 9 / API 28。
- AndroidX PDF 高级路径要求 Android 12 / API 31 及 S 扩展 >=13，实际可用功能由系统支持决定。
- Compose BOM 2026.01.00 + Material 3 1.5.0-alpha01（公开 Expressive API，实验版），固定到与现有 SDK 兼容的版本。
- AGP 内置 Kotlin 2.2.10；Compose compiler plugin 2.2.10；KSP 2.3.12；Room 2.8.5。

在 Android Studio 中打开 E:\ReadX，使用提供的 JBR 和 SDK，等待同步。

### PowerShell 构建

~~~powershell
cd E:\ReadX
. E:\Android\android-dev-env.ps1
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
# 或使用环境加载入口：
.\scripts\build.ps1
~~~

APK：E:\ReadX\app\build\outputs\apk\debug\app-debug.apk

### 设备测试与安装

~~~powershell
. E:\Android\android-dev-env.ps1
adb devices
.\gradlew.bat connectedDebugAndroidTest
adb install -r E:\ReadX\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.readx.app/.MainActivity
~~~

设备测试应在专用模拟器运行。界面测试首次在空书库导入示例，重复运行会复用示例；导入/数据库测试使用隔离内存数据库，PDF 测试只清理自己创建的测试书籍。

### 轻量自用预览包

~~~powershell
.\gradlew.bat assemblePreview
adb install -r E:\ReadX\app\build\outputs\apk\preview\app-preview.apk
~~~

Preview 启用 R8 / 资源压缩，并使用本机调试密钥签名；Debug 包包含调试工具和未裁剪依赖，体积更大。两者的 applicationId 相同，安装会替换彼此。正式 Release 签名需另行配置；不要把密钥或密码提交到仓库。

## 结构

- app/src/main/java/io/readx/app/data：Room 模型、私有文件、导入与去重。
- app/src/main/java/io/readx/app/reader：TXT/EPUB 解析、本地 HTML 隔离阅读。
- app/src/main/java/io/readx/app/pdf：AndroidX PDF 与基础渲染回退。
- app/src/main/java/io/readx/app/ui：Expressive 主题、书库、搜索、排版设置、状态管理。
- app/src/test：纯 JVM 解析、安全、编码测试。
- app/src/androidTest：Room/导入和阅读 UI、PDF 设备测试。
- docs/ROADMAP.md：后续阶段与验收边界。

## 隐私

没有申请共享存储管理权限，也没有网络权限。导入文件、数据库和设置都在应用私有空间。当前关闭系统自动备份，因为尚未实现书库与文件一致性恢复；卸载会删除应用副本，原始书籍文件不受影响。开发版本没有额外加密数据库或文件，不应当把它当作安全加密保险库。

## 完整验收入口

~~~powershell
.\scripts\verify.ps1 -DeviceTests
~~~

验证记录见 docs/TESTING.md。设备测试与 R8 构建分开执行；发布前仍需真实书籍与真机兼容性验收。
