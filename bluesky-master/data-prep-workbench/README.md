# 飞行数据准备与分析（data-prep-workbench）

独立子项目：为训练/仿真准备基础飞行数据（导航、机场、空域、航路、气象、机型性能、雷达与 ASTERIX 通道），提供 Excel 批量导入导出与全窗地图编辑器。按 [ADR-0014](../docs/adr/0014-standalone-data-prep-workbench.md) 作为与 bluesky-master 平级的独立 Maven 工程交付。

## 快速开始

```bash
./run.sh          # 打包并启动，浏览器访问 http://127.0.0.1:8090
```

- 仅需 JDK 8+（构建工具链 JDK 17 亦可）与 Maven；前端产物已随仓库发布，无需 Node。
- 默认内嵌 H2 文件数据库。通过 `run.bat` / `run.sh` 启动时，数据库稳定保存在模块的 `data/prep.mv.db`；直接运行 JAR 时可用 `BS_PREP_DATA_DIR` 指定数据库目录。
- 停止：Ctrl+C。删除上述数据目录即可重置演示库。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 2.7 / Java 8 目标 / MyBatis 注解 Mapper / Flyway / H2（默认）或 MySQL（profile） |
| 前端 | Vue 3 + TypeScript + Vite + Pinia + OpenLayers 10 + Vitest |
| 打包 | 前端构建产物输出至 `src/main/resources/static`，随 jar 一体发布 |

## 功能地图

- **空域数据 ▾**：空域信息（航路点）/ 航路（航段子表，引用航路点）/ 物理扇区（扇区与 FIR 的独立边界区域）
- **气象数据**：风切变、下击暴流、急流、湍流、平流雾、辐射雾、雷雨区域
- **机型性能**：同一机型可按高度层保存多条独立性能与响应参数
- **雷达与通道**：逻辑雷达站 + ASTERIX 通道（CAT021/048/062）；CAT048 强制绑定雷达站
- **数据编辑 ▾**：新建（编辑抽屉）/ 导入 Excel / 导出 Excel / 地图编辑
- **地图编辑器**：五类图层 GeoJSON 聚合、选择、新增点、绘制区域、编辑顶点、删除、批量保存

通用约束：活动记录的 `code` 业务编码不区分大小写唯一；`revision` 乐观锁（冲突返回 409）；逻辑删除。常规列表编辑保持 `sourceType=BLUESKY` 只读；地图编辑是明确授权的数据维护入口，首次修改会转为 `MANUAL`，删除前必须确认。

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
GET  /api/physical-sector               物理扇区分页列表（详情及写操作遵循通用 CRUD）
```

`entity` ∈ nav-point / airport / airspace / airway / wind-field / weather / performance / radar-site / asterix-channel / physical-sector；聚合端点 /api/weather、/api/radar。

## Excel 导入格式

模板表头即列定义（`*` 必填）。子表用打包文本：

| 子表 | 格式 |
| --- | --- |
| 机场跑道 | `跑道号:长度:宽度:真方位:道面:入口1经度:入口1纬度:入口2经度:入口2纬度:磁方位:状态`，多条 `;` 分隔 |
| 航路 | 单元格内按顺序填写导航点编码，以空格分隔；类型为 `CODED_ROUTE` / `SID` / `STAR` |
| 风场点 | `经度:纬度:高度:风向:风速`，多点 `;` 分隔 |
| 通道绑定 | 雷达站编码，`;` 分隔 |

导入通常按 `编码` 匹配已有记录；气象按“名称 + 类型”、机型性能按“机型 + 尾流类别 + 高度层”匹配。存在则更新，否则新建；单行失败仅记录错误（`/api/imports/{batchId}/errors`），不影响其余行。

## 开发

```bash
# 后端（8090）
mvn spring-boot:run
mvn test

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

机场气象、性能包线编辑、训练计划联动、训练分析视图、运行系统适配器导入（`sourceType` 枚举已预留）。
