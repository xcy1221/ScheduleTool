# Server Panel 界面规范

遵循 [服务器 README 的 Server Panel 规范](https://192.168.1.71/server-readme/README.md)。登录、日历与 TODO 页面均使用同一顶部栏：

`HOMESERVER · ScheduleTool　←返回server panel　外观与排版`

- 顶层返回 `/server-readme/`；页面内标题和操作保留在内容区，避免重复设置入口。
- 明暗和五种色调继续使用 `scheduletool-theme`、`scheduletool-tone`，支持同源同步。
- 工作区自适应占满可用空间，保留原有断点；移除内容宽度选项并忽略旧 width 偏好，不按预览窗口固定内容宽高。
- 密度和周／日刻度独立保存于 `scheduletool-layout-v1`；恢复默认仅重置这些布局选项。
- 刻度仅控制周／日视图显示，不改变事件时间、时区、重复规则或审计数据。
- 窄屏保留顶部三项，设置支持键盘、Escape 和焦点返回。

发布前检查前端构建、登录与日历／TODO、15／30／60 分钟刻度切换、设置保存、恢复默认、返回和窄屏布局。改动遵守 PR 人工审核与合并流程，审核前不部署。

### 页边距与细调

遵循 [Server Panel 规范](https://192.168.1.71/server-readme/README.md)。本页支持左右留白 0–20%、上下留白 0–120 px、密度 60–150%；有卡片网格时另设列数和间距（0–64 px）。保持内容流式宽度、顶部栏与侧栏布局。设置按页面写入 `serverpanel-page-layout-v1:<页面标识>`，首次沿用旧密度和列数，恢复默认只重置当前页面；明暗与色调继续共享。

日历与 TODO 共用本工具布局，日历保留七天结构；留白调整后重新计算日历尺寸。新改动须经 PR 审核并手动合并后部署。
