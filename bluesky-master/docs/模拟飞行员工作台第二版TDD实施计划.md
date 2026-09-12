# 模拟飞行员工作台第二版 TDD 实施与执行计划

> 文档版本：1.1
> 编制日期：2026-08-31
> 唯一需求基线：[模拟飞行员工作台第二版详细设计.md](./模拟飞行员工作台第二版详细设计.md)（2.2）
> 适用代码：`training-platform`、`bluesky/plugins/training_adapter`、`tests/training_adapter`

## 1. 文档定位与执行约束

### 1.1 权威关系

1. 本文只定义“如何实现、如何测试、按什么顺序执行”，不重新定义业务语义。
2. 状态、字段、错误码、接口、时序或验收口径与第二版详细设计冲突时，以第二版详细设计为准，并先修订详细设计，再同步本文。
3. 第一版文档、现有 `/api/v1` 行为和当前代码只能作为迁移输入，不得覆盖第二版语义。
4. 第二版实现完成后，本文作为实施和排期的唯一计划；需求验收仍以第二版详细设计为唯一基线。

### 1.2 TDD 强制循环

每个计划项都按以下顺序执行，禁止先写生产代码再补测试：

1. **Red**：先增加一个只描述单一行为的失败测试；确认失败原因是能力缺失，而不是测试环境错误。
2. **Green**：只实现让当前测试通过的最小生产代码，不提前实现后续计划项。
3. **Refactor**：在测试全绿时消除重复、收紧命名和边界；重构不得改变公开契约。
4. **Contract**：涉及 REST、SSE、Adapter 或数据库时运行相应契约测试。
5. **Evidence**：保存测试命令、结果、关键 requestId/operationId 和必要日志，登记到里程碑验收记录。

测试命名统一采用 `given...When...Then...`；Java 测试方法使用 lowerCamelCase，Python 使用 snake_case，前端 Vitest 使用中文或英文完整行为句。每个测试只允许一个主要失败原因。

### 1.3 完成口径

一个功能计划只有同时满足以下条件才可标记完成：

- 单元、领域、持久化、接口、协议和前端测试按本计划全部通过；
- 幂等、并发、权限、失败恢复和审计不是空实现；
- 不依赖 `/api/v1` 或 `GROUP-DEFAULT` 特判；
- 所有异步动作均能查询中间状态、失败原因和最终结果；
- 新增公共契约已进入 OpenAPI/JSON Schema，并通过双向一致性测试；
- 代码评审确认函数职责与本文一致，没有将复杂 Adapter 状态推断放到 Java 或前端。

## 2. 当前基线与改造原则

### 2.1 当前代码基线

| 区域 | 当前实现 | 第二版处理 |
|---|---|---|
| 训练组 | `ExerciseGroupService.start/pause/resume`，仅默认组，同步 Adapter 调用 | 保留类名，改为持久化过渡状态 + Outbox；新增 end、确认和恢复函数 |
| 航空器 | `AircraftService.create/list/delete`，同步创建、物理删除 | 拆成计划、出现调度和删除 Saga；原类只保留兼容迁移期门面 |
| 指令 | `InstructionParser.parse`、`InstructionService.create/list`、`InstructionProgressService.evaluate` | 拆成目录、解析、鉴权、调度、派发、确认和进度聚合；删除旧 `PENDING/WAITING` 语义 |
| Adapter | `SimulationGateway` + Protocol 1.0 REQ/REP | 建立 Protocol 2.0 DEALER/ROUTER + PUB/SUB、序号、幂等缓存和快照修复 |
| SSE | `EventStreamService` 内存 emitter | 改为 MySQL 可靠事件投影 + 终端投递游标；动态帧独立覆盖 |
| 工作台 | 单一 `store.ts` 和 `/api/v1` `api.ts` | 按领域拆 store/api，统一 revision、幂等键、游标和只读状态 |
| 数据库 | Flyway `V1`～`V7` | 已执行迁移只读；从 `V8` 增量迁移并保留现有数据 |
| 启动 | `StartupReset.run` 会重置演示状态 | 第二版禁用并最终删除，改为 `RecoveryCoordinator.recoverAtStartup` |

### 2.2 代码组织目标

后端仍采用 Java 8、Spring Boot 2.7、MyBatis，新增代码按领域放置：

```text
org.bluesky.training
├─ common           # requestId、错误、revision、幂等、时钟、调用方上下文
├─ exercise         # 训练组状态机、终端、引擎实例
├─ reference        # 固定快照、资源读取、磁差和单位
├─ aircraft         # 航空器、计划、出现调度、删除 Saga
├─ assignment       # 责任分配与移交
├─ instruction      # 指令聚合、解析、调度、引导目标、复合指令
├─ report           # 命令报告、飞行报告
├─ collaboration    # 脚本与消息
├─ faketarget       # 两类假目标
├─ display          # 屏幕方案与标牌布局
├─ event            # 可靠业务事件和动态帧
├─ adapter          # Protocol 2.0 传输、Outbox 派发、状态投影
├─ archive          # 状态段、检查点、恢复
└─ workstation      # v2 bootstrap 投影
```

前端目标目录：`frontend/src/api`、`frontend/src/stores`、`frontend/src/components`、`frontend/src/features`；Python Adapter 在现有 `training_adapter` 下新增 `protocol_v2.py`、`guidance/`、`recovery.py`，迁移完成后删除 Protocol 1.0 入口。

## 3. 总体执行图与质量门

```text
P00 契约定桩
 └─P01 数据与通用底座
    ├─P02 身份与权限
    ├─P03 参考快照
    └─P04 Protocol 2.0 / 引擎实例
       └─P05 训练生命周期
          ├─P06 可靠事件 / bootstrap
          ├─P07 航空器与延迟出现
          │  └─P08 责任分配与移交
          │     └─P09 指令内核
          │        ├─P10 基础指令
          │        ├─P11 航路导航
          │        ├─P12 程序约束
          │        ├─P13 起飞/进近/复飞
          │        ├─P14 SSR/NSPEED/特情 profile
          │        └─P15 删除 Saga
          ├─P16 假目标
          ├─P17 报告/脚本/消息
          └─P18 显示与工作台
P19 归档恢复建立在 P04～P18 上
P20 安全、可观测性、性能和最终迁移横贯全部计划
```

注：上图为结构分组，不是完整依赖图；实际依赖还包括 P16 复用 P07/P15、P17 报告依赖 P09、P18 依赖 P06/P07/P09。执行顺序以第 26 节 Wave 清单为准。

每合并一个 P 编号执行以下基础门禁：

```powershell
Set-Location bluesky-master/training-platform
mvn test
Set-Location frontend
npm test -- --run
Set-Location ../..
python -m unittest discover -s tests/training_adapter -p "test_*.py"
```

涉及真实 BlueSky 的测试另运行带 `real_engine` 标记的场景；涉及 MySQL 特性的测试运行 Testcontainers MySQL，不得只用 H2 证明约束正确。

## 4. 数据库迁移执行计划

| 迁移 | 主要对象 | 前置验证函数 | 迁移后验证测试 |
|---|---|---|---|
| `V8__v2_common_identity_and_revision.sql` | UUID/revision 审计列、终端频率/单位/启用列、`trusted_caller_binding`、`idempotency_record`、`audit_record` | `V2UpgradeGuard.assertNoActiveExercise()` | `V8MigrationTest.givenV1DataWhenMigratedThenIdsAndRevisionsArePreserved` |
| `V9__v2_exercise_engine_reference.sql` | 扩展 `exercise_group/workstation_terminal`、`engine_instance`、`reference_snapshot`、资源 manifest | `LegacyDataBackfill.backfillDefaultTerminalFrequency()` | `V9MigrationTest.givenLegacyDefaultGroupWhenMigratedThenReferenceAndEngineFieldsAreNullableButValid` |
| `V10__v2_aircraft_plan_assignment.sql` | 扩展航空器、`flight_plan`、`flight_plan_leg`、`aircraft_assignment`、`aircraft_handover` | `LegacyAircraftMigrator.createInitialPlanVersion()` | `V10MigrationTest.givenLegacyAircraftWhenMigratedThenCurrentAssignmentIsUnique` |
| `V10_1__replace_legacy_callsign_uniqueness.java` | 按 MySQL/H2 方言移除 V1 永久呼号唯一约束，启用 V10 的活动呼号键 | `migrate()` | `V10MigrationTest.givenDeletedAircraftWhenCallsignReusedThenAllowedButActiveDuplicateRejected` |
| `V11__v2_instruction_guidance_reports.sql` | 重建 v2 指令状态字段、`instruction_blocker`、`guidance_target`、`composite_instruction_child`、报告表 | `LegacyInstructionMigrator.mapTerminalStates()` | `V11MigrationTest.givenLegacyInstructionsWhenMigratedThenNoWaitingOrPendingStateRemains` |
| `V12__v2_outbox_events_reports.sql` | `business_event`、`terminal_event_delivery`、`sse_stream_epoch` | `EventSequenceBackfill.initializeGroupSequence()` | `V12ReliableEventMigrationTest.givenConcurrentAllocationWhenCommittedThenSequencesAreUnique` |
| `V13__v2_fake_script_message_display.sql` | 假目标、脚本、消息、显示方案、标牌布局 | `DisplayProfileBackfill.createDefaultProfile()` | `V13MigrationTest.givenLegacyColorsWhenMigratedThenDefaultProfilePreservesValues` |
| `V14__v2_special_profile_checkpoint.sql` | 特情 profile、检查点、状态段、清理任务元数据 | `ProfileSchemaBootstrap.installSchemaVersions()` | `V14MigrationTest.givenPublishedProfileWhenUpdatedThenDatabaseRejectsMutation` |
| `V15__v2_constraints_and_v1_retirement.sql` | 最终非空、唯一键、CHECK、索引；删除 v1 临时兼容列 | `V2CutoverGuard.assertBackfillComplete()` | `V15MigrationTest.givenInvalidEnumOrDuplicateCurrentKeyWhenInsertedThenDatabaseRejectsIt` |

执行规则：每个迁移先写 H2 结构测试，再写 MySQL Testcontainers 测试；数据回填必须可重跑验证但 Flyway 文件本身不可重写。`V15` 只能在前端、API、Adapter 和恢复测试全部通过后执行。

## 5. P00：机器可读契约与测试基础设施

### 5.1 生产与工具函数

| 文件/类 | 函数 | 责任 |
|---|---|---|
| `contract/ContractCatalog.java` | `loadOpenApi()`、`loadSseSchema()`、`loadAdapterSchema()`、`loadCommandCatalog()` | 测试期加载四类契约 |
| `contract/ContractConsistencyValidator.java` | `validateEnums()`、`validateErrorCodes()`、`validateCommandTypes()`、`validateAdapterMessages()` | 检查详细设计中固定枚举的生成物一致性 |
| `testsupport/V2FixtureFactory.java` | `readyGroup()`、`runningGroup()`、`terminal()`、`activeAircraft()`、`instruction()` | 生成最小合法领域夹具 |
| `testsupport/MutableSimulationClock.java` | `nowSeconds()`、`advanceSeconds()`、`freeze()`、`resume()` | 隔离系统时间和仿真时间 |
| `testsupport/AdapterProtocolFixture.java` | `request()`、`response()`、`event()`、`withSequence()` | Java/Python 共用协议向量 |
| `scripts/verify_v2_contracts.py` | `load_documents()`、`validate_json_schemas()`、`compare_enum_sets()`、`main()` | CI 中执行跨语言契约校验 |

### 5.2 Red → Green → Refactor

1. Red：新增 `ContractConsistencyTest.givenV2ArtifactsWhenLoadedThenAllRequiredEnumsMatch`，因生成物不存在而失败。
2. Green：生成 `docs/contracts/openapi-v2.yaml`、`sse-event-v2.schema.json`、`adapter-protocol-v2.schema.json`、`command-catalog-v2.json` 的最小合法骨架。
3. Red：新增 `AdapterCrossLanguageVectorTest.givenSameEnvelopeWhenValidatedByJavaAndPythonThenResultMatches`。
4. Green：实现 Java fixture 与 Python schema 校验脚本，先覆盖 `HELLO`、`INSTRUCTION_APPLY`、`STATE_SNAPSHOT_CHUNK`。
5. Refactor：将枚举来源集中到命令目录/Schema 构建脚本，禁止 Java 与 Python 测试分别手抄另一套枚举。

### 5.3 执行和退出条件

执行 `mvn -Dtest=ContractConsistencyTest,AdapterCrossLanguageVectorTest test` 与 `python scripts/verify_v2_contracts.py`。四个契约文件可被解析、关键枚举完全一致、CI 能明确报告差异后完成。

