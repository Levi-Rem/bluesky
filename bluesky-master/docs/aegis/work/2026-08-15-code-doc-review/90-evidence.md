# 代码-文档一致性审阅报告（2026-08-15）

- 基线 HEAD：`0ba143f`（Add training platform first-version closed loop）
- 审阅范围：training-platform（Java/Vue/Flyway/脚本）、bluesky/plugins/training_adapter
- 对照基线：详细设计（主基线）、实施任务、验收问题与用例、修复方案、概要设计、差异分析、CONTEXT、ADR-0001~0013、DEVELOPMENT_GUIDE
- 方法：4 个并行模块审阅（Java/前端/Adapter/修复口径核对）+ 主审交叉验证（git 证据、源码逐行抽查）
- 口径规则：详细设计 §2.3 首版简化登记优先于概要设计总体要求；不考虑极端场景

## 总评

首版闭环主体与详细设计**高度一致**（约 8/10）：状态机、三通道、六类指令、单帧容差、英制口径、同步确认、协议 10 类消息、单位换算、sequence/instanceId、DCT/RTE 单调回执、建机失败回滚均按文档落地；修复方案 §0 五个缺陷全部核实已修复（测试+实现双证据）；Q01（不修改 BlueSky 核心）git 证据确凿通过；T24"进行中"标记与证据相符。

主要问题集中在三类：① 文档滞后（性能包线已实现却登记"后续"、迁移版本数 V1~V5 vs 文档 V1~V3）；② SSE 契约缺口（snapshot 不含指令队列、heartbeat 未实现、无递增退避）；③ 若干正常路径缺陷（队列停滞、实例切换、协议应答未校验等）。

## A. 文档与实现差异

| # | 严重度 | 位置 | 文档依据 | 差异 |
|---|---|---|---|---|
| D1 | 高 | `engine.py:276-333`、`airborne_performance.py`、`data/airborne_performance.json`、迁移 V4/V5 | CONTEXT L716「性能包线暂不实现」、修复方案 WP-5/G5、附录A#6、详细设计 §2.3「创建校验仅存在性」、§5 目录清单（4 文件） | 空中性能包线/升限校验已完整实现（建机、ALT 升限、SPD 包线、VS 限幅、性能快照），但所有文档登记为"后续/暂不实现"；Adapter 实际 6 文件+data/ |
| D2 | 高 | `EventStreamService.java`、`WorkstationBootstrapResponse.java` | 详细设计 §8.6「snapshot：训练组、引擎、全部航空器和指令队列」、Q23 | SSE snapshot 与 bootstrap 均不含指令队列，断线重连无法恢复指令队列 |
| D3 | 中 | 迁移目录 V1~V5 | 实施任务 T24、修复方案「Flyway V1～V3」 | 实际迁移 V1~V5（V4 空中性能表、V5 速度规整），文档版本数滞后 |
| D4 | 中 | `EventStreamService.java`（无 heartbeat） | 详细设计 §8.6「heartbeat：服务时间；用于发现断线」 | heartbeat 事件前后端均未实现，断线发现仅靠 EventSource onerror |
| D5 | 中 | `CreateAircraftDialog.vue`（无该字段） | 详细设计 §8.3 约束 4「经纬度或初始航路点之一」 | 后端 `CreateAircraftRequest.initialWaypoint` 已实现，前端表单缺失，界面只能走经纬度路径 |
| D6 | 中 | `store.ts`（原生 EventSource） | §8.6/§13.5「断线后以递增退避重连」 | 未实现递增退避，依赖浏览器固定重试间隔 |
| D7 | 低 | `ReferenceController.java` | §6「Controller 不直接访问 Mapper/网关」分层约定 | reference 模块无 Service 层，Controller 直接注入 SimulationGateway |
| D8 | 低 | `ZeroMqSimulationGateway.request` | §10.1「超时后丢弃并重建 REQ Socket」 | 每次请求都新建 socket（规避 REQ 死锁更稳），但与"持久复用+超时重建"表述不符 |
| D9 | 低 | `AircraftController`/`InstructionController` | §8 未规定状态码 | create 返回 201 CREATED，文档未登记 |
| D10 | 低 | `runner.py` argparse 默认值 | §4「均可通过环境变量覆盖」 | BS_ADAPTER_*_ENDPOINT 仅 start-platform.ps1 读取后显式传参，runner 自身不读环境变量，归属未澄清 |
| D11 | 低 | `protocol.py` | ADR-0012「每个训练组消息都必须同时校验训练组 ID 与实例 ID」 | handle 不校验 exerciseGroupId（单组首版可豁免，建议文档登记） |
| D12 | 低 | CONTEXT 正文 | CONTEXT 自身 | 米制/英制总体口径与首版口径大量并存，仅 L581/L590/L701-721 显式标注例外，易误读 |

