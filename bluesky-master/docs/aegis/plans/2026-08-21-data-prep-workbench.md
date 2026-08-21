# 飞行数据准备与分析工作台 · 独立可运行模块实施计划

日期：2026-08-21
状态：approved（用户批准后执行）

## 1. 目标与成功标准

在 `bluesky-master` 内新建**可独立编译、独立运行**的「飞行数据准备与分析工作台」子模块（不改动 `training-platform/` 任何文件），UI 严格复刻已锁定的原型 HTML（亮色主题、顶部导航+下拉、主窗口纯表格、全窗口地图编辑器、页头「服务正常·修订 N」）。

成功标准（验收清单）：

1. `mvn package` 产出单个 jar（内含前端静态资源），`java -jar` 零外部依赖启动（默认端口 8090，绑定 127.0.0.1）。
2. 七个实体页（导航/机场/空域/航路/气象/机型性能/雷达与通道）列头与原型一致，开箱带种子数据（原型示例数据 PUD/AND/SASAN/TMA-01/A593/RDR-SHA-01 等）。
3. 行选中、查看/编辑、新建（数据编辑▾）、保存后页头修订号 +1 均走通。
4. 地图编辑器全屏覆盖：五图层勾选显隐、点/线/面渲染、工具切换、属性面板编辑保存。
5. Excel 模板下载/导出/导入（行级错误回显 + ImportBatch 留档）走通。
6. 业务规则：code 唯一、逻辑删除后列表不可见、revision 乐观锁冲突返回 409、CAT048 通道未绑雷达站校验失败。
7. 后端 `mvn test` 全绿（H2 内存库 + MockMvc），前端 `npm run typecheck && npm test && npm run build` 全绿。

## 2. 基线引用

- 概要设计 §12.6（数据准备与分析工作台最小边界）、§2.1 S-01：不采用不可变版本发布；逻辑雷达站字段；首版临时数据源说明。
- 概要设计 §5.1/§5.2、ADR-0011（Java8+SB2.7+单 Maven 工程）、ADR-0010（模块化单体）：技术栈对齐；新模块为兄弟独立工程，不违反 training-platform 不拆分约束。
- ADR-0013（无登录+网络边界）：不做认证，默认绑定 127.0.0.1；ADR-0013 的「三入口同一 Vue 工程」指正式部署形态，本模块是独立先行交付物，关系在 ADR-0014 记录。
- 用户消息「数据结构建议」（BaseRecord/GeoPoint/实体/枚举/一期清单）：数据模型权威规格。
- 锁定原型 HTML（`.dsh-vision-router/artifacts/ui-preview-main.html` 会话产物副本）+ 两张 1100×700 渲染截图：UI 视觉与交互基线（#087079 主色系、12px 表格等 Token）。
- CONTEXT.md 术语表：命名遵循规范术语。

原型裁决的三项差异（按原型执行）：

1. 地图编辑器=应用内全屏覆盖层（非独立浏览器窗口）。
2. 仅「空域数据」与「数据编辑」带下拉，气象/机型性能/雷达与通道为平铺按钮。
3. 行操作统一「查看·编辑」（sourceType 区分留到 BLUESKY 数据源接入后）。

对原型的一处补充：空域数据▾ 下拉增加「机场数据」页（数据模型要求 Airport/Runway，原型未演示）。

## 3. 治理记录

- TDD Route: Mode=off → Decision=skipped；姿态=每任务最小实现+事后回归测试（MockMvc/vitest）。
- Change Necessity: code-change——全新独立交付物；最小边界=新建 `data-prep-workbench/` 目录。
- Existence Check: 新表面=独立工程+ADR-0014；复用 training-platform 全部技术约定（SB2.7.18/MyBatis Mapper+Row/Flyway/H2 测试库/MockMvc ApiTest；前端 vue3+ts+vite+pinia+ol+vitest）。
- Requirement Ready Check: ready（数据模型+UI 原型+分期均已由用户确认）。

## 4. 架构与文件地图

```text
bluesky-master/data-prep-workbench/
├─ pom.xml                        # org.bluesky:data-prep-workbench:0.1.0-SNAPSHOT, Java 8, SB 2.7.18
├─ run.sh / run.bat               # 一键：构建前端→拷贝静态资源→mvn spring-boot:run
├─ README.md
├─ src/main/java/org/bluesky/dataprep/
│  ├─ DataPrepApplication.java
│  ├─ common/                     # BaseRecord 约定、GeoJSON 类型、错误码、RevisionService
│  ├─ nav/  airport/  airspace/  airway/  weather/  performance/  radar/
│  ├─ map/                        # 图层聚合 API
│  ├─ excel/                      # 模板/导出/导入（POI）、ImportBatch/ImportRowError
│  └─ persistence/                # 全部 Mapper + Row
├─ src/main/resources/
│  ├─ application.yml             # 8090 / 127.0.0.1 / H2 file 默认（本机数据库）
│  ├─ application-mysql.yml       # 可选 MySQL profile
│  └─ db/migration/V1__initial_workbench.sql, V2__seed_prototype_data.sql
├─ src/test/java/org/bluesky/dataprep/...
└─ frontend/                      # vue3+ts+vite+pinia+ol 10+vitest
```

