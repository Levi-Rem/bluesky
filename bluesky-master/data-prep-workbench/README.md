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

- **空域数据 ▾**：导航点 / 机场（含跑道子表）/ 空域（GeoJSON 边界）/ 航路（航段子表，引用导航点）
- **气象数据**：风场（含风场点子表）可编辑；机场气象、重要天气区域为二期只读展示
- **机型性能**：OpenAP/BADA/LEGACY/MANUAL 四类来源；OpenAP 等只读保护
- **雷达与通道**：逻辑雷达站 + ASTERIX 通道（CAT021/048/062）；CAT048 强制绑定雷达站
- **数据编辑 ▾**：新建（编辑抽屉）/ 导入 Excel / 导出 Excel / 地图编辑
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
frontend/    Vue3 前端源码
run.sh|bat   一键打包运行
```

## 二期范围（本版未含）

机场气象 / 重要天气区域编辑、性能包线编辑、训练计划联动、训练分析视图、BLUESKY 适配器导入（`sourceType` 枚举已预留）。