## B. 代码缺陷

| # | 严重度 | 位置 | 缺陷 | 触发条件 | 建议 |
|---|---|---|---|---|---|
| F1 | 高 | `InstructionProgressService.evaluateChannel:55-65` | 待执行指令下发失败（Adapter 拒绝/不可用）后直接 return，同通道剩余 pending 永不派发：之后每帧 findExecuting=null 提前返回，队列永久停滞 | 排队指令下发瞬间引擎瞬时不可用/拒绝（正常运维场景） | 失败后继续尝试派发下一 pending，或依赖失败暂停语义显式登记 |
| F2 | 中 | `AdapterStateProjector.isNewSequence:89-101` | 退役集合仅在 instanceId 变化分支检查；新实例首帧畸形（seq=-1）被丢弃时不更新 lastInstanceId，旧实例后续递增帧在同一分支被继续接受 | Adapter 重启抖动/首帧残缺 | 同一实例分支也检查 retiredInstanceIds；丢弃帧时也完成实例切换判定 |
| F3 | 中 | `ZeroMqSimulationGateway.request` | 未校验响应 requestId 与请求匹配 | Adapter 乱序/重复应答 | 比对 requestId，不符丢弃并重建 |
| F4 | 中 | `AdapterStateProjector.persist:78-79` | simulation_time 无条件覆盖，不校验训练组 state | READY/PAUSED 下收到帧（威胁 Q08） | 按 exercise_group.state 守卫 |
| F5 | 中 | `runner.py` poll 循环 | engine.update()（含 sim.update、DCT 观察）无异常隔离，引擎运行时异常穿透杀死 Adapter 进程 | 引擎任何运行时异常 | 循环内捕获记录，不退出主循环 |
| F6 | 中 | `engine.py:394-418,493-498` | 硬依赖 OpenAP 专属属性 traf.perf.coeff（PerfBase 无此属性） | performance_model≠openap 时机型查询/建机抛 AttributeError | hasattr 防御或登记"仅支持 OpenAP" |
| F7 | 中 | `api.ts` | 错误响应只取 message，丢弃 code/fieldErrors/requestId | 任何 400 校验失败 | 解析 fieldErrors 并逐字段展示（§8/§13 要求） |
| F8 | 中 | `CreateAircraftDialog.vue` | `...form` 直传 + 清空经纬度产生 ''（非 null） | 用户清空经纬度输入 | 显式构造请求对象，空串归一为 null；否则 Jackson 框架级 400 绕过友好校验 |
| F9 | 中 | `store.ts load()/connectEvents` | bootstrap 成功但 SSE 建立失败时整体落入加载错误分支；snapshot 处理中 loadInstructions 未捕获异常 | 网络抖动/重连 | 解耦 bootstrap 渲染与 SSE；void 调用加 catch |
| F10 | 低 | `InstructionProgressService.refreshActiveInstruction:73-77` | findLatestExecuting 未按通道过滤，跨通道时"当前指令"显示错源 | 多通道并行执行 | 按通道或全局语义明确化 |
| F11 | 低 | `InstructionParser.invalid:78` | 所有语法错误统一提示「首版航向指令格式为 HDG 090」 | 任意指令语法错误 | 按指令类型返回对应格式提示 |
| F12 | 低 | `App.vue toggleRun` | 无 catch，api.start/pause/resume 失败（503）未捕获 rejection | 引擎 UI 之外断开时点击 | 增加 catch 写入 store.error |
| F13 | 低 | `store.ts updateEngine` | bootstrap 为 null 时构造空壳占位对象，绕过主界面 v-if 渲染空壳 | engine-state 事件先于 bootstrap | 保持 null 直至真 bootstrap |
| F14 | 低 | `SituationMap.vue` | 单要素命中即停止遍历，重叠标牌只能选最上层 | 两机标牌重叠 | 收集全部命中或循环切换（首版可接受） |
| F15 | 低 | 迁移 V1 列定义 | VARCHAR(16)/VARCHAR(4) 与应用校验长度（呼号 2-7、机型≤4、机场 4/8）不一致，直插可绕过 | 手工直插 | 对齐列宽或补 CHECK |
| F16 | 低 | `App.vue:87` | 本席/全组计数恒等（同数组） | 未来多席位 | 按 assignedTerminalId 过滤本席（当前单席无功能影响） |
| F17 | 低 | `instructionQueue.ts` | PENDING 按 sequenceNumber 降序排在执行项之前，视觉顺序与执行顺序相反 | 多 pending 时 | 确认语义后统一升序或注释 |
| F18 | 低 | `InstructionProgressService.evaluateChannel:67`、`InstructionService` | executeInstruction 成功后 updateStatus 返回 0 行未检查（Java B2） | 并发/重入改状态 | 校验受影响行数 |
| F19 | 低 | `ZeroMqSimulationGateway.health:49` | catch(RuntimeException) 覆盖不到 IOException（Java B5） | 控制通道 JSON 解析异常 | 异常边界覆盖 IOException/Throwable |
| F20 | 低 | `AircraftService` route 空兜底 | route 为空时静默替换为 [destination]（Java B8） | 前端传空航路 | 显式校验或文档化兜底语义 |
| — | 已排除 | `InstructionService.nextSequence:132-135`（Java B7） | 子代理认为 executing==null 时固定返回 1L 会撞号；主审核实该分支不可达：executeNow=immediate\|\|executingCount==0，executingCount>0 时 findExecuting 必非 null，故 executing==null 且 executeNow=false 是死代码 | — | 建议删死分支或加注释，不列为缺陷 |
| 附注1 | 低 | `engine.py:395-398`（Adapter B-2） | OpenAP rotor 内部别名（Bob/Echo/Super）混入机型查询字典 | 查询 rotor 机型时暴露非平台码 | 与 F6 一并处理：仅暴露 fixwing 或过滤 |
| 附注2 | 低 | `protocol.py:15-29`（Adapter B-4） | handle 入口未判 isinstance(request, dict)，非对象 JSON 时 request_id 取值先行抛错 | 非对象 JSON 请求（畸形输入） | 入口防御 |
| 附注3 | 低 | `engine.py:210-217`（Adapter B-8） | execute_instruction 恒返回 accepted:true，多数引擎调用不返回失败标志 | 语义澄清 | 注释"accepted 仅表示已下发，完成以状态帧为准" |

