---
status: accepted
---

# Java 8 训练平台配合 Python BlueSky 适配器

训练平台后端采用 Java 8 和 Spring Boot 2.7.x，教员及模拟飞行员前端采用 Vue 3 + TypeScript；BlueSky 侧保留 Python `training_adapter` 插件。Java 平台不嵌入 Python 解释器、不直接访问 `bs.traf` 等内部对象，也不重写 BlueSky 算法，所有交互通过稳定的跨进程适配协议完成。

Java 8 无法采用需要 Java 17 的 Spring Boot 3 和现代 Spring Modulith 基线，因此首版采用单个 Maven/Spring Boot 工程，通过严格包边界、公开应用接口、进程内领域事件以及 ArchUnit 架构测试实现模块化单体；首版不提前拆分 Maven 子模块。Vue 3 源码位于独立 `frontend` 目录，统一构建将生产构建结果放入 Spring Boot 最终包的静态资源。Java 8 与 Spring Boot 2.7 已属于旧技术线，必须锁定依赖、生成软件物料清单、持续进行漏洞扫描，并保持面向接口和无 Java 8 特有协议的设计，为将来升级 Java LTS 留出迁移路径。

## 后果

- Java DTO 与 Python 消息模型必须由同一接口契约生成或进行双向契约测试，防止字段和单位漂移
- Java 平台负责训练业务事务、权限、指令状态机、记录和评估；Python 适配器只负责 BlueSky 命令、状态与生命周期转换
- REST/SSE 智能机长接口由 Java 平台提供，不由单个 BlueSky 实例暴露
- 前端只调用 Java 平台 API，不直接连接 BlueSky 或 Python 适配器
- 正式运行只启动 Spring Boot 托管前端静态资源；Vite 仅用于开发和构建，生成的 `dist` 不作为手工维护源码
- 项目依赖治理必须把 Java 8 和 Spring Boot 2.7 的支持周期与安全补丁可得性列为持续风险