## 6. P01：通用持久化、幂等、revision、Outbox 和错误模型

### 6.1 后端函数

| 类 | 函数签名 | 责任 |
|---|---|---|
| `RequestDigest` | `String sha256(String method, String canonicalPath, byte[] body)` | 规范请求摘要 |
| `IdempotencyScopeFactory` | `String create(CallerContext caller, String method, String canonicalPath, String key)` | 生成固定作用域 |
| `IdempotencyService` | `<T> IdempotentResult<T> execute(IdempotencyCommand command, Supplier<T> action)` | 首次执行、同载荷重放、异载荷冲突 |
| `IdempotencyRecordMapper` | `findForUpdate(scope)`、`insert(row)`、`complete(...)`、`deleteExpired(...)` | 持久化请求和完整响应 |
| `RevisionGuard` | `long requireIfMatch(String header)`、`void requireExpected(long expected, long actual)` | 428/409 统一处理 |
| `GroupSequenceAllocator` | `long next(String groupId)` | 事务内分配严格递增组序号 |
| `TransactionalOutboxService` | `enqueueBusinessEvent(...)`、`enqueueAdapterAction(...)` | 与业务变更同事务写 Outbox |
| `OutboxClaimService` | `List<OutboxRow> claimBatch(int limit)`、`markSent(...)`、`reschedule(...)`、`markFailed(...)` | `SKIP LOCKED` 或等价条件更新认领 |
| `V2ApiExceptionHandler` | `handleDomainException(...)`、`handleValidationException(...)`、`handleUnexpectedException(...)` | 输出 `code/message/fields/warnings/requestId` |
| `V2ApiResponseFactory` | `accepted(...)`、`created(...)`、`ok(...)`、`replayed(...)` | 保留首次 HTTP 状态和响应体 |
| `AuditService` | `recordMutation(...)`、`redactFingerprint(...)` | 写入调用方、终端、请求和幂等审计 |

### 6.2 测试与执行顺序

1. Red：`RevisionGuardTest.givenMissingIfMatchWhenParsingThenRevisionRequired`、`givenStaleRevisionWhenCheckingThenRevisionConflict`；Green：实现 `RevisionGuard`。
2. Red：`IdempotencyServiceTest.givenSameScopeAndDigestWhenRetriedThenStoredResponseIsReturned`、`givenSameKeyDifferentDigestThenConflict`、`givenConcurrentSameKeyThenActionRunsOnce`；Green：实现 mapper 和事务服务。
3. Red：`TransactionalOutboxTest.givenBusinessRollbackWhenEnqueueThenNoOutboxRemains`；Green：强制调用发生在同一 Spring 事务。
4. Red：`OutboxClaimServiceMySqlTest.givenTwoWorkersWhenClaimingThenRowsAreNotDuplicated`；Green：实现批次认领与退避。
5. Red：`V2ApiExceptionHandlerTest.givenDomainFailureThenStableErrorEnvelopeAndRequestIdAreReturned`；Green：实现错误映射。
6. Refactor：移除各 Service 中散落的异常字符串和手工 revision 比较；所有写接口使用统一拦截器/模板。

执行：`mvn -Dtest='RevisionGuardTest,IdempotencyServiceTest,TransactionalOutboxTest,OutboxClaimServiceMySqlTest,V2ApiExceptionHandlerTest' test`。并发 100 次只有一个业务副作用、回滚无孤立 Outbox 后完成。

## 7. P02：受信终端身份、服务身份与权限

### 7.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `TrustedCallerFilter` | `doFilterInternal(request, response, chain)` | 只读取代理注入且经过网关保护的身份属性 |
| `CallerContextResolver` | `resolveTerminal(request)`、`resolveService(request)` | 构造 `CallerContext`，拒绝混合身份 |
| `TrustedCallerBindingService` | `resolveByFingerprintDigest(...)`、`assertEnabled(...)`、`touchLastSeen(...)` | 校验证书绑定和启用状态 |
| `TerminalProvisioningService` | `createTerminal()`、`updateTerminal()`、`listByGroup()`、`bindCallerCertificate()` | 运维身份管理终端、组内频率唯一和受信绑定 |
| `TerminalAdminControllerV2` | `list()`、`create()`、`update()`、`bindCertificate()` | v2 终端管理接口，仅运维服务身份可调用 |
| `TerminalAccessPolicy` | `requireSameGroup(...)`、`requireResponsibleTerminal(...)`、`requireTerminalWrite(...)` | 终端通用权限 |
| `ServiceAccessPolicy` | `requireOrchestrator(...)`、`requireOperations(...)` | end、脚本入站、profile 管理权限 |
| `OperationPolicy` | `requireGroupStateAllows(...)`、`requireAircraftPhaseAllows(...)`、`requireEngineWritable(...)` | 聚合训练态、飞行阶段和引擎可写性 |

### 7.2 测试与执行顺序

1. Red：`TrustedCallerFilterMvcTest.givenSpoofedTerminalHeaderWithoutTrustedAttributeThenForbidden`。
2. Green：过滤器忽略浏览器自填 `X-Terminal-Id`，只接受反向代理认证属性。
3. Red：`TerminalAccessPolicyTest.givenTerminalFromOtherGroupThenTerminalNotInGroup`、`givenNonResponsibleTerminalThenAircraftNotAssigned`。
4. Green：实现终端、训练组、责任分配三重校验。
5. Red：`ServiceAccessPolicyTest.givenTerminalCertificateWhenEndingExerciseThenForbidden`、`givenOrchestratorWhenSubmittingFlightInstructionThenForbidden`。
6. Green：实现用途隔离；审计中只保存证书指纹摘要。
7. Red：`TerminalProvisioningServiceTest.givenOpsIdentityWhenCreatingTerminalThenFrequencyIsUniqueInGroup`、`givenTerminalCertificateWhenManagingTerminalsThenForbidden`。
8. Green：终端与绑定同事务写入；换绑后旧证书的后续请求返回 403。
9. Refactor：Controller 不再解析身份 Header，统一从 `CallerContext` 参数解析器取得。

执行 MockMvc 权限矩阵和 AT-03 的伪造 Header 子场景。任何浏览器字段都不能改变服务端身份判定后完成。

## 8. P03：参考快照、单位与磁差

### 8.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `ReferenceSnapshotService` | `publish(...)`、`listPublished()`、`pinToGroup(...)`、`verifyPinnedSnapshot(...)`、`diffPlanReferences(...)`、`readResource(...)` | 发布、列出、固定、校验和按组读取快照 |
| `ReferenceManifestValidator` | `validateSchemaVersion(...)`、`validateChecksums(...)`、`validateReferentialIntegrity(...)` | 原子校验 manifest 和资源 |
| `ReferenceSnapshotStore` | `copyPublishedSnapshot(...)`、`openResource(...)`、`calculateManifestChecksum(...)` | 平台只读副本 |
| `RuntimeReferenceCatalog` | 保留 `resolve/searchAirports/searchWaypoints/expandRoute`；新增 `resolveRunway()`、`resolveProcedure()`、`resolveNavaid()`、`resolveHoldingPattern()`、`resolveInitialStateTemplate()` | 从组固定快照解析稳定 ID |
| `UnitConverter` | `feetToMeters()`、`metersToFeet()`、`knotsToMetersPerSecond()`、`fpmToMetersPerSecond()`、`flightLevelToFeet()` | 唯一单位转换入口 |
| `MagneticVariationService` | `variationDeg(lat, lon, utc)`、`trueToMagnetic(...)`、`magneticToTrue(...)` | 使用快照固定磁差模型 |
| `ReferenceSnapshotController` | `pinSnapshot()`、`getResource()` | v2 管理与资源接口 |

### 8.2 Adapter 函数

- `BlueSkyEngine.load_reference_snapshot(payload)`：校验并原子替换运行目录。
- `RuntimeReferenceData.validate_manifest(payload)`、`calculate_checksum()`、`resolve_procedure()`、`resolve_runway()`、`resolve_navaid()`。
- `AdapterProtocolV2._handle_reference_snapshot_load(envelope)`：返回同一 manifest checksum。

### 8.3 TDD 执行

1. Red：`ReferenceManifestValidatorTest.givenChecksumMismatchThenSnapshotIsRejectedAtomically`；Green：校验全部通过后才切换 catalog。
2. Red：`ReferenceSnapshotServiceTest.givenReadyGroupAndPublishedSnapshotWhenPinnedThenImmutableCopyIsUsed`；Green：复制资源并写组 revision。
3. Red：`givenOrchestratorWhenListingPublishedSnapshotsThenOnlyPublishedWithChecksumAreReturned`；Green：实现 `listPublished`，供固定快照前查询 snapshotId。
4. Red：`givenExistingPlanMissingInNewSnapshotWhenSwitchingThenFullDiffIsReturned`；Green：实现 `diffPlanReferences`。
5. Red：`UnitConverterTest` 和 `MagneticVariationServiceTest` 覆盖边界、往返误差及区域外拒绝；Green：实现纯函数。
6. Red：Java/Python `REFERENCE_SNAPSHOT_LOAD` checksum 契约测试；Green：实现 Adapter 装载。
7. Refactor：现有 `MapDataService` 仅做快照投影，移除运行时依赖数据准备服务的路径。

退出条件：数据准备服务离线时已固定组仍可检索资源；checksum 不一致时不能 START 或创建计划；三种单位输入产生相同规范值。

## 9. P04：Adapter Protocol 2.0 与引擎实例

### 9.1 Java 函数

| 类 | 函数 | 责任 |
|---|---|---|
| `ProtocolEnvelopeCodec` | `encode(ProtocolEnvelope)`、`decode(byte[])`、`validateSize(...)` | 2.0 信封和 1 MiB 限制 |
| `ProtocolSequenceTracker` | `nextOutbound()`、`acceptInbound(sequence)`、`resetForInstance(...)` | 按 control/state 逻辑通道分别去重、检测缺口和处理实例切换 |
| `AdapterControlClient` | `connect(...)`、`hello(...)`、`send(...)`、`retrySameRequest(...)`、`queryInstructionStatus(...)` | DEALER 请求关联和重试 |
| `AdapterStateSubscriber` | `subscribe(...)`、`onFrame(...)`、`requestRepairOnGap(...)` | SUB 状态接收和缺口修复 |
| `SnapshotChunkAssembler` | `acceptChunk(...)`、`isComplete()`、`assembleAndVerify()`、`expireIncomplete()` | 分片快照重组 |
| `EngineInstanceService` | `createForGroup(...)`、`markConnected(...)`、`markDegraded(...)`、`markDisconnected(...)`、`markStopped(...)`、`assertCurrentInstance(...)` | 每组当前实例管理 |
| `EngineHealthMonitor` | `onHeartbeat(...)`、`detectTimeouts()`、`requestReconciliation(...)` | 3 秒/5 秒健康转换 |
| `AdapterOutboxDispatcher` | `dispatchPending()`、`handleResponse(...)`、`reconcileUnknownResult(...)` | 发送 Adapter Outbox 并确认 |

现有 `SimulationGateway` 在迁移期改成只委托上述客户端；`ZeroMqSimulationGateway`、`ZeroMqStateSubscriber` 的 1.0 编解码不继续扩展，v2 切换完成后删除。

### 9.2 Python 函数

| 模块/类 | 函数 | 责任 |
|---|---|---|
| `protocol_v2.ProtocolEnvelope` | `from_dict()`、`to_dict()`、`validate()` | 信封验证 |
| `protocol_v2.AdapterProtocolV2` | `handle()`、`_dispatch()`、`_success()`、`_failure()` | 2.0 消息路由 |
| `protocol_v2.SequenceTracker` | `next_outbound()`、`accept_inbound()` | 双向序号 |
| `protocol_v2.IdempotencyResultCache` | `execute()`、`get()`、`purge_expired()`、`payload_checksum()` | 同键同结果、异载荷拒绝 |
| `runner_v2.AdapterRuntime` | `run_control_loop()`、`publish_state()`、`publish_heartbeat()`、`shutdown()` | ROUTER/PUB 事件循环 |
| `engine_v2.EngineV2Handlers` | `handlers()`、`load_reference_snapshot()`、`apply_aircraft()`、`aircraft_exists()` | 将 2.0 消息接入隔离 BlueSky 引擎；已存在实体一致则幂等成功、不一致则拒绝 |
| `runner_v2` 进程入口 | `build_parser()`、`main()` | 使用显式 group/instance/endpoint 启动 2.0 Runtime，并持续驱动引擎更新与心跳 |
| `snapshot.StateSnapshotBuilder` | `build()`、`chunk()`、`checksum()` | 全量快照与分片 |

### 9.3 TDD 执行

