---
status: accepted
---

# 将 BlueSky 隔离为低侵入飞行仿真引擎

目标系统将 BlueSky 定位为可替换的飞行仿真引擎，只负责航空器运动、性能、航路、环境和仿真时钟等计算。固定终端、席位类型及其功能边界、飞行计划、练习计划、通信、记录评估及外部协议由外围训练平台拥有；平台通过仿真适配器与 BlueSky 交换命令、状态和事件，并优先采用插件、扩展实体和既有网络接口，尽量不修改 BlueSky 核心源码。该边界避免训练业务与 BlueSky 全局状态和内部数组耦合，也为多训练组隔离、独立或融合界面以及未来替换仿真引擎保留空间。

## 后果

- 前端和业务服务不得直接读写 `bs.traf`、`bs.sim` 等 BlueSky 全局对象
- 对 BlueSky 的必要修改必须收敛在明确的扩展字段和适配层，并单独维护补丁清单
- 训练业务状态以训练平台为权威数据源，BlueSky 状态以飞行计算结果为权威数据源
- 每个运行中的训练组绑定独立仿真实例，不使用 Traffic Group 代替训练隔离
- BlueSky 侧优先采用独立 `training_adapter` 插件以及最小扩展 `Entity/TrafficArrays`，只维护 Adapter 执行飞行仿真所需的稳定平台航空器 ID、扩展引导和特情状态；飞行计划、ICAO24、Squawk、FSA、控制模式等训练业务字段由 Java/MySQL 权威维护，不在 BlueSky 或 Adapter 建立业务镜像，Track Number 则由 ASTERIX 输出层按数据源独立维护
- Adapter 只发布带平台航空器 ID 的 BlueSky 飞行真值；Java 平台按该 ID 合并 MySQL 业务字段，形成工作台、记录和 ASTERIX 共同消费的标准航空器状态快照
- 修改 `Traffic`、`Autopilot`、`Route`、`Simulation` 等核心代码必须逐项例外审批，并附修改原因、影响范围、补丁清单和上游兼容性测试
- 训练组、终端与席位功能边界、语音、指令业务状态、记录时间线和评估不得进入 BlueSky 核心；业务层也不得公开 BlueSky 原生命令字符串或内部数组结构
