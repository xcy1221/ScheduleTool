---
schema_version: 2
project_id: "566606cd-b710-3574-86cd-7246d450b4e3"
maintenance_status: maintained
last_reviewed: "2026-11-14"
---

## 维护状态记录

仍在维护日程、TODO、MCP 和服务器面板集成。

2026-11-14：独立 Vue 前端、仅 API 后端与 `/schedule/health` 已上线，程序源 `984236f`，前端版本 `20261114-vue1`；生产认证/界面、真实 MCP 状态与前端回滚再发布通过。既有 Tunnel 构建缺少源制品的问题已通过保留原镜像的健康探针薄层解决，见 [部署记录](docs/deployment.md#7-部署记录)。

2026-11-14：PR #8 的内网免登录已部署并开启，当次前后端构建提交为 `4392179`；PR #7 已合并，仅同步既有 ISP 出站模板与说明。验证结果见 [部署记录](docs/deployment.md#7-部署记录)。

2026-11-13：更新 MCP Tunnel 的独立 ISP 出站模板；运行切换与验收状态见 [部署记录](docs/deployment.md#7-部署记录)。

当前仓库版本见 [VERSION](VERSION)。维护状态独立于功能完成度和实际部署状态。