1. Red：Java `ProtocolEnvelopeCodecTest` 与 Python `test_protocol_v2_envelope.py` 同时运行共享 JSON 向量。
2. Green：实现信封验证、大小限制和主版本拒绝。
3. Red：`ProtocolSequenceTrackerTest.givenDuplicateThenIgnored`、`givenGapThenOutOfSync`、`givenNewInstanceThenSequenceRestarts`。
4. Green：实现序号状态；缺口只触发快照修复，不重放 PUB 帧。
5. Red：`AdapterControlClientTest.givenAckTimeoutWhenRetriedThenRequestIdAndKeyStaySame`；Green：最多 3 次且总窗口 ≤5 秒。
6. Red：Python `test_same_key_different_payload_is_rejected_without_side_effect`；Green：实现结果缓存。
7. Red：`SnapshotChunkAssemblerTest` 覆盖乱序、重复、缺片、checksum 错误；Green：重组与整组重取。
8. Refactor：协议常量由 `adapter-protocol-v2.schema.json` 校验，禁止在多个 switch 中重复消息清单。

退出条件：两组并行实例互不收消息；迟到旧实例响应被拒绝；心跳、断连、重连和状态缺口均有确定状态与测试。

## 10. P05：训练组生命周期与多组隔离

### 10.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `ExerciseGroupStateMachine` | `requestStart()`、`confirmStarted()`、`failStart()`、`requestPause()`、`confirmPaused()`、`requestResume()`、`confirmResumed()`、`requestEnd()`、`confirmEnded()`、`enterRecovering()`、`failRecovery()` | 唯一状态转换定义 |
| `ExerciseGroupService` | 改造 `start/pause/resume` 为 `requestStart/requestPause/requestResume`；新增 `requestEnd()`、`onAdapterLifecycleResult()` | revision 校验、写过渡状态和 Outbox |
| `ExerciseStartCoordinator` | `validateSnapshot()`、`createEngine()`、`loadReference()`、`loadDuePlans()`、`completeStart()`、`compensateFailedStart()` | STARTING 编排 |
| `ReferenceSnapshotService` | `verifyPinnedSnapshotIfPresent()`、`readOptionalResource()` | 已固定快照的计划写入完整性门禁；读取可选初始状态模板 |
| `InitialStateTemplateService` | `completeInitialState(..., groupId)`、`findTemplate()` | 优先按机型/起飞机场读取固定快照模板，缺失时使用兼容默认模板 |
| `ExerciseEndCoordinator` | `freezeClock()`、`cancelOutstandingInstructions()`、`writeFinalCheckpoint()`、`generateFinalReports()`、`stopEngine()`、`completeEnd()` | ENDING 编排 |
| `SimulationClockService` | `currentTime()`、`advanceFromFrame()`、`freeze()`、`resume()` | 持久化组仿真时间 |
| `ExerciseGroupProvisioningService` | `createGroup()`、`listGroups()`、`get()` | 运维身份创建与查询训练组，初始 READY |
| `ExerciseGroupAdminControllerV2` | `list()`、`create()` | v2 训练组管理接口，仅运维服务身份可调用 |
| `ExerciseGroupActionControllerV2` | `start()`、`pause()`、`resume()`、`end()` | `/api/v2/.../actions/*` |

### 10.2 TDD 执行

1. Red：`ExerciseGroupStateMachineTest` 对设计中的每一条合法边和每一条非法边参数化测试。
2. Green：实现纯状态机，不调用网络和数据库。
3. Red：`ExerciseGroupServiceTest.givenStartWhenReadyThenStartingAndOutboxCommitTogether`、`givenDuplicateStartThenSameOperationReturned`、`givenStaleRevisionThenConflict`。
4. Green：Service 只做事务，Adapter 由 dispatcher 异步调用。
5. Red：`ExerciseStartCoordinatorTest.givenReferenceLoadFailureThenEngineIsReconciledAndGroupReturnsReadyWithReason`；Green：按逆序补偿。
6. Red：`ExerciseEndCoordinatorTest.givenRunningGroupWhenEndThenClockFreezesInstructionsCancelCheckpointAndStopAreOrdered`；Green：实现可重入步骤。
7. Red：`MultiGroupIsolationTest.givenOneEngineFailsThenOtherGroupKeepsRunning`；Green：移除全部默认组特判。
8. Red：`ExerciseGroupProvisioningServiceTest.givenOpsIdentityWhenCreatingGroupThenGroupIsReadyAndStartRequiresSnapshotAndTerminals`；Green：创建组初始 READY，未固定快照或组内无启用终端时 start 拒绝。
9. Refactor：删除 `ExerciseGroupService.requireDefaultGroup()`；Controller 返回 202 操作信封。

退出条件：所有中间状态可查询；PAUSE/RESUME 确认不猜测；恢复后只能到 PAUSED；ENDED 永久只读。

## 11. P06：可靠业务事件、动态帧与 v2 bootstrap

### 11.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `BusinessEventService` | `append(...)`、`fanOutToAuthorizedTerminals(...)` | 生成 groupSequence 和终端投递 |
| `TerminalDeliveryService` | `allocateDeliverySequence(...)`、`loadAfter(...)`、`oldestRetainedSequence(...)`、`purgeEligible(...)` | 可靠窗口与连续序列 |
| `StreamEpochService` | `currentEpoch()`、`rebuildProjection()` | 重启保持 epoch，显式重建才更换 |
| `EventCursorCodec` | `encode()`、`decode()`、`validateTerminal()` | 固定 eventId 格式 |
| `ReliableEventStreamService` | `connect(...)`、`replayAfterCursor(...)`、`pushReliable(...)`、`disconnectSlowConsumer(...)` | Last-Event-ID 续传与背压 |
| `DynamicFrameService` | `publishLatest(...)`、`latestForGroup()` | 只保留最新 1 Hz 状态帧 |
| `WorkstationSnapshotService` | `bootstrap(CallerContext, terminalId)`、`readProjectionAtSequence(...)` | 原子读取投影和 snapshotSequence |
| `WorkstationControllerV2` | `bootstrap()` | v2 bootstrap |
| `EventStreamControllerV2` | `events()` | v2 SSE、游标过期返回 409 |

### 11.2 前端函数

- `api/httpClient.ts`：`requestJson()`、`newIdempotencyKey()`、`withIfMatch()`、`parseApiError()`。
- `api/events.ts`：`connectEventStream()`、`parseEventEnvelope()`、`isNextDelivery()`。
- `stores/workstation.ts`：`bootstrap()`、`connectEvents()`、`applyReliableEvent()`、`applyDynamicFrame()`、`rebootstrapAfterCursorExpired()`、`scheduleReconnect()`、`disconnect()`。
- `stores/eventCursor.ts`：`loadCursor()`、`saveCursor()`、`clearCursor()`、`hasApplied()`。

### 11.3 TDD 执行

1. Red：MySQL `TerminalDeliveryServiceTest.givenConcurrentEventsThenGroupAndDeliverySequencesRemainStrictlyIncreasing`。
2. Green：事务分配 groupSequence 和每终端 deliverySequence。
3. Red：`WorkstationSnapshotServiceTest.givenConcurrentMutationWhenBootstrappingThenSnapshotSequenceClosesTheGap`；Green：同事务读投影与最大序列。
4. Red：`ReliableEventStreamServiceTest` 覆盖正常续传、重复游标、错误终端、窗口过期、2000 条/10 MiB 慢消费者。
5. Green：实现数据库 replay 和断开策略；原 `EventStreamService.publishAfterCommit` 不再承担可靠事件。
6. Red：前端 `workstationStore.spec.ts` 覆盖事件去重、缺口不乱序应用、游标过期重新 bootstrap、动态帧覆盖。
7. Green：拆分当前 `store.ts`；所有事件按 eventId 应用，不按时间戳排序。
8. Refactor：业务事件与动态帧使用两个缓冲通道，避免动态帧阻塞可靠事件。

退出条件：Java 重启后 epoch 不变；快照与增量无窗口；浏览器断网重连无重复业务副作用；正文消息只投递目标终端。

## 12. P07：航空器、版本化飞行计划与延迟出现

### 12.1 后端函数

| 类 | 函数 | 责任 |
|---|---|---|
| `AircraftApplicationService` | `createPlan()`、`get()`、`listByGroup()`、`patchPlannedFlightPlan()`、`cancelPlannedAircraft()` | 计划资源事务入口；取消未出现计划委托 P15 删除入口，本地直达 `DELETED` |
| `AircraftValidator` | `normalizeCallsign()`、`validateIcao24()`、`validatePlannedSquawk()`、`validateInitialState()`、`collectWarnings()` | 规范化、唯一和警告 |
| `InitialStateTemplateService` | `completeInitialState(..., groupId)`、`findTemplate()`、`resolveAircraftTemplate()`、`resolveAirportTemplate()` | 优先从固定快照模板补齐并返回最终初态 |
| `FlightPlanService` | `createVersion()`、`copyActiveVersion()`、`replaceLegs()`、`validateRouteContinuity()`、`findVersion()` | 只增版本、旧版本只读 |
| `AircraftAppearanceScheduler` | `scanDuePlans()`、`requestAllDuePlans()`、`requestAppearance()`、`hasCreateRequested()`、`freezeWhenPaused()` | STARTING 批量装载、RUNNING 到时条件更新与 AIRCRAFT_APPLY；START 必须等待全部确认 |
| `AircraftAppearanceWorker` | `scanRunningGroups()` | 从独立调度 Bean 调用事务代理，持续扫描 RUNNING 组，禁止同类自调用绕过事务 |
| `AircraftLifecycleService` | `markCreateRequested()`、`confirmActive()`、`markCreateFailed()`、`retryCreate()` | 创建状态机和实际出现时间 |
| `AircraftAdapterPayloadFactory` | `build()` | 从航空器和最新计划版本生成完整可重放 `AIRCRAFT_APPLY` 载荷，禁止只发送数据库 ID |
| `AircraftStateProjectionService` | `applyStateFrame()`、`applyFlightPhaseChanged()`、`rejectStaleInstanceFrame()` | Adapter 动态状态投影 |
| `AircraftControllerV2` | `create()`、`list()`、`get()`、`patchFlightPlan()` | v2 资源接口 |

### 12.2 Adapter 函数

- `BlueSkyEngine.apply_aircraft(payload, idempotency_key)`：已存在且状态一致返回原结果，不重复创建。
- `BlueSkyEngine.aircraft_exists(payload)`：按平台 ID/呼号/ICAO24 对账。
- `BlueSkyEngine._normalize_initial_state()`、`_apply_flight_plan()`、`_aircraft_result_checksum()`。
- `AdapterProtocolV2._handle_aircraft_apply()`、`_handle_aircraft_exists_get()`。

### 12.3 前端函数

- `api/aircraft.ts`：`listAircraft()`、`createAircraft()`、`getAircraft()`、`patchFlightPlan()`。
- `features/aircraft/aircraftForm.ts`：保留 `validateAircraftForm/buildAircraftPayload`；新增 `normalizeCallsign()`、`validateAppearanceTime()`、`mapServerFieldErrors()`。
- `stores/aircraft.ts`：`upsertAircraft()`、`removeDeletedAircraft()`、`applyLifecycleEvent()`、`selectAircraft()`。
- `CreateAircraftDialog.vue`：保留拖拽函数；将 `submit()` 改为生成幂等键、展示规范化回包与重复 Squawk 警告。

### 12.4 TDD 执行

1. Red：`AircraftValidatorTest` 覆盖呼号格式、ICAO24、八进制 Squawk、重复 Squawk警告与重复呼号拒绝。
2. Green：实现无数据库纯校验；唯一性放数据库约束并映射稳定错误码。
3. Red：`AircraftApplicationServiceTest.givenMissingInitialFieldsAndTemplatesWhenCreatingThenInitialStateIncomplete`；Green：实现模板补齐。
4. Red：`FlightPlanServiceTest.givenRouteChangeThenNewVersionAndOldVersionRemainReadOnly`；Green：一次事务写计划与航段。
5. Red：`AircraftAppearanceSchedulerTest.givenFutureAppearanceWhenPausedThenCountdownDoesNotAdvance`、`givenDuePlanWhenRunningThenOneWorkerClaimsItOnce`。
6. Green：按仿真时钟扫描和条件更新；创建事务只写 PLANNED，不提前调用 Adapter。
7. Red：Java/Python `givenRetryAfterLostAckThenBlueSkyEntityIsNotDuplicated`；Green：Adapter 幂等 apply + EXISTS 对账。
8. Red：前端表单和生命周期组件测试；Green：显示 PLANNED/CREATE_REQUESTED/CREATE_FAILED/ACTIVE。
9. Refactor：删除 `AircraftService.requireDefaultGroup()` 和同步 `simulationGateway.createAircraft()` 路径；物理 delete 留到 P15 删除。