## C. 已核实一致/修复落实（正向证据）

- **Q01 通过**：`git show --stat 0ba143f` 在 bluesky/ 下仅新增 training_adapter 6 文件（9302 行插入、0 改动），core/traffic/stack/tools/simulation/navdatabase/ui 零改动
- **修复方案 §0 五缺陷全部落实**：① RESET 清 RTE/DCT 回执（engine.py:82-83 + test_runner_ping.py）② 启动复位容错（StartupReset.java + StartupResetTest）③ 空串二次代码归一化（AircraftService.normalizedTransponderCode + V3 + AircraftApiTest）④ 排队 ALT·VS 下发重定符号（InstructionProgressService.resolveForDispatch:119-129 + InstructionApiTest）⑤ 正北 360（situationGeometry.formatHeading + 测试）
- 首版口径核对：英制不解析后缀、单帧容差（HDG±2/ALT±100/SPD±5/MACH±0.01）、三通道独立仲裁、同步确认无 DISPATCHING、出现时间仅格式校验、无多选/批量——全部相符
- 协议：10 类消息齐全、信封 protocolVersion 1.0、单位换算系数正确（aero.ft/kts 常量）、sequence 递增/instanceId 更换、DCT passed/RTE activated 单调回执、建机失败回滚（traf.delete）
- MACH 闭环核实：selspdcmd 存原始值，vcasormach2tas 按阈值解释为 Mach（正常范围无缺陷）
- 测试覆盖：Java 集成测试（Aircraft/Instruction/Exercise/Event/Reference/Startup/Adapter 均存在）、Python 协议测试、前端单测（commandKeys/situationGeometry）
- ADR 抽查：0005 三通道✓、0007 未做 CAT021/048（符合首版）✓、0008 SSE+REST✓、0012 版本化协议✓、0013 无登录✓

## D. 修复优先级建议（Top 10）

1. **D2（高）** snapshot 补指令队列——否则 Q23 与 §8.6 不成立
2. **F1（高）** 队列停滞——排队指令下发失败后继续派发或显式登记失败暂停语义
3. **D1（高，文档侧）** 性能包线"已实现"回写：CONTEXT L716、修复方案 WP-5/附录A#6、详细设计 §2.3/§5——文档与代码对齐（改文档即可，功能合理）
4. **D4（中）** heartbeat 前后端补实现（或登记首版豁免）
5. **F2/F3/F4（中）** 实例切换、requestId 校验、仿真时间 state 守卫——协议与状态正确性
6. **F5（中）** Adapter update 异常隔离
7. **D5/F7/F8（中）** 创建表单补 initialWaypoint、解析 fieldErrors、空串归一化
8. **F6（中）** OpenAP 硬依赖防御或登记
9. **D6（中）** SSE 递增退避重连
10. **D3（低）** 迁移版本数文档回写 V1~V5
