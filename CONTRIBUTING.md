# 贡献指南

ReadX 当前是中文优先的个人阅读器开发预览。提交问题前先查看 README 的已知边界、开发路线和测试记录。

## 报告问题

提供应用版本、Android/API 版本、PDF/EPUB/TXT 类型、可复现步骤、预期/实际结果。优先上传自生成或允许公开分发的最小样书；**不要公开整本私人书籍、笔记、密钥或敏感日志**。截图先检查书名/批注等私人信息。

## 代码贡献

- 阅读 AGENTS.md 和架构/设计说明；尽量提交小范围、可测试的变更。
- 保持本地优先，无 destructive migration；Room 变更补历史迁移/schema/保留数据测试。
- 第三方新增依赖说明必要性、包体、许可和隐私边界，保留归属声明。请勿直接复制不兼容许可的代码。
- 不在主线程解析/渲染/写库，正确传播 CancellationException；不能用虚假总页数或占位内容冒充功能。
- PR 描述实际构建/测试、未运行项目和已知限制。代码/文档的新贡献按项目 MIT 许可提交；保留所引入第三方的原始许可。

## 本地验证

普通版：`./gradlew assembleDebug testDebugUnitTest lintDebug assemblePreview`。

内置模型版：先运行 `python3 scripts/prepare-ocr-models.py`，再加 `-PbundledOcr=true`。模型仅存本地，不能提交 `.traineddata` 或用户书籍。

设备用例仅在专用模拟器执行，保留 APK 参数不是数据备份；不运行 pm clear、卸载或 wipe-data 清除已有书库。R8 与设备测试分开运行。
