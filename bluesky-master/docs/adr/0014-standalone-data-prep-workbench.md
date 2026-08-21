---
status: accepted
---

# 数据准备与分析工作台作为独立可运行工程先行交付

数据准备与分析工作台首版以 `data-prep-workbench/` 独立 Maven 工程交付：可独立编译、独立打包、独立运行，默认使用本机 H2 文件数据库（零安装），不依赖训练平台的 MySQL、ZeroMQ 仿真链路或 BlueSky Adapter。技术栈与训练平台完全一致（Java 8 / Spring Boot 2.7.18 / MyBatis / Flyway / Vue 3 + TypeScript + Vite + OpenLayers），包结构与测试约定（Mapper+Row、XxxApiTest + H2 内存库 + MockMvc）沿用 training-platform 既有模式。

该决策不违反 ADR-0011「单 Maven 工程、首版不拆分子模块」：training-platform 保持不变，新模块是仓库内的兄弟工程，为「数据准备与分析工作台」这一概要设计 §12.6 既定独立前端入口提供先行落地。与 ADR-0013「三入口同一 Vue 工程」的关系：该条描述的是平台正式部署形态；本工程是独立交付物，接入正式平台部署时，其 REST 契约与数据可迁入统一工程/统一入口，届时以新 ADR 记录迁移决策。

## 后果

- 独立工程拥有自己的 Flyway 版本线与 `workbench_state` 修订计数，与 training-platform 的 V1–V6 迁移互不影响
- 默认配置绑定 127.0.0.1:8090，无认证，遵循 ADR-0013 网络边界要求；仅受控网络内使用
- 数据模型以用户确认的数据结构规格为权威：BaseRecord 公共列、GeoJSON TEXT 存储、逻辑删除、revision 乐观锁、sourceType 预留 BLUESKY
- BLUESKY 只读数据源（OpenAP/导航资源经 Adapter）一期不接入；接入时新增同步任务与只读规则，不改动既有表结构
- code 唯一性采用服务层事务内校验，接受单用户工作台假设；多人并发需求出现时改为数据库级唯一约束
- 训练分析视图、练习计划关联、机场气象/重要天气区编辑、性能包线编辑均不在本工程一期范围
- 若后续该工作台并入 training-platform 统一部署，数据经导出/导入或 MySQL profile 直迁，独立工程可整体退役并以新 ADR 记录
