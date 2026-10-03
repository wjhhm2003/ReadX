# ReadX 验证记录

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