退出条件：READY 可预建、STARTING 装载所有到期计划、暂停冻结、失败可重试、活动航空器不能 PATCH 动态计划。

## 13. P08：责任分配与按频率直接移交

### 13.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `AircraftAssignmentService` | `createInitialAssignment()`、`findCurrentForUpdate()`、`requireCurrentTerminal()`、`listByGroup()` | 当前分配唯一性 |
| `HandoverService` | `handoverByFrequency()`、`resolveTargetTerminal()`、`lockAircraftAndAssignment()`、`completeHandover()` | 原子结束旧分配并建立新分配 |
| `HandoverResponseFactory` | `build()`、`activeInstructionSummaries()` | 返回修订号和完整活动/等待摘要 |
| `HandoverControllerV2` | `handover()`、`assignments()` | v2 移交和查询 |
| `InstructionOwnershipPolicy` | `canView()`、`canOverride()`、`canCancel()` | 保留原来源审计，以当前责任决定后续操作 |

### 13.2 前端函数

- `api/assignments.ts`：`handoverAircraft()`、`listAssignments()`。
- `stores/assignments.ts`：`applyHandoverCompleted()`、`isResponsible()`、`isReadOnly()`。
- `HandoverDialog.vue`：`validateFrequency()`、`submitHandover()`、`applyConflictRefresh()`。

### 13.3 TDD 执行

1. Red：`AircraftAssignmentServiceTest.givenAircraftCreatedThenExactlyOneCurrentAssignmentExists`。
2. Green：航空器创建事务同时写初始分配和 current key。
3. Red：`HandoverServiceMySqlTest.givenTwoConcurrentHandoversThenOnlyOneCommits`、`givenDisabledOrOtherGroupTargetThenRejected`。
4. Green：固定锁顺序为航空器行 → 当前分配行，唯一 current key 兜底。
5. Red：`givenCompletedHandoverThenSourceReadOnlyTargetWritableAndExistingInstructionsRemain`。
6. Green：只变业务责任和 revision，不触碰 guidance/队列。
7. Red：前端双席事件测试，源席收到事件立即禁用写控件，目标席无刷新获得控制。
8. Refactor：所有指令、删除、计划修改的权限统一调用 `requireCurrentTerminal()`。

退出条件：并发移交没有双重控制窗口；旧指令保留 `sourceTerminalId`；新责任席可覆盖或取消。

## 14. P09：统一指令内核、调度、阻塞、引导目标与复合指令

### 14.1 领域函数

| 类 | 函数 | 责任 |
|---|---|---|
| `InstructionCatalog` | `definitionFor(type)`、`allDefinitions()`、`conflictKeyFor(command)`、`affectedChannelsFor(command)` | 机器可读命令定义 |
| `InstructionParserV2` | `parse(text, context)`、`tokenize()`、`normalizeAlias()` | 将文本委托给类型 parser |
| `InstructionApplicationService` | `submit()`、`validateRequestMode()`、`createAggregate()`、`cancel()`、`get()`、`list()` | 指令事务入口 |
| `InstructionStateMachine` | `validate()`、`block()`、`beginDispatch()`、`confirmApplied()`、`complete()`、`replace()`、`fail()`、`timeOut()`、`cancel()`、`reject()` | 唯一状态转换 |
| `InstructionBlockerService` | `recalculate()`、`add()`、`remove()`、`releaseWhenClear()`、`cancelSuccessors()` | 四类阻塞原因管理 |
| `InstructionQueueService` | `allocateSequence()`、`linkPredecessor()`、`clearWaitingByReplace()`、`releaseNext()` | 每冲突键严格串行 |
| `DispatchSlotService` | `claim()`、`release()`、`isOccupied()` | 每航空器+冲突键单一在途下发 |
| `InstructionDispatchService` | `dispatchReady()`、`buildApplyPayload()`、`handleApplied()`、`handleRejected()`、`handleAckTimeout()` | 技术确认和三次重试结果处理 |
| `GuidanceTargetService` | `createPending()`、`activate()`、`supersede()`、`clear()`、`fail()` | 与指令终态解耦的目标生命周期 |
| `CompositeInstructionService` | `createChildren()`、`applyOverridePolicy()`、`aggregateParentState()`、`terminateAllChildren()` | 多通道原子指令与父状态聚合 |
| `InstructionCompletionService` | `evaluateEvidence()`、`openStableWindow()`、`resetStableWindow()`、`completeWhenStable()`、`expireBudgets()` | 容差、稳定窗和仿真预算 |
| `InstructionControllerV2` | `submit()`、`list()`、`get()`、`cancel()` | v2 指令接口 |

### 14.2 Adapter 函数

- `BlueSkyEngine.apply_instruction(payload, idempotency_key)`、`cancel_instruction()`、`instruction_status()`。
- `GuidanceRegistry.apply_atomically()`、`replace_targets()`、`cancel_targets()`、`snapshot()`。
- `GuidanceStateMachine.update(sim_time)`、`phase()`、`completion_evidence()`、`failure()`。
- `InstructionEventPublisher.phase_changed()`、`guidance_failed()`。

### 14.3 TDD 执行

1. Red：`InstructionStateMachineTest` 参数化验证全部合法/非法边，特别禁止 `WAITING/PENDING`。
2. Green：实现纯状态机和原因码约束。
3. Red：`InstructionStateMachineTest.givenAdapterlessBusinessFieldCommandWhenValidatedThenExecutesDirectlyWithoutDispatching`；Green：纯业务字段指令 `VALIDATED → EXECUTING` 同事务直达并可直接完成，不占在途下发占位。
4. Red：`InstructionQueueServiceTest` 覆盖 REPLACE、AFTER_COMPLETION、不同通道并行、前置非 COMPLETED 时后继取消。
5. Green：先写 RECEIVED→VALIDATED，再计算 blockers；数据库 sequence 决定顺序。
6. Red：`InstructionDispatchServiceTest.givenNewApplyRejectedThenOldTargetAndQueueStayUntouched`。
7. Green：只有 `INSTRUCTION_APPLIED` 事务中才激活新 target、替代旧 target、清队列。
8. Red：`GuidanceTargetServiceTest.givenInstructionCompletedThenTargetRemainsActiveUntilSuperseded`。
9. Green：完成与目标保持分离。
10. Red：`CompositeInstructionServiceTest` 覆盖 TAKEOFF 部分接管、ILS/MISSED 整体终止、ALL_OR_NOTHING/PARTIAL。
11. Green：单个 payload 带完整 `affectedChannels`，Adapter 单事件循环原子采用。
12. Red：`InstructionCompletionServiceTest` 覆盖稳定窗抖动重置、暂停预算冻结、技术预算不冻结、最大业务预算。
13. Refactor：现有 `InstructionService` 退化为迁移门面；`InstructionProgressService.evaluate` 只用于简单证据适配，复杂阶段必须取 Adapter 事件。

### 14.4 前端函数

- `api/instructions.ts`：`submitInstruction()`、`listInstructions()`、`getInstruction()`、`cancelInstruction()`。
- `stores/instructions.ts`：`submit()`、`applyInstructionEvent()`、`waitingByAircraft()`、`selectInstruction()`。

退出条件：同冲突键没有双重在途；拒绝新目标不破坏旧状态；完成后 guidance 继续；暂停可以同时保留多个 blocker。

## 15. P10：基础选机和飞行控制指令

### 15.1 解析与规范化函数

| 指令 | Java parser/normalizer | Adapter handler | 完成判据函数 |
|---|---|---|---|
| ACID | `AcidSelectionService.selectBySuffix()`、`findCandidates()` | 无 | 前端选择成功即完成，不创建指令 |
| HDG | `HeadingCommandParser.parse()`、`HeadingNormalizer.normalizeMagnetic()` | `apply_heading()` | `HeadingCompletion.isWithinOneDegreeForTwoSeconds()` |
| LEFT/RIGHT | `TurnCommandParser.parse()`、`TurnNormalizer.toDirectedHeading()` | 复用 `apply_heading()` | 复用 HDG |
| ALT | `AltitudeCommandParser.parse()`、`AltitudeNormalizer.toFeetMsl()` | `apply_altitude()` | `AltitudeCompletion.isStable()` |
| VS | `VerticalSpeedCommandParser.parse()`、`PerformanceGuard.limitVerticalSpeed()` | `apply_vertical_speed()` | 与 ALT 目标组合判定 |
| SPD | `SpeedCommandParser.parse()`、`SpeedNormalizer.toCasKnots()` | `apply_speed()` | `SpeedCompletion.isStable()` |
| MACH | `MachCommandParser.parse()`、`PerformanceGuard.validateMach()` | `apply_mach()` | `MachCompletion.isStable()` |

### 15.2 前端函数

- `features/command/commandInput.ts`：`submitTextCommand()`、`resolveAcidSelection()`、`mapInsertionModifiers()`。
- `features/command/quickControl.ts`：`buildHeadingCommand()`、`buildAltitudeCommand()`、`buildSpeedCommand()`、`resolveRatePreset()`、`isInstantControlAllowed()`；变化率档位固定为 `INSTANT/MAX/FAST/NORMAL/SLOW`。
- 保留 `commandKeys.insertionForEnter()`，但将结果映射为 `REPLACE/AFTER_COMPLETION`，删除 APPEND 语义。

厂商兼容语法由 `VendorAliasNormalizer.normalizeKeyword()`、`normalizeVerticalRateAlias()`、`normalizeOffsetAlias()` 处理；`CommandIntentRouter.route()` 将 `FRE` 路由到移交、将 `DEL` 路由到删除预览，两者绝不创建飞行指令。命令帮助、快捷键标签和 OpenAPI 示例由 `command-catalog-v2.json` 的 `CommandHelpGenerator.generate()` 生成。

### 15.3 TDD 执行

1. 为每个 parser 先写正常、边界、单位、非法 token 和厂商别名参数化测试。
2. 为 ACID 写 `givenUniqueLastFourThenSelect`、`givenUniqueLastThreeThenSelect`、`givenMultipleMatchesThenReturnCandidatesWithoutChangingSelection`。
3. 为 LEFT/RIGHT 写三位数绝对航向和非三位数相对角度测试。
4. 为 ALT/VS/SPD/MACH 写性能包线拒绝和 SI 转换协议向量。
5. Adapter 先用 fake traffic 测试目标设置，再用真实 BlueSky 测试完成证据。
6. 前端写 Enter/修饰键、选机歧义、错误字段回显和快速面板结构化请求测试。
7. 写别名矩阵测试：`LVL→ALT`、`SPEED→SPD`、`CR→VS+`、`DR→VS-`、`SSRCODE→SQK`、`FRE→handover`、空参数 `OFFSET→OFFSET CLR`、`R5→OFFSET R 5NM`、`HOLD L→ORBIT L`、`VOR T/F 后缀→IN/OUT`；明确断言 `ID` 不得归一化为 `IDENT`，未知命令拒绝，VOR 以普通航路点为目标时拒绝并提示 DCT。
8. 写 PCA 档位测试：未启用 `allowInstantPilotControl` 时禁用 INSTANT，其余档位仍经性能包线校验。
9. Refactor：`InstructionParser` 当前 `HDG/ALT/SPD/MACH` 分支迁至独立 parser，并由 `InstructionCatalog` 注册。

退出条件：所有规范值以 ft/kt/Mach/磁航向存平台，Adapter 边界唯一转换 SI；完成判据均使用稳定窗。

## 16. P11：航路与导航指令

### 16.1 函数级矩阵

| 功能 | Java 函数 | Python 状态机/函数 | 核心测试 |
|---|---|---|---|
| DCT | `DirectToParser.parse()`、`RouteCommandService.resolveDirectTarget()`、`LateralSuspendContextService.captureExitAnchor()` | `DirectToGuidance.apply()`、`update()`、`has_passed_target()`、`restore_route()` | 临时改变活动航路但不生成计划版本；航路内/外点、最后点、经过后完成 |
| RER | 前端 `routeEditSession.createCandidate()`、`validateCandidate()`、`discardCandidate()`、`confirmAsRte()`；后端只接收最终 RTE | 无，纯前端候选 | 候选不落业务计划、不产生指令 |
| RTE | `RouteParser.parse()`、`RouteCommandService.buildReplacementPlan()`、`commitAppliedPlanVersion()` | `RouteGuidance.validate_replacement()`、`apply_replacement_atomically()` | 任一航段非法则原航路不变 |
| RESUME | `ResumeParser.parse()`、`RouteCommandService.resolveResumePoint()` | `RouteResumeGuidance.apply()`、`update()` | 恢复活动版本，不猜旧版本 |
| ORBIT | `OrbitParser.parse()`、`OrbitCommandValidator.validate()` | `OrbitGuidance.enter()`、`update()`、`exit()`、`phase()` | 方向、稳定阶段、EXIT/覆盖 |
| HOLD | `HoldParser.parse()`、`HoldingPatternResolver.resolve()` | `HoldGuidance.enter()`、`join_leg()`、`update()`、`exit()` | 加入方向、航段计时、暂停冻结 |
| OFFSET | `OffsetParser.parse()`、`OffsetPathBuilder.build()` | `OffsetGuidance.intercept()`、`track()`、`exit()` | 左右偏置、截获、恢复 |
| VOR | `VorParser.parse()`、`NavaidResolver.resolveVor()` | `VorGuidance.intercept_radial()`、`track_radial()`、`exit()` | 台站类型、径向、磁差 |

