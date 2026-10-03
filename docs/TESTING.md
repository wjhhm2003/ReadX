# ReadX 0.2.0 验证记录

## 环境

- Pixel_6_API_36，Android API 36，S 扩展 17。
- Gradle 9.3.1 / AGP 9.1.0 / SDK 36.1。
- 验证日期：2026-10-03。

## 最终结果

- assembleDebug / assemblePreview：通过。
- testDebugUnitTest：13 项通过，0 失败。
- lintDebug：0 错误；仍有版本提示与 KTX 风格建议等非阻断警告。
- connectedDebugAndroidTest：12 项通过，0 失败，0 跳过。
- apksigner verify：通过，本机调试密钥签名，仅供个人开发预览。
- 压缩 Preview APK 已安装到模拟器；启动、书架、真实 TXT 页面、返回书架和重启均手动验证通过。

## 验收覆盖

1. 13 项 JVM：编码识别、TXT 章节与转义、EPUB 元数据/spine/nav、ZIP 安全、HTML 清理；新增默认分页/CSS 退出分页与跨章节总进度计算。
2. 4 项仓库设备测试：导入去重与进度、元数据/删除不修改原文件、失败导入回滚、长章节分块查询。
3. 1 项数据库升级：创建真实 v1 SQLite 数据库，再迁移到 v2；检查书籍、章节、标签、进度与章节数均保留。
4. 2 项分页设备测试：分别导入 100 段的 TXT 与 EPUB，断言一章分成多页、按钮翻页、真实水平手势、垂直位置不滚动、退出重开恢复页码、滚动模式、重新分页、章末自动进入下一章、前翻回上一章末、添加与展示书签。
5. 3 项 PDF：高级加载/页码跳转/重建恢复/搜索入口；基础回退；横版 PDF 的工具栏高度 <=57dp、内容紧接工具栏、文档使用超过70%的窗口空间。生成真实截图检查顶部留白。
6. 1 项阅读 UI：示例、目录、主题、搜索与结果跳转。
7. 1 项参考界面：生成带真实 PDF 首页与 EPUB 内嵌 PNG 封面的验收样书；检查实际封面、底部导航与书库筛选，保存模拟器截图。样书仅是 UI 验收数据，测试结束清理，不是用户的真实收藏。

## 修复过的实际问题

- WebView 未测量就加载 HTML，会将默认列宽/高度写入正文：已改为 doOnLayout 后加载，并使用实际可用区域的 CSS 像素。
- 100vh 在初次原生布局尚未完成时不可靠：改为测量后的固定页高，并等待 Chromium 排版稳定/视觉状态提交。
- Snackbar 覆盖底部导航：根据阅读/书架页面留出底部控件空间。
- PDF 系统栏与 Compose 标题栏重复边距：由原生根布局统一处理安全区，子工具栏 windowInsets=0，高度固定56dp。
- 横版短页面被居中：高级 PdfView 与基础图像回退均采用顶部对齐。
- 文本书库百分比只使用当前章 fraction：改为章节等权的全书估算；PDF 使用真实页数。

## 构建与设备验收

建议依次运行，避免 R8 构建与模拟器同时争用内存：

~~~powershell
cd E:\ReadX
. E:\Android\android-dev-env.ps1
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assemblePreview
.\gradlew.bat connectedDebugAndroidTest
~~~

如果只检查已下载依赖，可添加 --offline。

## 预览包

- 路径：E:\ReadX\app\build\outputs\apk\preview\app-preview.apk
- 版本：0.2.0-preview / versionCode 2。
- 大小：5055260 字节，约 4.82 MiB。
- SHA-256：`1add6747d85d1743fc370a2c6a606a44f90bb8a8b0deb6a95e966741a91fc23d`。
- Preview 与 Debug 使用同一 applicationId 和本机调试签名；升级不会主动清空书库。正式发布签名仍需独立配置。

## 截图

- E:\ReadX\docs\screenshots\ui-preview.png：书架、屏幕分页、PDF 适配实机拼图。
- bookshelf-reference.png：新的参考书架（验收样书）。
- txt-paged.png / epub-paged.png：同章多页的真实截图。
- pdf-landscape-page.png：横版 PDF 顶部对齐。
- txt-bookmark-tab.png：持久化书签列表。
- preview-reader.png：R8 压缩包实际阅读。

## 仍需验证

真实旧系统、平板/折叠屏、大字体、复杂 EPUB 版式及大量真实 PDF 的完整兼容性与长期压力测试尚未完成。当前分页位置恢复仍使用章节/相对位置，不是精确文字锚点。PDF 搜索入口测试不代表所有 PDF 的搜索质量已全面验收。
