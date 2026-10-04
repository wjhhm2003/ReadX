# ReadX 界面设计语言（0.5.0）

## 方向与边界

沿用个人阅读器的安静、中文优先界面，不做营销式首页。使用本地 material-3 skill 的 Compose-first、语义色、色调层次、8dp 节奏、形状层级与自适应导航原则；不套用其 Web 样例，也不新增字体、图标或大型依赖。

保留 ReadX 标题、真实 EPUB 封面/PDF 首页、继续阅读横向卡片、圆角列表及「首页 / 书库 / 批注 / 设置」。导入仍在顶栏和菜单，空书库另有可用导入按钮，不增加遮挡正文/导航的 FAB，不加入宣传口号、隐私徽章或伪造阅读统计。

## 共享 tokens

实现入口：`app/src/main/java/io/readx/app/ui/DesignSystem.kt` 与 `Theme.kt`。

| 类别 | 约定 |
| --- | --- |
| 间距 | 24dp 页面边距；16dp 分组间距；8dp 紧凑间距 |
| 形状 | 4 / 8 / 16 / 24 / 32dp；阅读操作栏仅顶部 28dp 圆角 |
| 字阶 | 系统 Sans 与中文回退；headline 标题、title 卡片标题、body 正文描述、label 元信息；正文设置不使用应用字阶覆盖 |
| 表面 | surface 背景；surfaceContainerLow 卡片/设置分组；surfaceContainer 导航；surfaceContainerHigh 操作面板 |
| 强调 | primaryContainer 继续阅读和阅读设置入口；secondaryContainer 导航选中/明确格式占位封面 |
| 宽度 | 实际可用宽度 >=600dp 切换侧栏；内容上限 840dp，居中显示 |

不依赖仅更改 primary 的默认紫色方案。固定/自定义颜色采用现有 Material Views 库的公开 `MaterialColors.getColorRoles` API，生成 HCT accent / onAccent / accentContainer / onAccentContainer 配对；secondary 使用低饱和种子，tertiary 使用与主色协调后的暖色种子。中性表面使用受控低饱和染色。**RGB 输入是种子，并不承诺显示的强调色与输入十六进制相同**；预览显示实际强调色。没有调用受限的 color.utilities API。

Android 12+ 动态取色开启时采用完整系统 ColorScheme；关闭和旧系统使用本地方案。用户已有设置不迁移、不清空。阅读纸张、墨色是独立覆盖，夜间/纯黑/暖色/浅绿继续保留。辅助文字与各强调色配对有 JVM 对比度回归。

## 布局与交互

- 导航使用原生 NavigationBar / NavigationRail 选中态和组件交互；不再将 indicatorColor 设为透明。
- ContinueCard 增加真实进度轨道，文本书使用章节等权估算并标“约”；不伪造总页数。BookCard 不锁定行高，标题最多两行，作者一行。
- 真实封面保持 Fit；无封面时明确显示格式占位，不使用看似出版封面的生成图。
- 空状态提供图标、简短说明；仅空书库提供真实导入操作。批注编辑/删除仍连接原有记录。
- 设置采用可滚动的 tonal 分组；阅读方式采用单选分段按钮，格式/背景选择可换行；字体等滑条仍松手后提交。动态取色开关、模型 SAF 导入和转换任务保持原有逻辑。
- Reader 与 PDF 采用顶部圆角的覆盖式操作栏，当前工具有 tonal 选中态，48dp 级别原生交互目标不缩小。默认控件隐藏，无额外正文留白；显隐不改变分页宽高。继续保留三分屏点击、长按选区和链接优先级。
- 紧凑书架由底部 NavigationBar 唯一消费 navigationBars，外层只处理顶部/横向与 IME，背景一直延伸至小白条；Snackbar 避开导航高度及系统手势带。
- 阅读保留原有 safeDrawing 正文尺寸，额外保护层只补画小白条底色，随工具栏显隐切换纸张/面板色，不重新分页。PDF 仍由原生根布局唯一处理 systemBars/cutout/IME，并补画系统手势带；不让 Compose 再加边距。
- 输入法由外层统一避让，书库导航可显示在键盘上方，键盘自身管理其系统手势带；弹层采用组件自身的系统安全区，不重复传入 Activity 底部 padding。
- 使用现有 Expressive 组件动效；不添加持续动画、后台循环或为了卡片高度对正文触发排版。

## 验收与尚未覆盖

实际构建、设备截图和回归结果记录在 `TESTING.md`。`screenshots/md3-overview.png` 是真实运行拼图，`md3-gesture-compare.png` 对比同轮修正前后的手势区域，均不是设计稿。截图为自生成验收样书；封面来自样书内真实资源，并非用户书籍或正式封面。

AndroidX PDF 内部的原生搜索界面仍由其依赖控制，XML 基础主题对齐默认蓝色，不承诺全部原生 PDF 控件实时同步自定义 Compose 色板。

本轮实现紧凑/宽屏两个布局，不声称已经完成折叠屏铰链、XR、多窗口全部尺寸类、完整 TalkBack、2倍以上字体缩放或所有复杂 EPUB/PDF 兼容。Expressive 仍来自项目固定的实验版 Material 3 依赖，没有升级依赖以追新。