### 16.2 TDD 执行

1. 每一行先写 Java parser/参考解析测试，确保错误在下发前发生。
2. 为 RTE 写 Java—Python 原子失败契约：Adapter 任一验证失败，resultChecksum 与旧航路一致。
3. 为复杂引导分别写 Python 状态机阶段测试；使用显式输入帧推进，不依赖 wall clock。
4. 写 `InstructionPhaseProjectionTest`，只接受 Adapter `INSTRUCTION_PHASE_CHANGED`，禁止 Java 依据位置重建阶段。
5. 写前端 RER 候选编辑、地图点选、确认/取消和结构化 RTE 请求测试。
6. 写真实 BlueSky DCT/RTE/ORBIT/HOLD/OFFSET/VOR 场景；记录阶段序列和完成证据。
7. Refactor：复用 `GuidanceStateMachine`、`RouteSnapshot` 和 `ExitCommandPolicy`，不合并各指令独有的阶段枚举。

退出条件：每个指令都有解析、结构化 API、原子采用、阶段、退出/覆盖、恢复快照和真实引擎测试。

## 17. P12：SID、STAR、航段约束和程序复飞

### 17.1 函数级矩阵

| 功能 | Java 函数 | Adapter 函数 | 测试重点 |
|---|---|---|---|
| SIDSTAR | `ProcedureParser.parseSidStar()`、`ProcedureCommandService.resolveSidStar()`、`FlightPlanService.createVersionForProcedure()` | `ProcedureGuidance.apply_sid()`、`apply_star()`、`activate_leg()` | 按机场、跑道、方向区分 SID/STAR；连续性、原子采用 |
| P_LEVEL | `LegConstraintParser.parseLevel()`、`LegConstraintService.applyAltitudeConstraint()`、`FlightPlanService.createVersionForConstraint()` | `ConstraintGuidance.apply_level()`、`report_constraint_state()` | 航段稳定 ID、性能包线、字段冲突键 |
| P_TIME | `LegConstraintParser.parseTime()`、`LegConstraintService.applyTimeConstraint()`、`FlightPlanService.createVersionForConstraint()` | `ConstraintGuidance.apply_time()`、`estimate_required_speed()` | D日序/T+秒/HHMM、暂停冻结、不可达拒绝 |
| MISSED 程序解析 | `MissedApproachParser.parse()`、`ProcedureCommandService.resolveMissedProcedure()` | 由 P13 `MissedApproachGuidance` 执行 | 只解析快照中正式复飞程序 |

### 17.2 TDD 执行

1. Red：`ProcedureParserTest` 覆盖带/不带跑道、同名程序歧义、厂商别名和未知程序。
2. Green：所有解析结果保存稳定 procedureId/runwayId，不只保存显示代码。
3. Red：`ProcedureCommandServiceTest.givenProcedureWithBrokenLegContinuityThenReferenceNotFoundAndPlanUnchanged`。
4. Green：先完整构造候选计划，校验通过后才创建新版本。
5. Red：`LegConstraintServiceTest` 覆盖同航段字段独立冲突、过去的 P_TIME、性能不可达和版本保留。
6. Green：P_LEVEL/P_TIME 分别使用 `BUSINESS_FIELD:LEG:{legId}:LEVEL/TIME` 冲突键；SIDSTAR 与 RTE 共用 `LATERAL` 冲突键。
7. Red：Python `test_procedure_guidance.py` 覆盖航段激活、通过、约束事件和恢复快照。
8. Green：Adapter 输出 `WAYPOINT_PASSED` 和阶段证据；Java 只去重投影。
9. Refactor：SID/STAR 共用程序解析和计划构建器，但保留不同机场/阶段前置规则。

退出条件：RTE/SID/STAR/P_LEVEL/P_TIME 每次成功都产生新计划版本；失败不改变当前活动版本；Adapter 和平台引用同一稳定航段 ID。

## 18. P13：TAKEOFF、ILS、MISSED 与着陆闭环

### 18.1 函数级矩阵

| 功能 | Java 函数 | Python 状态机函数 | 关键阶段 |
|---|---|---|---|
| TAKEOFF | `TakeoffParser.parse()`、`TakeoffCommandValidator.validatePhaseRunwayTimePerformance()`、`TakeoffCompositeFactory.createChildren()` | `TakeoffGuidance.schedule()`、`line_up()`、`begin_takeoff_roll()`、`rotate()`、`initial_climb()`、`release_lateral_and_speed_at_400ft()`、`completion_evidence()` | SCHEDULED/LINE_UP/TAKEOFF_ROLL/ROTATION/INITIAL_CLIMB/COMPLETED |
| ILS | `IlsParser.parse()`、`IlsCommandValidator.resolveFacilityAndEnvelope()`、`IlsCompositeFactory.createChildren()` | `IlsGuidance.intercept_localizer()`、`capture_localizer()`、`capture_glideslope()`、`track_final()`、`flare()`、`rollout()`、`mark_landed()`、`terminate_by_override()` | INTERCEPTING_LOCALIZER/LOCALIZER_CAPTURED/GLIDESLOPE_CAPTURED/FINAL_APPROACH/FLARE/ROLLOUT/LANDED |
| MISSED | `MissedApproachParser.parse()`、`MissedCommandValidator.validatePhase()`、`MissedCompositeFactory.createChildren()` | `MissedApproachGuidance.initiate()`、`climb()`、`track_procedure()`、`terminate_by_override()` | MISSED_INITIATED/CLIMBING/PROCEDURE_TRACK/COMPLETED |
| 着陆报告 | `FlightPhaseEventHandler.onLanded()`、`FlightReportService.recordLanded()` | `FlightPhaseDetector.detect_landed()`、`publish_phase_changed()` | LANDED |

### 18.2 TDD 执行

1. Red：为 TAKEOFF 写阶段前置、跑道、性能、复合子项和 400 ft AGL 前覆盖测试。
2. Green：Java 创建完整子项；Adapter 一次原子采用横向/垂直/速度目标。
3. Red：Python 使用合成传感帧逐阶段驱动 TAKEOFF；乱序或缺失条件不得越级。
4. Red：为 ILS 写台站频率/航向/下滑角、截获几何、阶段前置和任一通道覆盖整体终止测试。
5. Green：`terminate_by_override()` 同一循环清除全部 ILS guidance，并发出稳定原因码。
6. Red：为 MISSED 写只允许 APPROACH/FINAL/FLARE、终止 ILS、装载复飞程序和整体覆盖测试。
7. Green：MISSED 接管 ILS 后一次性激活复飞横向/垂直目标。
8. Red：真实 BlueSky `test_takeoff_to_landing_e2e.py` 与 `test_missed_approach_e2e.py`；Green：补齐真实引擎映射。
9. Refactor：复用复合指令聚合和阶段事件框架，不用位置阈值在 Java 重复判定阶段。

退出条件：AT-01、AT-02 可在真实 BlueSky 重复运行；每次阶段变化、覆盖和失败都有 Adapter 证据、指令报告和飞行报告。

## 19. P14：Squawk、SSR、NML、IDENT、NSPEED 与 ID/DECOMP profile

### 19.1 普通业务字段与速度函数

| 功能 | Java 函数 | Adapter 函数 | 说明 |
|---|---|---|---|
| SQK | `SquawkParser.parse()`、`SquawkValidator.validateOctal()`、`TransponderService.setSquawk()`、`clearSquawk()` | 无；MySQL 业务字段 | 字符串保存前导零；CLR 置无效；`0000` 明确拒绝；重复仅警告 |
| SSRMODE | `SsrModeParser.parse()`、`TransponderService.setMode()` | 无；MySQL 业务字段 | 固定枚举，按字段冲突 |
| NML | `NormalTransponderParser.parse()`、`TransponderService.restorePlannedStateAtomically()` | 无；MySQL 业务字段 | 同时锁定 SQUAWK/SSR_MODE；恢复计划值，不等同 profile CLR |
| IDENT | `TransponderIdentParser.parse()`、`TransponderService.activateIdent()`、`clearExpiredIdent()` | 无；MySQL 业务字段 | 独立字段和自动清除计时器；重复成功时重新计时 |
| NSPEED | `NormalSpeedParser.parse()`、`SpeedScheduleService.resolveNormalSpeed()` | `apply_normal_speed()` | 复用 SPEED 通道，来源为当前机型/阶段计划 |

### 19.2 特情 profile 函数

| 类 | 函数 | 责任 |
|---|---|---|
| `SpecialOperationProfileService` | `createDraft()`、`updateDraft()`、`publish()`、`retire()`、`getPublished()` | 草稿、不可变发布和停用 |
| `SpecialProfileSchemaRegistry` | `schemaForVersion()`、`validateParameters()`、`validateOverridePolicy()`、`validateClearBehavior()` | 服务端内置 Schema 校验 |
| `SpecialProfileChecksum` | `canonicalize()`、`sha256()` | 规范化后服务端计算 checksum |
| `SpecialProfileLoader` | `loadRequiredProfiles()`、`confirmAdapterChecksum()`、`isLoaded()` | 训练开始前 Adapter 一致性 |
| `IdCommandParser` | `parse()` | 只解析 profile 明确声明的 ID 参数 |
| `DecompCommandParser` | `parse()` | 只解析 profile 明确声明的 DECOMP 参数 |
| `SpecialOperationCommandService` | `normalizeWithProfile()`、`createCompositeChildren()`、`applyClearBehavior()` | 按发布 profile 产生指令，不内置 N/S 含义 |
| `SpecialOperationProfileControllerV2` | `list()`、`create()`、`update()`、`publish()`、`retire()` | 运维接口 |

Python 新增：`SpecialProfileRegistry.load()`、`verify_checksum()`、`definition_for()`；`ProfileGuidance.apply()`、`clear()`、`override()`、`snapshot()`。

### 19.3 TDD 执行

1. Red：`SquawkValidatorTest` 覆盖 `0042` 保留、8/9 非法、长度、`0000`、重复警告；Green：Squawk 全链路保持字符串。
2. Red：`TransponderServiceTest` 覆盖模式切换、NML 和 IDENT 自动清除；Green：IDENT 使用独立到期仿真时间，不复用 ID 类型。
3. Red：`SpeedScheduleServiceTest` 覆盖机型/阶段正常速度和快照数据缺失；Green：NSPEED 产生规范速度目标。
4. Red：`SpecialOperationProfileServiceTest` 覆盖非法 Schema、缺 overridePolicy、发布后 PUT、checksum 稳定、停用不影响执行中指令。
5. Green：实现草稿到发布状态机与规范 JSON checksum。
6. Red：Java/Python `SPECIAL_PROFILE_LOAD` checksum 不一致时 `PROFILE_NOT_LOADED`；Green：STARTING 装载 profile。
7. Red：`SpecialOperationCommandServiceTest.givenNoPublishedProfileWhenIdSubmittedThenFeatureProfileNotConfigured`；Green：只有 profile 驱动映射。
8. Red：分别检查命令目录、数据库、报告和前端中 IDENT 与 ID 无混用。
9. Refactor：厂商快捷别名仅在 parser 层转换，领域类型保持规范名称。

退出条件：AT-06 完整通过；有测试 profile 时 AT-08 工程验收通过；无业务签署 profile 时部署验收显式标记未通过而不是伪造含义。

## 20. P15：删除预览与可恢复删除 Saga

### 20.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `DeletionPreviewService` | `createPreview()`、`buildImpactSummary()`、`issueOneTimeToken()`、`validateAndConsumeToken()` | 绑定 aircraftId、revision、终端和到期时间 |
| `AircraftDeletionSaga` | `requestDelete()`、`freezeWrites()`、`cancelBlockedInstructions()`、`requestExecutingCancellation()`、`sendAdapterDelete()`、`confirmDeleted()`、`markDeleteFailed()`、`retry()`、`cancelFailedDelete()` | 可重入删除步骤 |
| `DeletionReconciliationService` | `queryAircraftExists()`、`resolveUnknownResult()`、`resumeIncompleteSagas()` | 丢 ACK/重启后对账 |
| `AircraftDeleteControllerV2` | `preview()`、`delete()`、`retry()`、`cancelFailed()` | v2 删除接口 |
| `DeletionTokenCodec` | `encode()`、`decodeAndVerify()`、`hashForStorage()` | 一次性 token，不保存明文 |

