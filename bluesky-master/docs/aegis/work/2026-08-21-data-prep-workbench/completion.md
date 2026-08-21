# 完成记录 — 2026-08-21

计划 `2026-08-21-data-prep-workbench.md` 全部 14 个任务（T0–T13）完成。

## 验证结论

- 后端：`mvn test` 49/49 绿（MockMvc 集成测试，覆盖 8 实体 CRUD、乐观锁 409、BLUESKY 只读 400、CAT048 绑定 400、Excel 导入导出往返、地图批量编辑回滚）。
- 前端：`npm test` Vitest 2/2 绿；`npm run build`（vue-tsc 严格类型检查）通过，产物入 `src/main/resources/static`。
- 打包：`target/data-prep-workbench.jar`（47MB，含前端），`./run.sh` 一键启动。
- 验收走查（对 jar 实测）：11/11 通过——首页、五图层计数、A593/雷达/气象坐标、错误修订 409、BLUESKY 只读 400、CAT048 缺绑定 400、编码重复 409、模板下载回传导入、批次列表。

## 提交清单

| 任务 | 提交 |
| --- | --- |
| T0 文档/ADR/索引 | 3a38994 |
| T1 骨架 | 81ed420 |
| T3 公共层 | bed4a03 |
| T4 导航点切片 | 5e543dc |
| T5 机场/空域/航路 | 3c3d862 |
| T6 气象/性能 | d0744bf（含 T7） |
| T7 雷达三件套 | 同上 |
| T8 地图聚合 | 40e71c3 |
| T9 Excel 导入导出 | f3f91e2 |
| T10 前端骨架 | 77add45 |
| T11 地图编辑器 | 24e27d5 |
| T12 编辑抽屉/导入向导 | d3a5407 |
| T13 打包/README/验收 | 2cf38b3 |

## 关键修正（后见之明）

1. 乐观锁：Service 不得用库中 revision 覆盖客户端提交值，否则 `WHERE revision=#{revision}` 永远命中。
2. MyBatis `map-underscore-to-camel-case` 必须放 `mybatis:` 顶层键，不能嵌在 `spring:` 下。
3. H2 Map 结果对 JDBC 标签大小写敏感：测试库曾带 `DATABASE_TO_LOWER=TRUE` 掩盖问题；修复为测试/生产同构 + 所有 Map 查询别名加双引号。
4. 导入编排不能整体 `@Transactional`：行级业务异常会把外层标成 rollback-only；改为逐行独立事务 + 批次独立落库。
5. `import_batch.batch_status` 需容纳 `COMPLETED_WITH_ERRORS`（V4 扩列）。

## 二期清单

机场气象/重要天气区域编辑、性能包线、训练计划联动、训练分析视图、BLUESKY 适配器导入。
