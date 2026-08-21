# Task Start Snapshot — 数据准备与分析工作台独立模块

- 日期：2026-08-21
- 计划：docs/aegis/plans/2026-08-21-data-prep-workbench.md（approved）
- ADR：docs/adr/0014-standalone-data-prep-workbench.md
- 分支：master（基线 commit 53d02a2）
- 变更面：仅新增 `bluesky-master/data-prep-workbench/` 目录与本任务文档；不改 training-platform/、bluesky/ 既有文件
- 工具链：JDK 17（编译目标 8，与 training-platform 现行构建一致）、Maven 3.8.7、Node 22 / npm 10、H2 2.1.214 已缓存、POI 走中央仓下载
- 验收：见计划 §1 七项清单；每任务一次提交