Adapter：`BlueSkyEngine.delete_aircraft_idempotently()`、`aircraft_exists()`、`_delete_result_checksum()`；协议处理 `_handle_aircraft_delete()`、`_handle_aircraft_exists_get()`。

前端：`api/deletion.ts` 的 `previewDeletion()`、`deleteAircraft()`、`retryDelete()`；`DeleteAircraftDialog.vue` 的 `loadImpact()`、`confirmDelete()`、`handleExpiredPreview()`。

### 20.2 TDD 执行

1. Red：`DeletionPreviewServiceTest` 覆盖无 token、过期、已使用、版本变化、其他终端使用均返回确认/权限错误。
2. Green：token 摘要落库，成功请求中原子消费。
3. Red：`AircraftDeletionSagaTest.givenDeleteRequestedThenNewWritesAreRejectedAndStepsAreOrdered`。
4. Green：生命周期先到 DELETE_REQUESTED；历史、计划、报告不物理删除。
5. Red：`givenPlannedAircraftWhenDeletedThenCompletedLocallyWithoutAdapterOutbox`；Green：未出现航空器在同一事务完成 `DELETE_REQUESTED → DELETED`，不写 adapter_outbox、不发送 AIRCRAFT_DELETE。
6. Red：`givenAdapterRejectsThenDeleteFailedRetainsAircraftAndReason`、`givenAckLostButEntityAbsentThenReconciliationCompletesDeleted`。
7. Green：失败可重试；未知结果先 EXISTS_GET，不能猜测。
8. Red：`givenPlatformRestartsDuringDeleteThenSagaResumesWithoutDuplicateSideEffect`。
9. Green：从数据库状态和 adapter_outbox 恢复步骤。
10. Red：`CommandIntentRouterTest.givenDelTextWhenSubmittedThenPreviewFlowStartsAndNoInstructionIsCreated`。
11. Red：前端必须先显示影响摘要，token 过期自动重新预览，不直接发旧 DELETE。
12. Refactor：删除现有 `AircraftService.delete()` 物理删除路径，所有删除入口只调用 Saga。

退出条件：创建、指令、移交和删除并发时有明确锁顺序；任何故障点都可查询、可重试、可对账；DELETED 历史仍可报告审计。

## 21. P16：两类假目标

### 21.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `FakeTargetService` | `create()`、`get()`、`list()`、`updateSynthetic()`、`stop()`、`delete()` | 统一资源入口和 revision |
| `FakeTargetStateMachine` | `schedule()`、`activate()`、`stop()`、`expire()`、`requestDelete()`、`confirmDeleted()`、`fail()` | 固定状态转换 |
| `SyntheticTargetMotionService` | `activateDueTargets()`、`advanceOneSecond()`、`projectPosition()`、`freeze()`、`expireDueTargets()` | 恒速大圆/局部近似运动和暂停 |
| `SimulatedAircraftTargetService` | `createViaAircraftPlan()`、`expireViaDeletionSaga()`、`mapAircraftLifecycle()` | 复用 P07/P15 |
| `FakeTargetControllerV2` | `list()`、`create()`、`get()`、`patch()`、`stop()`、`delete()` | v2 假目标接口 |

前端：`api/fakeTargets.ts`；`stores/fakeTargets.ts` 的 `upsert()`、`applyFrame()`、`applyLifecycle()`；`FakeTargetDialog.vue` 的 `validateTimes()`、`buildSyntheticPayload()`、`buildSimulatedAircraftPayload()`、`submit()`。

### 21.2 TDD 执行

1. Red：`FakeTargetStateMachineTest` 对全部合法边和 STOPPED 不可重启进行参数化测试。
2. Red：`SyntheticTargetMotionServiceTest` 用固定初始值验证每秒位置、高度、跨 360°、暂停冻结、到期移除和 STOPPED 保持最后位置。
3. Green：使用仿真时钟；1 Hz 写归档，MySQL 只写最后索引。
4. Red：`SimulatedAircraftTargetServiceTest` 验证 `isFake=true`、完整控制/分配、到期走删除 Saga。
5. Green：调用航空器计划服务和删除 Saga，不复制 BlueSky 创建/删除代码。
6. Red：权限测试证明 RADAR_SYNTHETIC 拒绝普通飞行指令，SIMULATED_AIRCRAFT 接受。
7. Red：前端地图样式、编辑/停止/删除可用性和刷新恢复测试。
8. Refactor：统一假目标业务事件，但动态帧与生命周期事件仍分流。

退出条件：AT-07 通过；暂停时两类目标时间均冻结；合成目标从不产生 BlueSky 实体。

## 22. P17：命令报告、飞行报告、脚本和终端消息

### 22.1 函数级计划

| 类 | 函数 | 责任 |
|---|---|---|
| `CommandReportService` | `recordTransition()`、`list()`、`buildTerminalDescription()` | 每次指令状态转换唯一报告 |
| `FlightReportService` | `recordEvent()`、`deduplicateBySourceEventId()`、`list()` | 固定事件枚举和重连去重 |
| `ReportQueryService` | `query()`、`encodeCursor()`、`decodeCursor()` | 多条件过滤、稳定倒序游标 |
| `ReportControllerV2` | `list()` | 统一 COMMAND/FLIGHT 查询 |
| `ScriptService` | `create()`、`deliverDueItems()`、`acknowledge()`、`cancel()`、`listForTerminal()` | 仿真时间触发、暂停冻结、幂等确认 |
| `ScriptScheduler` | `scanDue()`、`publishDelivered()` | 到期投递 |
| `TerminalMessageService` | `receive()`、`list()`、`markRead()`、`softDeleteForTerminal()` | 目标终端隔离与软删除 |
| `ScriptControllerV2` | `create()`、`list()`、`acknowledge()` | v2 脚本接口 |
| `MessageControllerV2` | `receive()`、`list()`、`read()`、`delete()` | v2 消息接口 |

### 22.2 前端函数

- `api/reports.ts`：`queryReports()`；`stores/reports.ts`：`loadFirstPage()`、`loadNextPage()`、`applyReportEvent()`、`setFilters()`。
- `stores/scripts.ts`：`applyDelivered()`、`acknowledge()`、`pendingAcknowledgementCount()`。
- `stores/messages.ts`：`applyReceived()`、`markRead()`、`softDelete()`、`unreadCount()`。
- `ReportPanel.vue`：`submitFilters()`、`loadMore()`、`openAircraft()`。
- `ScriptInbox.vue`：`acknowledgeItem()`；`MessageInbox.vue`：`readMessage()`、`deleteMessage()`。

### 22.3 TDD 执行

1. Red：`CommandReportServiceTest.givenStateTransitionsThenSequenceIsUniqueAndTerminalStateHasStableReason`。
2. Green：状态转换和报告同事务；`instructionId + transitionSequence` 唯一。
3. Red：`FlightReportServiceTest.givenDuplicateAdapterSourceEventAfterReconnectThenOneReportExists`。
4. Green：按 `aircraftId + sourceEventId` 去重。
5. Red：`ReportQueryServiceMySqlTest` 覆盖所有过滤、同仿真时间 ID 定序和游标翻页无重复/遗漏。
6. Red：`ScriptServiceTest` 覆盖多目标终端、暂停、确认权限和重复确认原时间。
7. Red：`TerminalMessageServiceTest` 覆盖正文不泄露给非目标终端、已读 revision、终端级软删除。
8. Green：写可靠事件并按终端 fan-out。
9. Red：前端列表分页、实时插入、刷新恢复、未读计数测试。
10. Refactor：报告说明由服务器生成稳定结构字段，前端只本地化显示，不推导指令状态。

退出条件：AT-09 通过；历史分页稳定；刷新后脚本确认和消息读删状态不丢失；可靠 SSE 不泄露正文。

## 23. P18：电子进程单、地图工具、标牌和屏幕方案

### 23.1 后端与前端函数

| 功能 | 后端函数 | 前端函数 |
|---|---|---|
| 屏幕方案 | `DisplayProfileService.list/create/update/delete/applyDefault`、`validateLimit()` | `displayProfilesStore.load/save/apply/remove`、`DisplayProfileDialog.submit()` |
| 标牌布局 | `LabelLayoutService.save/delete/listForBootstrap` | 保留 `labelGeometry.*`；新增 `labelLayoutStore.saveDebounced/reset/applyServerLayout` |
| 电子进程单 | bootstrap/航空器/指令投影 | `flightStripModel.toRows()`、`sortRows()`、`statusBadges()`、`FlightStripPanel.select/submitQuickAction` |
| 未来航路 | 固定计划与活动 guidance 查询 | `futureRoute.buildSegments()`、`routeConstraintLabels()` |
| 测距 | 无写接口 | `rangeBearing.start()`、`update()`、`finish()`、`distanceNm()`、`bearingTrue()`、`bearingMagnetic()` |
| 罗盘 | 快照磁差模型 | `compassRose.buildTicks()`、`formatMagneticBearing()`、`rotateWithMap()` |
| 距离环 | 屏幕方案保存 | `rangeRings.build()`、`validateSpacing()`、`formatRingLabel()` |
| 局部地图 | 快照 map-layers | `localMap.openForAircraft()`、`fitRoute()`、`close()` |
| 自动避让 | 标牌持久化 | `labelCollision.detect()`、`findCandidateLayout()`、`resolveAll()`、`respectPinnedLayout()` |
| 历史点/速度矢量 | 动态帧 | `trackHistory.append()`、`prune()`、`velocityVector.project()` |
| 单位显示 | 无业务写入 | `formatAltitude()`、`formatSpeed()`、`formatDistance()`、`formatVerticalSpeed()` |

现有 `DisplaySettingsService` 改造成 `DisplayProfileService` 的默认方案迁移门面；现有 `SituationMap.vue.redraw/syncAnchors/rebuildRuntimeLayers/updateRuntimeVisibility/refreshRuntimeStyles` 分解到可测试 composable，组件只组织 OpenLayers 生命周期。

### 23.2 TDD 执行

1. Red：`DisplayProfileServiceTest` 覆盖同终端名称唯一、20 个限制、默认方案不可删除、revision 冲突和终端隔离。
2. Green：保存视口、量程、单位、图层、面板、颜色、字体、距离环、历史点和矢量全部字段。
3. Red：`LabelLayoutServiceTest` 覆盖终端+航空器唯一和删除恢复自动布局。
4. Red：Vitest 纯函数测试未来航路、真/磁方位、三种单位、碰撞避让、固定布局、历史点裁剪和速度矢量。
5. Green：先实现纯函数，再在 `SituationMap.vue` 中接线。
6. Red：组件测试电子进程单的责任只读、生命周期、队列、错误、快捷操作和移交后即时变化。
7. Red：OpenLayers 交互测试点选、拖标牌、测距、局部地图、图层方案切换和键盘可达性。
8. Refactor：将当前单体 `App.vue` 拆为 `TopStatusBar`、`FlightStripPanel`、`CommandConsole`、`ReportPanel`、`SituationWorkspace`、`CollaborationDrawer`；保持 store 为唯一状态来源。

退出条件：AT-10 通过；完整方案可保存/调用；手工标牌布局刷新后恢复；所有写控件依据服务端权限投影禁用但服务端仍独立鉴权。

## 24. P19：状态归档、检查点、重启恢复与数据保留

### 24.1 Java 函数

| 类 | 函数 | 责任 |
|---|---|---|
| `ArchivePathPolicy` | `resolveGroupDay()`、`segmentPath()`、`temporaryPath()`、`validateUuidComponent()` | 只在显式 `BS_STATE_ARCHIVE_DIR` 下生成安全路径 |
| `StateSegmentWriter` | `openSegment()`、`appendFrame()`、`flushAndSync()`、`finalizeAtomically()`、`calculateChecksum()` | 每分钟 gzip JSONL 状态段 |
| `StateSegmentReader` | `readAndVerify()`、`validateHeader()`、`streamFramesAfter()` | 格式/组/实例/快照/文件 checksum 校验 |
| `CheckpointService` | `createEveryThirtySeconds()`、`writeCheckpoint()`、`registerMetadataAfterRename()`、`loadLastValid()` | 全量恢复检查点 |
| `RecoveryCoordinator` | `recoverAtStartup()`、`claimRecoverableGroup()`、`createReplacementEngine()`、`loadCheckpoint()`、`replayReliableEvents()`、`reconcilePendingOperations()`、`verifyRecoveredState()`、`completePaused()`、`failRecovery()` | 启动恢复主流程 |
| `PendingOperationReconciler` | `reconcileOutbox()`、`reconcileDispatchingInstructions()`、`reconcileCreateRequested()`、`reconcileDeleteRequested()` | 逐类查询幂等结果 |
| `RecoveryToleranceValidator` | `validatePositionNm()`、`validateAltitudeFt()`、`validateIasKt()`、`validateRoutesAndPhases()` | 0.1 NM/100 ft/5 kt 和精确程序状态 |
| `ArchiveCleanupService` | `findEligibleGroups()`、`hasPendingOperations()`、`deleteExpiredSegments()`、`retainAuditSummary()` | 90 天/一年保留与未决保护 |
| `ArchiveCapacityGuard` | `sampleUsage()`、`warnAt70()`、`blockNewStartAt85()`、`emergencyPauseAt95()` | 容量故障边界 |

