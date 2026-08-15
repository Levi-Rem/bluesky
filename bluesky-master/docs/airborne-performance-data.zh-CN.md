# 空中性能数据筛选与接入说明

## 1. 结论

`simulator_backup` 中可用于当前首版空中仿真的主数据来自以下三张表：

| 表 | 本次用途 |
|---|---|
| `ap_plane_type_info` | 机型代码、名称、尾流类别 |
| `ap_config_fly_info` | 机型升限，本次只导入升限字段 |
| `ap_fly_height_info` | 不同高度、飞行阶段和载重下的 CAS 包线、爬升率、下降率 |

本次不导入起飞、着陆、滑行、跑道和机场性能数据。`ap_fly_speed_limit` 的列名是高度分段、值却表现为速度，语义不够可靠；`ap_fly_acceleration_info` 不属于当前要求的最小闭环，因此均未接入。

## 2. 筛选规则

源库共有 31 个机型。每个机型原始高度表有 49 个高度层、3 个飞行阶段、3 种载重。首版仅采用中载数据，并施加以下硬规则：

1. 高度不得超过 `ap_config_fly_info.ceiling_max`，因此每阶段保留 44 个高度层（0–12,500 m）。
2. 爬升、巡航、下降三阶段的高度网格必须完整且一致。
3. 最小 CAS 必须大于 0，最大 CAS 必须大于最小 CAS。
4. 爬升阶段爬升率必须大于 0；下降阶段下降率必须大于 0。
5. 机型必须能映射为当前 OpenAP 支持的明确 ICAO 机型；不采用“相似机型”猜测映射。
6. 源库有 18 条低高度爬升记录的参考 CAS 高于同记录最大 CAS；目标库将参考 CAS 截断至最大 CAS，最小/最大包线和源记录标识保持不变。

通过源数据质量规则的机型有 12 个，其中 9 个可明确映射到当前 OpenAP：

| BlueSky/OpenAP 机型 | 源库机型 | 备注 |
|---|---|---|
| A319 | A319 | 直接映射 |
| A320 | A320 | 直接映射 |
| A321 | A321 | 直接映射 |
| A21N | A321NEO | 标准 ICAO 代码映射 |
| A332 | A332 | 直接映射 |
| A388 | A380 | 标准 ICAO 代码映射 |
| B738 | B738 | 直接映射 |
| B744 | B744 | 直接映射 |
| B77W | B773ER | 标准 ICAO 代码映射 |

暂不采用的 3 个质量合格源机型为 A345、A346、S365：当前 OpenAP 没有可确认的一一对应代码。其余 19 个机型存在测试机型、零包线、零升降率、最小速度为零或整批复用通用模板等问题，首版不导入。

## 3. 数据形式与单位

整理后的权威业务数据已复制到 `bluesky_training`：

| 目标表 | 内容 | 当前行数 |
|---|---|---:|
| `aircraft_performance_type` | BlueSky 机型代码、源机型代码、名称、尾流、升限、载重基线和源记录标识 | 9 |
| `aircraft_performance_envelope` | 按机型、飞行阶段和高度展开的 CAS 包线与最大升降率 | 1,188 |

目标表使用 `(aircraft_type, flight_phase, altitude_meters)` 唯一约束，并保留 `source_plane_type_id`、`source_fly_height_id`，可由目标记录追溯到 `simulator_backup` 源记录。数据库迁移结构位于 `V4__add_airborne_performance_tables.sql`，跨库复制脚本为 `utils/migrate_airborne_performance_to_training.sql`；脚本可重复运行，不会重复插入。

目标表还约束 `minimum_cas_mps <= nominal_cas_mps <= maximum_cas_mps`。源库上述 18 条参考速度越界记录按第 2 节规则规整，避免以后把参考速度作为目标速度使用时越过包线；原值仍可通过 `source_fly_height_id` 回查源库。

同时保留供 Python Adapter 离线加载的导出文件：

`bluesky/plugins/training_adapter/data/airborne_performance.json`

每个机型保存中载下的爬升、巡航、下降曲线；每个高度点包括：

- 高度：m；
- 经济/目标 CAS、最小 CAS、最大 CAS：统一由源库 km/h 转换为 m/s；
- 最大爬升率或最大下降率：m/s；
- 升限：m。

适配器在任意两个高度层之间做线性插值，低于最低层时采用最低层，高于机型升限时拒绝。

## 4. 当前运行行为

该数据作为 OpenAP 之上的训练平台空中包线约束，不替换 OpenAP 的推力、阻力、燃油和加速度模型，也不修改 BlueSky 核心源码：

- 创建航空器时，校验当前高度的巡航 CAS 包线和升限；超限则拒绝创建。
- 执行 `SPD` 时，校验当前高度的巡航 CAS 包线；超限则拒绝指令，不静默截断。
- 执行带显式 VS 的 `ALT` 时，根据目标高度确定爬升或下降，并按当前高度插值出的最大升降率限幅；响应会返回请求值、实际采用值和是否发生限幅。
- 航空器状态快照带 `performanceEnvelope`，用于调试当前最小/最大 CAS 和最大爬升/下降率。
- `PING` 返回性能库来源、范围、载重基线和已接入机型数量。
- 参考数据查询会标识机型是否具有导入的空中性能曲线。

## 5. 重新导出

Python Adapter 运行时不连接 `simulator_backup`。只有数据准备或更新时执行导出脚本：

```powershell
$env:BLUESKY_SOURCE_DB_PASSWORD='数据库密码'
.\.venv\Scripts\python.exe .\utils\export_airborne_performance.py `
  --mysql 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
```

脚本不会修改源数据库。它会重新执行完整性校验，任一机型不满足规则即失败，不会生成部分数据文件。

## 6. 已知边界

- 首版固定采用中载曲线；当前源库中三个载重档多数值相同，暂不提供飞机级载重选择。
- 数据在若干高度点有非单调变化（例如 A320 在 6,300 m 的爬升率突降），本次忠实保留源值，不擅自平滑。
- 仅对 `SPD`（CAS）做导入包线校验；`MACH` 仍由 OpenAP 的 MMO 约束，避免把源表中标注和数值均有歧义的 `mach_number` 当作真实马赫数。
- 本次没有导入燃油消耗、重量、推力、加速度、起降和机场修正数据。
