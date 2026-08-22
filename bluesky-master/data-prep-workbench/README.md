# 飞行数据准备与分析（data-prep-workbench）

独立子项目：为训练/仿真准备基础飞行数据（导航、机场、空域、航路、气象、机型性能、雷达与 ASTERIX 通道），提供 Excel 批量导入导出与全窗地图编辑器。按 [ADR-0014](../docs/adr/0014-standalone-data-prep-workbench.md) 作为与 bluesky-master 平级的独立 Maven 工程交付。

## 快速开始

```bash
./run.sh          # 打包并启动，浏览器访问 http://127.0.0.1:8090
```

- 仅需 JDK 8+（构建工具链 JDK 17 亦可）与 Maven；前端产物已随仓库发布，无需 Node。
- 默认内嵌 H2 文件数据库（`./data/prep.mv.db`），首次启动自动建表并写入原型演示数据。
- 停止：Ctrl+C。数据保存在 `./data/`，删除该目录即重置演示库。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 2.7 / Java 8 目标 / MyBatis 注解 Mapper / Flyway / H2（默认）或 MySQL（profile） |
| 前端 | Vue 3 + TypeScript + Vite + Pinia + OpenLayers 10 + Vitest |
| 打包 | 前端构建产物输出至 `src/main/resources/static`，随 jar 一体发布 |

## 功能地图

- **空域数据 ▾**：空域信息（航路点）/ 航路（航段子表，引用航路点）/ 物理扇区（扇区与 FIR 的独立边界区域）
- **气象数据**：风场（含风场点子表）可编辑；机场气象、重要天气区域为二期只读展示
- **机型性能**：OpenAP/BADA/LEGACY/MANUAL 四类来源；OpenAP 等只读保护
- **雷达与通道**：逻辑雷达站 + ASTERIX 通道（CAT021/048/062）；CAT048 强制绑定雷达站
- **数据编辑 ▾**：新建（编辑抽屉）/ 导入 Excel / 导入运行系统 ASF / 导出 Excel / 地图编辑
- **地图编辑器**：五类图层 GeoJSON 聚合、选择、新增点、绘制区域、编辑顶点、删除、批量保存

通用约束：`code` 业务编码唯一；`revision` 乐观锁（冲突返回 409）；逻辑删除；`sourceType=BLUESKY` 的源数据只读（400 拒绝）。

## REST API 摘要

```
GET  /api/health                        → {status, revision}（revision 随每次写操作 +1）
GET  /api/{entity}?page&size            → {items, page, size, total}
GET|POST /api/{entity}[/{id}]           详情 / 新建
PUT  /api/{entity}/{id}                 更新（body 带 revision，过期返回 409）
DELETE /api/{entity}/{id}?revision=     逻辑删除
POST /api/{entity}/{id}/status?status=  启用/停用
GET  /api/map/layers                    五类图层 GeoJSON
PUT  /api/map/features                  地图批量保存（UPDATE_GEOMETRY/UPDATE_PROPERTIES/DELETE）
GET  /api/templates/{entity}            下载导入模板 xlsx
GET  /api/export/{entity}               导出全部数据 xlsx
POST /api/imports/{entity}              上传导入（multipart file）
GET  /api/imports[/{batchId}/errors]    最近批次 / 逐行错误
POST /api/asf/replace-airspace          同时上传特征点与航路 ASF，并原子替换导航/航路数据
POST /api/asf/replace-physical-sectors  上传 FDP 体积定义 ASF，并原子替换物理扇区数据
GET  /api/physical-sector               物理扇区分页列表（详情及写操作遵循通用 CRUD）
```

`entity` ∈ nav-point / airport / airspace / airway / wind-field / performance / radar-site / asterix-channel；聚合端点 /api/weather、/api/radar。

## Excel 导入格式

模板表头即列定义（`*` 必填）。子表用打包文本：

| 子表 | 格式 |
| --- | --- |
| 机场跑道 | `跑道号:长度:宽度:真方位:道面`，多条 `;` 分隔 |
| 航路航段 | `起点编码-终点编码`，多段 `;` 分隔 |
| 风场点 | `经度:纬度:高度:风向:风速`，多点 `;` 分隔 |
| 通道绑定 | 雷达站编码，`;` 分隔 |

导入按 `编码` 匹配已有记录：存在则更新，否则新建；单行失败仅记录错误（`/api/imports/{batchId}/errors`），不影响其余行。

## ACCOPS ASF 导入

“数据编辑 → 导入运行系统 ASF”同时选择 `CHARACTERISTIC_POINTS.ASF` 与 `ROUTES.ASF`。导入器读取
`/DEFINITIONS/`、`/CODED_ROUTE/`、`/SID/` 和 `/STAR/`，并在统一航路列表中标明类型。

- `REPORT/AIRPORT_I/VORDME/NDB/VOR/DUMMY` 映射为工作台标准类型，同时保留原始类型、DMS 坐标及 REL/AA/PIL/DTI/TFM。
- 航路点序列转换为连续航段；编码航路保留 RNAV/RNP/RVSM，SID/STAR 保留机场、跑道及 `ELIGIBLE_ROUTE` 等源字段。
- 导入前完整解析并校验全部航路点引用；任一错误会回滚，原数据不变。
- 同编码定义保留文件中第一条，所有重复或坐标冲突均在导入结果中列出，不会静默覆盖。
- 成功导入会替换现有导航点、航路及航段；机场、空域边界、气象、性能、雷达数据不受影响。ASF 来源记录可在列表中继续编辑或删除。

`FDP_VOLUMES_DEFINITION.ASF` 可单独导入。导入器解析 `/POINTS/`、`/LAYER/`、`/VOLUME/`、
`/SECTOR/` 与 `/FIR/`，把相同名称下不同的水平边界和连续高度层拆成独立记录。物理扇区名称允许重复，
边界组成可直接保存有序经纬度，也可在人工新建/编辑时引用“空域信息”表中的点。

## 开发

```bash
# 后端（8090）
mvn spring-boot:run
mvn test                     # 48 个 MockMvc 集成测试

# 前端（5173，/api 代理到 8090）
cd frontend
npm install
npm run dev
npm test                     # Vitest
npm run build                # 产物写入 ../src/main/resources/static
```

MySQL：`BS_MYSQL_HOST=… BS_MYSQL_PASSWORD=… mvn spring-boot:run -Dspring-boot.run.profiles=mysql`（模板见 `application-mysql.yml`）。

## 目录结构

```
src/main/java/org/bluesky/dataprep/
  common/    异常、分页、乐观锁守卫、修订计数、健康端点
  nav/ airport/ airspace/ airway/    空域数据四域（Row+Mapper+Service+Controller）
  weather/ performance/ radar/       气象、性能、雷达三件套
  map/       图层聚合与批量编辑
  excel/     模板/导出/导入（EntitySchemas 注册 8 类实体）
  asf/       ACCOPS 特征点/编码航路解析、校验与原子替换导入
frontend/    Vue3 前端源码
run.sh|bat   一键打包运行
```

## 二期范围（本版未含）

机场气象 / 重要天气区域编辑、性能包线编辑、训练计划联动、训练分析视图、BLUESKY 适配器导入（`sourceType` 枚举已预留）。