### 24.2 Python 函数

- `recovery.CheckpointCodec.validate()`、`load()`、`result_checksum()`。
- `BlueSkyEngine.load_recovery_checkpoint()`、`restore_aircraft()`、`restore_routes()`、`restore_guidance()`、`restore_program_phases()`。
- `AdapterProtocolV2._handle_recovery_checkpoint_load()`、`_handle_instruction_status_get()`。

### 24.3 TDD 执行

1. Red：`ArchivePathPolicyTest` 覆盖无配置、路径穿越、非 UUID、跨组路径，且绝不回退当前目录/用户目录。
2. Red：`StateSegmentWriterTest` 覆盖 tmp→fsync→rename→DB 元数据顺序、进程中断和 checksum。
3. Green：正式文件写成后才登记 MySQL；无记录过期 tmp 可清理，错误正式文件不自动删。
4. Red：`CheckpointServiceTest` 验证全部航空器、航路、guidance、程序、合成目标和序号都在检查点中。
5. Red：`RecoveryCoordinatorTest` 覆盖各可恢复组状态、ENDING 只完成结束、PAUSED 丢实例、缺快照、checksum 错误和无法恢复程序阶段。
6. Green：每一步可重入并持久化；成功永远进入 PAUSED。
7. Red：Java/Python checkpoint 共享向量及 resultChecksum 测试。
8. Red：故障注入分别在 Adapter 已应用但 Java 未确认、创建中、删除中、写段中、重放中杀进程。
9. Red：`ArchiveCapacityGuardTest` 使用伪磁盘采样验证 70/85/95 行为和 PAUSE 确认顺序。
10. Refactor：删除 `StartupReset.run()` 生产注册；演示重置只保留 `RESET_DEMO_ONLY` 专用配置。

退出条件：AT-11 通过；任何恢复失败都保留证据并进入 RECOVERY_FAILED；浏览器 bootstrap 不会触发引擎重建。

## 25. P20：可观测性、安全配置、容量与最终切换

### 25.1 函数级计划

| 类/脚本 | 函数 | 责任 |
|---|---|---|
| `TrainingMetrics` | `recordInstructionTransition()`、`recordDispatchLatency()`、`recordSseBacklog()`、`recordHandover()`、`recordRecovery()` | Micrometer 指标统一入口 |
| `StructuredLogContextFilter` | `doFilterInternal()` | 注入 group/terminal/aircraft/instruction/request 上下文 |
| `SecretRedactor` | `redactConfiguration()`、`redactUri()`、`redactFingerprint()` | 防止日志泄密 |
| `ZeroMqSecurityValidator` | `validateLoopbackOrCurve()`、`validateKeyFiles()` | 非 loopback 必须 CurveZMQ |
| `UpgradeReadinessService` | `findActiveGroups()`、`assertUpgradeAllowed()` | 只允许 READY/ENDED 升级 |
| `V1RetirementVerifier` | `scanFrontendRoutes()`、`scanSpringMappings()`、`scanProtocolVersion()` | 阻止运行期 v1 依赖 |
| `loadtest/workstation_v2.js` | `setupGroups()`、`runReadMix()`、`runWriteMix()`、`disconnectRandomClients()`、`summarize()` | 固定 AT-12 负载模型 |
| `scripts/collect_acceptance_evidence.py` | `collect_metrics()`、`collect_logs()`、`write_manifest()`、`verify_seed()` | 保存硬件、配置、原始指标和摘要 |

### 25.2 TDD 与执行

1. Red：指标测试验证关键状态/失败原因均有低基数标签；Green：Service 通过统一 `TrainingMetrics` 记录。
2. Red：日志捕获测试证明每次写请求具备五类上下文且无数据库密码、Curve 密钥或完整指纹。
3. Red：配置测试证明 TCP 非 loopback 且无 Curve 时启动失败；Green：实现 fail-fast validator。
4. Red：`UpgradeReadinessServiceTest` 对所有训练状态参数化；Green：只放行 READY/ENDED。
5. Red：`V1RetirementVerifierTest` 初始因 `/api/v1` 和 Protocol 1.0 引用失败；迁移所有前端 API、Controller、配置和脚本后再 Green。
6. 运行 AT-01～AT-11 全回归；失败按对应 P 编号回到 Red，不在 E2E 测试中加入绕过。
7. 运行预热 10 分钟 + 连续 30 分钟性能窗口；满足样本、比例、P95、错误率和资源指标。
8. 运行 8 组 × 200 架 × 每组 8 终端共 8 小时，执行指定断网、Adapter 重连和 Java 重启故障注入。
9. 只有证据齐备后执行 `V15`、删除 v1 Controller/Protocol 入口和演示启动重置。

退出条件：AT-01～AT-12 全绿，连续运行和容量满足详细设计第 4.1、15.4 节，仓库扫描无运行期 v1 依赖。

## 26. 分批执行清单

### 26.1 Wave 0：契约定桩（M0）

按顺序执行：

1. `P00` 四类契约与共享测试向量。
2. `V8`～`V15` 完整数据字典草案，只执行 `V8` 的测试迁移。
3. 建立 CI 三语言基础门禁和 MySQL Testcontainers profile。
4. 评审所有目标函数名、包路径和契约；发现语义缺口先回到详细设计。

交付：OpenAPI、SSE Schema、Adapter Schema、命令目录、完整数据字典、测试向量。未通过契约一致性测试不得进入 Wave 1。

### 26.2 Wave 1：正式运行底座（M1）

按依赖执行：

1. `P01` + `V8`：revision、幂等、Outbox、错误与审计。
2. `P02`：终端/服务身份和权限矩阵。
3. `P03` + `V9`：固定参考快照。
4. `P04`：Protocol 2.0 和多引擎实例。
5. `P05`：训练生命周期。
6. `P06` + `V12`：可靠 SSE 与 bootstrap。
7. `P07` + `V10`：航空器、计划和延迟出现。
8. `P08`：当前责任和移交。

执行门：`M1AcceptanceTest` 依次通过运维管理接口创建两组、注册并绑定四终端、经 `GET /reference-snapshots` 固定快照、启动、暂停、重连、移交和结束；验证组间隔离、revision、幂等和游标。

### 26.3 Wave 2：进程单与基础控制（M2）

1. `P09` + `V11`：完整指令、blocker、guidance 和 composite 内核。
2. `P10`：ACID、HDG、LEFT/RIGHT、ALT、VS、SPD、MACH。
3. `P14` 中 SQK、SSRMODE、NML、IDENT 子集。
4. `P17` 中命令报告和飞行报告子集。
5. `P18` 中电子进程单、基础标牌和快速控制子集。
6. `V13`：假目标、脚本、消息、显示方案与标牌布局表结构一次迁移到位，供本 Wave P17/P18 子集及 Wave 5/6 使用。

执行门：基础控制矩阵覆盖 REPLACE/AFTER_COMPLETION、暂停多 blocker、移交后接管、Adapter 拒绝/超时和稳定窗。

### 26.4 Wave 3：航路与程序（M3）

1. `P11`：DCT/RER/RTE/RESUME。
2. `P11`：ORBIT/HOLD/OFFSET/VOR。
3. `P12`：SID/STAR/P_LEVEL/P_TIME 和 MISSED 程序解析。
4. 完成 AT-04、AT-05；将全部复杂阶段纳入 Adapter 快照。

执行门：原子失败不改变旧航路，所有阶段可恢复且 Java 不根据位置猜测。

### 26.5 Wave 4：起降闭环（M4）

1. `P13` TAKEOFF。
2. `P13` ILS 和着陆。
3. `P13` MISSED。
4. 串联 AT-01、AT-02 真实 BlueSky 场景。

执行门：从延迟出现到着陆/复飞全链路报告、计划版本、指令和阶段证据一致。

### 26.6 Wave 5：特情与协同（M5）

1. `P14` NSPEED 与 ID/DECOMP profile。
2. `V14`：特情 profile、检查点、状态段和清理任务元数据迁移；检查点与状态段表供 Wave 7 P19 使用。
3. `P15` 删除 Saga。
4. `P16` 两类假目标。
5. `P17` 脚本和消息。
6. 完成 AT-06～AT-09。

执行门：IDENT/ID 自动扫描无混用；删除和假目标故障注入无数据库/BlueSky 长期分叉。

### 26.7 Wave 6：显示完善（M6）

1. 完成 `P18` 全部地图工具、自动避让、历史点、速度矢量和屏幕方案。
2. 运行 64 终端浏览器组件/交互压力测试。
3. 完成 AT-10。

执行门：三种单位只改变显示，不改变服务端规范值；屏幕方案和标牌布局跨刷新恢复。

### 26.8 Wave 7：恢复与系统验收（M7）

1. `P19` 归档、检查点、恢复和容量保护。
2. `P20` 安全、可观测、v1 退役检查。
3. 完成 AT-11、AT-12 及 8 小时稳定性。
4. 执行 `V15`，删除 v1 Controller、Protocol 1.0 入口和 `StartupReset` 生产注册。
5. 生成最终验收证据 manifest，并逐条核对详细设计第 17 节完成定义。

## 27. AT-01～AT-12 自动化执行入口

为避免“人工步骤即测试”，建立以下稳定测试入口：

| 场景 | 测试入口函数 | 主要调用链 |
|---|---|---|
| AT-01 | `CompleteFlightAcceptanceTest.runCompleteFlight()` | `createDelayedAircraft → start → TAKEOFF → SID → basic controls → route → HOLD → STAR → ILS → landed` |
| AT-02 | `MissedApproachAcceptanceTest.runMissedApproach()` | `armIls → reachFinal → submitMissed → verifyIlsTerminated → verifyMissedRoute` |
| AT-03 | `MultiTerminalHandoverAcceptanceTest.runConcurrentHandover()` | `handoverByFrequency → assertSourceReadOnly → assertTargetWritable → raceSecondHandover` |
| AT-04 | `RouteConstraintAcceptanceTest.runAtomicRouteScenario()` | `rerCandidate → rte → resume → offset → vor → pLevel → pTime → injectInvalidRoute` |
| AT-05 | `HoldingOrbitAcceptanceTest.runHoldAndOrbit()` | `enterHold → pause → resume → exit → enterOrbit → override` |
| AT-06 | `TransponderAcceptanceTest.runTransponderMatrix()` | `sqk → ssrMode → ident → autoClear → nml → restart` |
| AT-07 | `FakeTargetAcceptanceTest.runBothTargetKinds()` | `create → advance → pause → stop/command → expire → delete` |
| AT-08 | `SpecialProfileAcceptanceTest.runProfileLifecycleAndCommands()` | `draft → invalidPublish → publish → load → ID/DECOMP → CLR → retire` |
| AT-09 | `CollaborationAcceptanceTest.runReportsScriptsMessages()` | `filterReports → triggerScript → acknowledge → receiveMessage → read → delete → refresh` |
| AT-10 | `DisplayAcceptanceTest.runDisplayProfileScenario()` | `units → route → measure → localMap → labelAvoidance → history/vector → save/apply` |
| AT-11 | `RecoveryAcceptanceTest.runFailureInjectionMatrix()` | `disconnectBrowser → expireCursor → killJava/Adapter at checkpoints → recoverPaused → checksumFailure` |
| AT-12 | `CapacityAcceptanceRunner.runEightHourProfile()` | `warmUp → steadyWindow → faultSchedule → collect → assertThresholds` |

每个入口必须：使用固定随机种子；输出 operationId/requestId 列表；失败时保留数据库快照、Adapter 状态摘要和最近可靠事件；成功时写入机器可读 JSON 结果。

## 28. 每个功能计划的任务卡模板

实际创建开发任务时，从对应 P 编号复制以下模板，不得只写“完成某功能”：

