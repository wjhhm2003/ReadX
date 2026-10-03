# ReadX 验证记录

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