关键选型：

- 运行库=H2 文件库（`jdbc:h2:file:./data/prep;MODE=MySQL`，即原型「本机数据库」，零安装；测试 H2 mem）；MySQL 仅留 profile。
- Excel=Apache POI 5.2.5（SXSSF 流式导出）。
- 地图=OpenLayers 矢量图层（无瓦片服务，纯色底+经纬网格，复刻原型观感且离线可用）。
- 全局修订号=`workbench_state` 单行表计数，每次写操作 +1，`GET /api/health` 返回。

## 5. 数据库 Schema（V1 要点）

所有业务表统一含 BaseRecord 列：`id VARCHAR(36) PK, code, name, status, source_type, source_reference, revision INT DEFAULT 0, created_at/updated_at TIMESTAMP(3), created_by/updated_by, deleted BOOLEAN DEFAULT FALSE`。code 唯一性由 Service 事务内校验（单用户工作台可接受，保证 H2/MySQL 同构）。

一期表：navigation_point、airport、runway、airspace、airway、airway_segment、wind_field、wind_field_point、aircraft_type_performance、logical_radar_site、asterix_channel、radar_channel_binding、import_batch、import_row_error、workbench_state。字段以用户数据结构消息为准；几何以 TEXT 存 GeoJSON。

V2 种子=原型六页全部示例行（坐标取原型值），保证开箱界面与原型截图一致。

## 6. API 契约（REST，前缀 /api）

| 端点 | 说明 |
|---|---|
| GET /api/health | `{status:"UP", revision:N}` |
| GET /api/{entity}?page&size | 分页列表（deleted=0；radar 返回站点+通道混合行） |
| GET/POST/PUT/DELETE /api/{entity}[/{id}] | 查看/新建/编辑（revision 不匹配 409）/逻辑删除 |
| POST /api/{entity}/{id}/status | 启用/停用 |
| GET /api/map/layers | 五类 MapLayerItem（counts+features GeoJSON 摘要） |
| PUT /api/map/features | 地图会话批量保存（事务，成功 revision+N） |
| GET /api/templates/{entity} · GET /api/export/{entity} | 模板下载/导出 xlsx |
| POST /api/imports?entity= · GET /api/imports · GET /api/imports/{id}/errors | 上传校验执行/批次历史/行级错误 |

entity ∈ nav-point / airport / airspace / airway / wind-field / performance / radar-site / asterix-channel。

## 7. 任务分解（每任务一提交）

- T0 文档定桩：ADR-0014 + 本计划入库 + INDEX 登记。
- T1 后端骨架：pom/配置/启动类/workbench_state/health + HealthApiTest。
- T2 Schema+种子：V1 全表 DDL、V2 原型种子。
- T3 公共层：乐观锁 409、逻辑删除过滤、code 唯一校验、RevisionService。
- T4 垂直切片（NavigationPoint 全代码范本）+ NavPointApiTest。
- T5 Airport+Runway / Airspace / Airway+Segment：父子事务、GeoJSON boundary。
- T6 WindField+Point / Performance（标量）。
- T7 雷达三件套：站点/通道/绑定；CAT048 未绑站点保存 400。
- T8 地图聚合 API + 批量保存。
- T9 Excel：模板/导出/导入（行级错误+批次留档）。
- T10 前端骨架：tokens.css/App 布局/7 页列配置/api/health 轮询。
- T11 地图编辑器：图层面板/工具条/属性面板/批量保存。
- T12 编辑抽屉 + 数据编辑▾接线 + 导入向导五步。
- T13 打包+run.sh/run.bat+README+验收走查。

## 8. 兼容边界 / 风险 / 执行路线

- 兼容边界：不改 training-platform/、bluesky/ 任何文件；不改既有 V1–V6 迁移；独立端口 8090。回滚=删除 data-prep-workbench/ + ADR-0014 标 superseded。
- 风险：① code 唯一事务内校验（多人并发二期改部分索引）；② H2 文件库单进程独占（单用户假设成立）；③ POI 大文件——导入流式读、导出 SXSSF；④ OL 离线无底图——纯色底+网格即原型样式；⑤ BLUESKY 只读源一期不接入（sourceType 枚举已预留）。
- 二期明确不做：AirportWeather/SignificantWeatherArea、性能包线编辑、练习计划关联、训练分析视图、OpenAP 自动同步。
- Execution Route: inline，T0–T13 顺序执行，每任务完成即验证+一次提交；T4 范本后 T5–T7 实体在各自文件族内推进。