```markdown
### [Pxx-Fnn] 功能名称

- 需求依据：第二版详细设计 x.x 节；本文 Pxx
- 前置任务：Pxx-Fnn
- 变更文件：列出生产文件、测试文件、迁移/Schema 文件
- 新增/改造函数：逐一列出完整类名和函数名
- Red：测试类.测试方法；预期失败信息
- Green：最小实现步骤，不包含下一任务能力
- Refactor：允许的内部重构范围
- Contract：OpenAPI/SSE/Adapter/DB 受影响项
- Failure cases：权限、revision、幂等、并发、Adapter 拒绝、超时、重启
- 执行命令：最小测试、模块测试、全量门禁
- 验收证据：测试结果、requestId、operationId、指标/日志路径
- 完成条件：可观察的 Given/When/Then
```

任务卡拆分上限：一个卡片最多修改一个领域聚合和一个跨边界契约；预计超过两天或包含两个独立状态机时必须继续拆分。

## 29. 对外契约到函数的完整追踪

### 29.1 REST v2

| 路由 | Controller 函数 | 应用服务函数 | 计划 |
|---|---|---|---|
| `GET /workstations/{terminalId}/bootstrap` | `WorkstationControllerV2.bootstrap()` | `WorkstationSnapshotService.bootstrap()` | P06 |
| `GET/POST /exercise-groups` | `ExerciseGroupAdminControllerV2.list/create()` | `ExerciseGroupProvisioningService.listGroups/createGroup()` | P05 |
| `GET/POST .../terminals` | `TerminalAdminControllerV2.list/create()` | `TerminalProvisioningService.listByGroup/createTerminal()` | P02 |
| `PATCH /terminals/{terminalId}` | `TerminalAdminControllerV2.update()` | `TerminalProvisioningService.updateTerminal()` | P02 |
| `PUT /terminals/{terminalId}/trusted-binding` | `TerminalAdminControllerV2.bindCertificate()` | `TerminalProvisioningService.bindCallerCertificate()` | P02 |
| `GET /reference-snapshots` | `ReferenceSnapshotControllerV2.listPublished()` | `ReferenceSnapshotService.listPublished()` | P03 |
| `POST .../actions/start` | `ExerciseGroupActionControllerV2.start()` | `ExerciseGroupService.requestStart()` | P05 |
| `POST .../actions/pause` | `ExerciseGroupActionControllerV2.pause()` | `ExerciseGroupService.requestPause()` | P05 |
| `POST .../actions/resume` | `ExerciseGroupActionControllerV2.resume()` | `ExerciseGroupService.requestResume()` | P05 |
| `POST .../actions/end` | `ExerciseGroupActionControllerV2.end()` | `ExerciseGroupService.requestEnd()` | P05；仅编排身份 |
| `PUT .../reference-snapshot` | `ReferenceSnapshotController.pinSnapshot()` | `ReferenceSnapshotService.pinToGroup()` | P03 |
| `GET/POST .../aircraft` | `AircraftControllerV2.list/create()` | `AircraftApplicationService.listByGroup/createPlan()` | P07 |
| `GET /aircraft/{id}` | `AircraftControllerV2.get()` | `AircraftApplicationService.get()` | P07 |
| `PATCH .../flight-plan` | `AircraftControllerV2.patchFlightPlan()` | `AircraftApplicationService.patchPlannedFlightPlan()` | P07 |
| `POST .../deletion-preview` | `AircraftDeleteControllerV2.preview()` | `DeletionPreviewService.createPreview()` | P15 |
| `DELETE /aircraft/{id}` | `AircraftDeleteControllerV2.delete()` | `AircraftDeletionSaga.requestDelete()` | P15 |
| `POST .../retry-delete` | `AircraftDeleteControllerV2.retry()` | `AircraftDeletionSaga.retry()` | P15 |
| `POST .../cancel-delete` | `AircraftDeleteControllerV2.cancelFailed()` | `AircraftDeletionSaga.cancelFailedDelete()` | P15；仅编排身份 |
| `POST/GET .../instructions` | `InstructionControllerV2.submit/list()` | `InstructionApplicationService.submit/list()` | P09～P14 |
| `GET /instructions/{id}` | `InstructionControllerV2.get()` | `InstructionApplicationService.get()` | P09 |
| `POST .../instructions/{id}/actions/cancel` | `InstructionControllerV2.cancel()` | `InstructionApplicationService.cancel()` | P09 |
| `POST .../handover` | `HandoverControllerV2.handover()` | `HandoverService.handoverByFrequency()` | P08 |
| `GET .../assignments` | `HandoverControllerV2.assignments()` | `AircraftAssignmentService.listByGroup()` | P08 |
| `GET/POST .../fake-targets` | `FakeTargetControllerV2.list/create()` | `FakeTargetService.list/create()` | P16 |
| `GET/PATCH /fake-targets/{id}` | `FakeTargetControllerV2.get/patch()` | `FakeTargetService.get/updateSynthetic()` | P16 |
| `POST .../fake-targets/{id}/actions/stop` | `FakeTargetControllerV2.stop()` | `FakeTargetService.stop()` | P16 |
| `DELETE /fake-targets/{id}` | `FakeTargetControllerV2.delete()` | `FakeTargetService.delete()` | P16 |
| `GET .../reports` | `ReportControllerV2.list()` | `ReportQueryService.query()` | P17 |
| `POST/GET .../scripts` | `ScriptControllerV2.create/list()` | `ScriptService.create/listForTerminal()` | P17 |
| `POST .../scripts/{id}/actions/acknowledge` | `ScriptControllerV2.acknowledge()` | `ScriptService.acknowledge()` | P17 |
| `POST .../messages` | `MessageControllerV2.receive()` | `TerminalMessageService.receive()` | P17 |
| `GET .../workstations/{id}/messages` | `MessageControllerV2.list()` | `TerminalMessageService.list()` | P17 |
| `POST .../messages/{id}/actions/read` | `MessageControllerV2.read()` | `TerminalMessageService.markRead()` | P17 |
| `DELETE /messages/{id}` | `MessageControllerV2.delete()` | `TerminalMessageService.softDeleteForTerminal()` | P17 |
| `GET/POST .../display-profiles` | `DisplayProfileControllerV2.list/create()` | `DisplayProfileService.list/create()` | P18 |
| `PUT/DELETE /display-profiles/{id}` | `DisplayProfileControllerV2.update/delete()` | `DisplayProfileService.update/delete()` | P18 |
| `PUT/DELETE .../label-layout` | `LabelLayoutControllerV2.save/delete()` | `LabelLayoutService.save/delete()` | P18 |
| `GET .../reference-snapshot/{resourceType}` | `ReferenceSnapshotController.getResource()` | `ReferenceSnapshotService.readResource()` | P03 |
| `GET/POST /special-operation-profiles` | `SpecialOperationProfileControllerV2.list/create()` | `SpecialOperationProfileService.list/createDraft()` | P14 |
| `PUT /special-operation-profiles/{id}` | `SpecialOperationProfileControllerV2.update()` | `SpecialOperationProfileService.updateDraft()` | P14 |
| `POST .../actions/publish` | `SpecialOperationProfileControllerV2.publish()` | `SpecialOperationProfileService.publish()` | P14 |
| `POST .../actions/retire` | `SpecialOperationProfileControllerV2.retire()` | `SpecialOperationProfileService.retire()` | P14 |
| `GET /events` | `EventStreamControllerV2.events()` | `ReliableEventStreamService.connect()` | P06 |

每个 Controller 测试必须至少覆盖：合法请求、缺 Idempotency-Key、错误身份、缺/旧 revision、幂等重放和领域失败映射；GET 只覆盖身份、分页/过滤和 not-found。异步接口另断言 202 信封与最终 SSE 状态。

### 29.2 Adapter Protocol 2.0

`AdapterProtocolV2._dispatch()` 只负责类型路由；每类消息使用独立函数并有共享 JSON 向量：

| 消息族 | Python handler | Java 发起/处理函数 |
|---|---|---|
| HELLO/HELLO_ACK | `_handle_hello()` | `AdapterControlClient.hello()`、`EngineInstanceService.markConnected()` |
| HEALTH | `_handle_health()`、`AdapterRuntime.publish_heartbeat()` | `EngineHealthMonitor.onHeartbeat()` |
| START/STARTED | `_handle_start()` | `ExerciseStartCoordinator.completeStart()` |
| PAUSE/PAUSED | `_handle_pause()` | `ExerciseGroupService.onAdapterLifecycleResult()` |
| RESUME/RESUMED | `_handle_resume()` | `ExerciseGroupService.onAdapterLifecycleResult()` |
| STOP/STOPPED | `_handle_stop()` | `ExerciseEndCoordinator.completeEnd()` |
| RESET_DEMO_ONLY | `_handle_demo_reset()` | `DemoResetGateway.resetWhenExplicitlyEnabled()` |
| REFERENCE_SNAPSHOT_LOAD/ACK | `_handle_reference_snapshot_load()` | `ReferenceSnapshotService.verifyPinnedSnapshot()` |
| SPECIAL_PROFILE_LOAD/ACK | `_handle_special_profile_load()` | `SpecialProfileLoader.confirmAdapterChecksum()` |
| AIRCRAFT_APPLY/APPLIED | `_handle_aircraft_apply()` | `AircraftLifecycleService.confirmActive()` |
| AIRCRAFT_DELETE/DELETED | `_handle_aircraft_delete()` | `AircraftDeletionSaga.confirmDeleted()` |
| AIRCRAFT_EXISTS_GET/RESULT | `_handle_aircraft_exists_get()` | `DeletionReconciliationService.resolveUnknownResult()` |
| INSTRUCTION_APPLY/APPLIED/REJECTED | `_handle_instruction_apply()` | `InstructionDispatchService.handleApplied/handleRejected()` |
| INSTRUCTION_CANCEL/CANCELLED | `_handle_instruction_cancel()` | `InstructionApplicationService.onAdapterCancelled()` |
| INSTRUCTION_STATUS_GET/RESULT | `_handle_instruction_status_get()` | `PendingOperationReconciler.reconcileDispatchingInstructions()` |
| RECOVERY_CHECKPOINT_LOAD/ACK | `_handle_recovery_checkpoint_load()` | `RecoveryCoordinator.loadCheckpoint()` |
| STATE_SNAPSHOT_GET/CHUNK | `_handle_state_snapshot_get()` | `SnapshotChunkAssembler.acceptChunk()` |
| INSTRUCTION_PHASE_CHANGED | `InstructionEventPublisher.phase_changed()` | `AdapterStateProjector.onInstructionPhaseChanged()` |
| WAYPOINT_PASSED | `InstructionEventPublisher.waypoint_passed()` | `AdapterStateProjector.onWaypointPassed()` |
| FLIGHT_PHASE_CHANGED | `InstructionEventPublisher.flight_phase_changed()` | `AdapterStateProjector.onFlightPhaseChanged()` |
| GUIDANCE_FAILED | `InstructionEventPublisher.guidance_failed()` | `AdapterStateProjector.onGuidanceFailed()` |

所有响应 handler 先执行 `EngineInstanceService.assertCurrentInstance()`、`ProtocolSequenceTracker.acceptInbound()` 和 correlation/idempotency 校验，再进入领域事务；失败或迟到消息只记录协议审计，不修改业务投影。

## 30. 评审与变更控制

1. 每个 P 编号开始前，由后端、前端、Adapter 和测试负责人共同评审函数表；不适用的函数必须说明替代责任，不可直接省略。
2. 新需求若改变状态机、公开字段、错误码、指令含义或验收口径，先修订第二版详细设计，再调整本文和机器契约。
3. 实现中可以增加私有辅助函数，但不得把本文列出的跨层责任合并成不可测试的大函数。
4. 对外函数改名时，同一提交必须更新本文、契约和测试；只改代码不改计划视为未完成。
5. 每个 Wave 结束生成一次覆盖矩阵：详细设计功能 → P 编号 → 函数 → 测试 → AT 场景 → 验收证据。
6. 最终切换后，仓库中如仍存在被运行代码引用的旧设计、v1 API、Protocol 1.0、默认组特判或启动清空，第二版不得发布。

## 31. 最终交付物清单

- Java、Vue、Python 生产代码和自动测试；
- `V8`～`V15` Flyway 增量迁移及完整数据字典；
- OpenAPI v2、SSE Schema、Adapter Protocol 2.0 Schema、命令目录；
- Java/Python 共享协议向量和真实 BlueSky 场景；
- AT-01～AT-12 机器可读结果、负载脚本、随机种子、配置和硬件清单；
- 归档/恢复校验工具、运维指标、告警和安全配置说明；
- v1 依赖扫描报告、数据升级报告和最终覆盖矩阵。

只有上述交付物全部存在且通过本文质量门，才执行第二版发布标记。
