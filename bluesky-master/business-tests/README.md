# 模拟飞行员工作台第二版自动化业务验收

本目录把详细设计第 15.2 节的 AT-01～AT-12 转成可重复执行的真实 HTTP 业务测试。测试直接访问已启动的 `/api/v2`，不连接数据库造状态，也不把单元测试结果冒充端到端验收。

## 结果语义

- `PASS`：场景规定的业务动作和业务结果全部验证成功。
- `FAIL`：实现返回错误、契约不符、业务结果不符，或设计要求的 HTTP 能力不存在。当前实现尚未暴露的报告、脚本、消息、假目标、显示方案和特殊 profile 接口会明确失败。
- `BLOCKED`：测试环境缺少 ACTIVE 航空器、参考数据、真实 Adapter、第二/第三目标席位、故障注入证据或容量结果。`BLOCKED` 的进程退出码为 2，不能算验收通过。

每次运行生成 JSON 证据，包含固定随机种子、每个步骤、脱敏后的请求/响应、HTTP 状态码、耗时、requestId 和 operationId。网关密钥只从环境变量读取，不写入配置和证据。

## 环境准备

1. 复制 `config.example.json` 为 `config.local.json`；本地配置已由 `.gitignore` 的 `*.local.*` 规则排除。
2. 准备固定参考快照已固定、真实 Adapter 已连接、状态为 `RUNNING` 的训练组和 `ACTIVE` 航空器。会修改责任归属或训练状态的场景必须使用独立夹具；通过 `scenarioConfigs.AT-xx` 覆盖该场景的 groupId、aircraftId 和身份，避免场景间污染。
3. 准备源席、目标席以及并发移交所需的另外两个不同频率席位；把终端 ID、受信指纹摘要和业务测试数据写入本地配置。
4. 通过环境变量提供测试网关密钥：

```powershell
$env:BS_ACCEPTANCE_GATEWAY_SECRET = "仅本机测试环境密钥"
```

示例配置中的跑道、程序、航路点、VOR 和等待点只是占位符，必须替换为当前固定参考快照内的真实数据。

## 执行

```powershell
# 快速验证当前已实现的应答机链路
./business-tests/run.ps1 -Config ./business-tests/config.local.json -Scenarios AT-06

# AT-01～AT-11
./business-tests/run.ps1 -Config ./business-tests/config.local.json

# 列出设计规定的稳定入口函数
python ./business-tests/acceptance_runner.py --list
```

默认结果位于 `business-tests/artifacts/<runId>/result.json`。退出码：0 全部通过，1 存在业务失败，2 存在环境阻塞，3 为测试框架自身异常。

## 逐机长指令业务测试

`instruction_acceptance_runner.py` 以 `docs/contracts/command-catalog-v2.json` 为强制覆盖基线。当前包含全部 30 个目录命令、194 个显式语义/边界用例，并为 27 个创建指令的命令自动增加 5 类通用守卫；23 个控制通道命令再增加 `AFTER_COMPLETION` 队列守卫，合计 352 项检查。

覆盖内容包括：

- ACID 唯一匹配、多匹配保持原选择、不匹配、非法后缀，以及不得创建后端指令；
- HDG、LEFT、RIGHT、ALT、VS、SPD、MACH 的单位换算、上下界、稳定终态和性能包线；
- DCT、RTE、RESUME、ORBIT、HOLD、OFFSET、VOR 的航路原子性、进入/退出、厂商兼容语法和参考数据拒绝；
- SIDSTAR、P_LEVEL、P_TIME 的程序、未来航段、三种仿真时间及不跨日规则；
- TAKEOFF、ILS、MISSED 的阶段前置、跑道/程序、复合通道和执行终态；
- SQK、SSRMODE、NML、IDENT、NSPEED 的前导零、原子恢复、自动清除和保护逻辑；
- ID、DECOMP 的 profile 缺失、发布版本、模式、CLR 和 checksum 未加载；
- FRE 不创建指令且直接原子移交；DEL 必须执行预览，并校验 token 签名、终端/航空器/revision 绑定、30 秒过期、一次性消费和删除 Saga；
- 每个创建指令的命令均检查缺失 revision、过期 revision、text/command 二选一、非责任席、匿名身份；控制命令还检查 `AFTER_COMPLETION` 的 predecessor 和 `PREDECESSOR_ACTIVE`；
- 正向指令检查幂等重放、同键异载荷冲突、解析参数、最终状态、命令报告原始/规范文本；完成的引导命令还检查关联 `TARGET_REACHED` 飞行报告。

准备配置：

```powershell
Copy-Item ./business-tests/instruction-config.example.json `
  ./business-tests/instruction-config.local.json
```

模板中的跑道、VOR、程序、未来/已飞航段必须替换为固定参考快照中的真实数据。TAKEOFF、ILS、MISSED、性能保护、profile、移交和删除使用独立航空器；每个命令的 `guardAircraftId` 与 `queueAircraftId` 也必须是可丢弃的专用夹具，不能与人工训练共用。

执行方式：

```powershell
# 查看全部命令和稳定 case ID
python ./business-tests/instruction_acceptance_runner.py --list

# 单条指令
./business-tests/run-instructions.ps1 `
  -Config ./business-tests/instruction-config.local.json `
  -Commands HDG

# 一个领域
./business-tests/run-instructions.ps1 `
  -Config ./business-tests/instruction-config.local.json `
  -Commands HDG,LEFT,RIGHT,ALT,VS,SPD,MACH

# 全部 30 个命令
./business-tests/run-instructions.ps1 `
  -Config ./business-tests/instruction-config.local.json
```

ACID 是浏览器本地选机行为，HTTP 脚本只能证明可见航空器集合和“不得创建后端指令”。先执行下面的独立前端业务测试，它会生成 `clientAutomationEvidencePath` 所需证据；示例证据默认全部为 `BLOCKED`，防止尚未执行时误报通过。

```powershell
./business-tests/run-client-commands.ps1
```

## AT-11 与 AT-12

AT-11 不会自行杀死 Java/Adapter。由受控环境完成浏览器断网、指令执行中重启、删除 Saga 中重启、checksum 破坏和升级保留验证后，将结果写入 `recoveryEvidence`；任一证据缺失都不会通过。

AT-12 的 HTTP 负载入口为 `loadtest/workstation_v2.js`，64 路 SSE 长连接入口为 `loadtest/sse_soak.py`。两者完成后，把资源监控、故障调度和稳定性结果汇总为 `capacitySummaryPath` 指向的 JSON，再运行：

```powershell
python ./business-tests/acceptance_runner.py --config ./business-tests/config.local.json --scenarios AT-12
```

可复制 `loadtest/at12-summary.example.json` 填入监控和故障调度实测值，再用以下命令把 K6、SSE、监控和日志的 SHA-256 清单及 requestId 样本合并进最终摘要：

```powershell
python ./scripts/collect_acceptance_evidence.py `
  --seed 20260819 `
  --metrics ./loadtest/at12-summary.local.json `
  --raw ./loadtest/artifacts/k6-summary.json `
  --raw ./loadtest/artifacts/sse-summary.json `
  --log ./loadtest/artifacts/platform.log `
  --output ./business-tests/artifacts/at12-summary.json
```

容量摘要必须证明：预热和 30 分钟统计窗完成、成功样本不少于 10,000、8 小时无 OOM/异常重启、CPU 和四项增长指标满足详细设计 15.4，并且故障调度及可靠事件恰好一次副作用全部完成。
