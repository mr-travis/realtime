---
phase: "1"
slug: "规则内核与表达式沙箱"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: true
wave_0_complete: false
created: "2026-09-29"
---

# Phase 1 — Validation Strategy
- **状态说明（wave_0 校准后）**：`nyquist_compliant: true` —— 48 条 REQ 全部有可采样行、每行已回填 `Task ID`、Wave 列已与各 PLAN frontmatter 的 `wave` 对齐，两条独立验收门（三计数器 / CVE）已有专行。`status` 仍为 `draft`（生命周期由 `validate-phase §6` 推进，不在本轮改动）。`wave_0_complete: false` —— 仓库尚无 `src/`，所有 `File Exists` 均为 ❌，Wave 0 未完成；**不得**在 `wave_0_complete: false` 时把任何行标为绿。

> Per-phase validation contract for feedback sampling during execution.
> 派生自 `01-RESEARCH.md:578-660`（Validation Architecture / Edge Coverage / Prohibitions）。
> 本文件由 plan-phase 播种；task 级映射在 `*-PLAN.md` 落盘后由 validate-phase §6 校准。

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Jupiter 5.8.2（Spring Boot 2.7.6 管理）+ Mockito + AssertJ + `spring-boot-starter-test`；性能用 JMH `jmh-core` 1.20（`provided`） |
| **Config file** | 无独立配置文件（surefire 由 Boot 依赖管理默认驱动） |
| **Quick run command** | `mvn -q -pl foundation-rule -am test` |
| **Full suite command** | `mvn test && mvn enforcer:enforce` |
| **Estimated runtime** | quick ~30s（单模块）· full ~180s |

> `ARCHITECTURE.md:251` 要求「无 Spring / 无 Nacos 依赖的模块用纯 JUnit 5 测试」。
> 因此 `foundation-rule`、`foundation-expression`、`foundation-core` 的单元测试**不加载 Spring 上下文**——沙箱与规则测试可在秒级完成，且沙箱测试不受宿主配置污染。

---

## Sampling Rate

- **After every task commit:** `mvn -q -pl <module> -am test`（仅受影响模块）
- **After every plan wave:** `mvn test && mvn enforcer:enforce`（全量 + 依赖收敛闸门）
- **Before `/gsd-verify-work`:** 全量绿 **且** `SandboxAttackMatrixTest` 全通过 **且** `enforcer:enforce` 绿 **且** JMH 基线已记录
- **Max feedback latency:** 180 秒（全量），30 秒（受影响模块）

---

## Per-Task Verification Map

> Task ID 已按各 `*-PLAN.md` 的 `<name>Task N` 回填（格式 `01-04-T1` = 计划 01-04 的 Task 1）；当 REQ 跨多个任务时取该计划的首个承载任务，
> 完整任务分解见各 PLAN 的 `<tasks>` 块。每条 REQ 至少一条可执行断言，两条独立验收门（三计数器、CVE）另有专行。
> `Threat Ref` 中 `T-1-NN` 由各 PLAN 的 `<threat_model>` 块分配，`—` 表示该任务不新增威胁寄存器。

| Req ID | Task ID | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|--------|--------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| FND-01 | 01-03-T1 | 3 | 公开 API 并发安全且可重入 | — | N/A | unit（多线程） | `mvn -pl foundation-rule test -Dtest=SnapshotAtomicityTest` | ❌ W0 | ⬜ pending |
| FND-02 | 01-01-T1 | 1 | 集成依赖全为 optional/provided | — | 接入方版本不被接管 | build | `mvn dependency:tree` + `mvn enforcer:enforce` | ❌ W0 | ⬜ pending |
| FND-03 | 01-01-T1 | 1 | 非 Spring 宿主可启动 | — | N/A | unit | `mvn -pl foundation-rule test -Dtest=NonSpringBootstrapTest` | ❌ W0 | ⬜ pending |
| FND-04 | 01-01-T1 | 1 | 公共 API 不泄漏三方类型 | — | 内部替换不构成破坏性变更 | unit + build | `mvn dependency:analyze` + API 签名检查 | ❌ W0 | ⬜ pending |
| FND-05 | 01-06-T1 | 5 | 冷启动 fail-fast（STRICT）/ RESILIENT opt-in | T-1-06 | 配置不可达不静默 | integration | `mvn -pl foundation-nacos test -Dtest=StartupFailureTest` | ❌ W0 | ⬜ pending |
| FND-06 | 01-01-T1 | 1 | 遵循 semver，deprecate-then-remove | — | N/A | unit | `mvn -pl foundation-core test -Dtest=SemverTest` | ❌ W0 | ⬜ pending |
| FND-07 | 01-01-T1 | 1 | 启动暴露实际解析版本 | — | 版本偏离可见 | integration | `mvn -pl foundation-autoconfigure test -Dtest=VersionAssertTest` | ❌ W0 | ⬜ pending |
| FND-08 | 01-01-T1 | 1 | `bannedDependencies` 防火墙 | — | 数据面模块不得依赖 spring-* | build | `mvn enforcer:enforce` | ❌ W0 | ⬜ pending |
| FND-09 | 01-03-T1 | 3 | 规则无 TTL、无同步重试 | T-1-02 | 决策路径零网络 | unit（Mockito 零交互） | `mvn -pl foundation-rule test -Dtest=NoNetworkOnDecisionPathTest` | ❌ W0 | ⬜ pending |
| FND-10 | 01-01-T1 | 1 | 全部配置 `foundation.*` opt-in | — | 不接管宿主全局配置 | integration | `mvn -pl foundation-autoconfigure test -Dtest=OptInOnlyTest` | ❌ W0 | ⬜ pending |
| GOV-01 | 01-03-T1 | 3 | schema 版本按能力分列 + support 上限 | — | schema 只加不减 | unit | `mvn -pl foundation-rule test -Dtest=SchemaParseTest` | ❌ W0 | ⬜ pending |
| GOV-02 | 01-03-T1 | 3 | `ruleSetVersion` 回退即拒绝 | — | N/A | unit | `mvn -pl foundation-rule test -Dtest=VersionMonotonicityTest` | ❌ W0 | ⬜ pending |
| GOV-03 | 01-03-T1 | 3 | 每能力独立 `AtomicReference` 快照 | — | N/A | unit | `mvn -pl foundation-rule test -Dtest=SnapshotAtomicityTest` | ❌ W0 | ⬜ pending |
| GOV-04 | 01-03-T1 | 3 | 无部分应用，跨能力不连累 | T-1-03 | 失败不扩大影响面 | unit | `mvn -pl foundation-rule test -Dtest=AtomicityPartialFailureTest` | ❌ W0 | ⬜ pending |
| GOV-05 | 01-05-T1 | 4 | LKG 快照 temp-file + `ATOMIC_MOVE` | T-1-04 | 落盘半写不可见 | unit | `mvn -pl foundation-rule test -Dtest=LastKnownGoodStoreTest` | ❌ W0 | ⬜ pending |
| GOV-06 | 01-03-T1 | 3 | 段级原子拒绝 + 关系约束校验 | T-1-03 | 语义错误不静默生效 | unit | `mvn -pl foundation-rule test -Dtest=RuleValidatorTest` | ❌ W0 | ⬜ pending |
| GOV-07 | 01-06-T1 | 5 | 回调去抖（~200ms） | — | 重复/乱序回调不重复应用 | unit | `mvn -pl foundation-nacos test -Dtest=RuleChangeListenerTest` | ❌ W0 | ⬜ pending |
| GOV-08 | 01-03-T1 | 3 | 配置删除退回内置安全默认 | — | 删除是合法事件 | unit | `mvn -pl foundation-rule test -Dtest=DeletedRuleDefaultTest` | ❌ W0 | ⬜ pending |
| GOV-09 | 01-06-T1 | 5 | 白名单外配置键即失败 | T-1-05 | 拼错键不静默 | unit | `mvn -pl foundation-nacos test -Dtest=NacosConfigKeysTest` | ❌ W0 | ⬜ pending |
| GOV-10 | 01-03-T1 | 3 | 内容哈希不匹配拒绝 | T-1-01 | 篡改不可生效 | unit | `mvn -pl foundation-rule test -Dtest=ContentHashTest` | ❌ W0 | ⬜ pending |
| GOV-11 | 01-06-T1 | 5 | 冷启动断言 `maxSchemaVersion` | T-1-06 | 启动期能力缺失可见 | integration | `mvn -pl foundation-nacos test -Dtest=StartupSchemaAssertTest` | ❌ W0 | ⬜ pending |
| GOV-12 | 01-05-T1 | 4 | 快照目录可写性探针 | T-1-04 | 目录失效可观测 | unit | `mvn -pl foundation-rule test -Dtest=SnapshotDirWatchdogTest` | ❌ W0 | ⬜ pending |
| GOV-13 | 01-05-T1 | 4 | 路由表大小稳定（10 万卡 BIN） | T-1-07 | 内存不随实体数增长 | unit | `mvn -pl foundation-rule test -Dtest=RoutePlanSizeTest` | ❌ W0 | ⬜ pending |
| GOV-14 | 01-04-T1 | 3 | 编译缓存有界 LRU + 淘汰指标 | T-1-07 | 堆占用可逆受限 | unit | `mvn -pl foundation-expression test -Dtest=CompiledExpressionCacheTest` | ❌ W0 | ⬜ pending |
| GOV-15 | 01-05-T1 | 4 | 差量替换 | — | N/A | unit | `mvn -pl foundation-rule test -Dtest=RoutePlanDiffTest` | ❌ W0 | ⬜ pending |
| EXPR-01 | 01-02-T1 | 2 | 沙箱求值器 SPI 契约 | T-1-01 | 默认实现沙箱化 | build | `mvn -pl foundation-expression test -Dtest=ExpressionEngineTest` | ❌ W0 | ⬜ pending |
| **EXPR-02** | 2 | 01-02-T1 | **启动断言沙箱未被关闭** | **T-1-01** | **启动期反证** | **unit** | `mvn -pl foundation-expression test -Dtest=SandboxOptionsAssertTest` | **❌ W0** | ⬜ pending |
| **EXPR-03** | 2 | 01-02-T1 | **固定攻击矩阵全部不可逃逸** | **T-1-01** | **9+1 类逃逸全阻断** | **unit** | `mvn -pl foundation-expression test -Dtest=SandboxAttackMatrixTest` | **❌ W0** | ⬜ pending |
| EXPR-04 | 01-08-T1 | 5 | 第 4 层 p50/p99 实测数字 | — | 性能有据可查 | benchmark | `mvn -pl foundation-bench test -Pjmh` | ❌ W0 | ⬜ pending |
| EXPR-05 | 01-04-T1 | 3 | 步数预算可触达 | T-1-08 | CPU DoS 有界 | unit | `mvn -pl foundation-expression test -Dtest=StepBudgetTest` | ❌ W0 | ⬜ pending |
| EXPR-06 | 01-04-T1 | 3 | 求值超时独立线程池 + 拒绝策略 | T-1-08 | 不用 `Thread.interrupt` | unit | `mvn -pl foundation-expression test -Dtest=EvaluationTimeoutTest` | ❌ W0 | ⬜ pending |
| EXPR-07 | 01-04-T1 | 3 | 长度/AST/深度限制 | T-1-08 | 内存放大有界 | unit | `mvn -pl foundation-expression test -Dtest=ExpressionBudgetLimitsTest` | ❌ W0 | ⬜ pending |
| **EXPR-08** | 2 | 01-02-T1 | **表达式正则静态拒绝** | **T-1-09** | **ReDoS 不可达** | **unit** | `mvn -pl foundation-expression test -Dtest=RegexForbiddenTest` | **❌ W0** | ⬜ pending |
| **EXPR-09** | 2 | 01-02-T1 | **validate 先于 compile** | **T-1-10** | **元空间不增长** | **unit** | `mvn -pl foundation-expression test -Dtest=ValidationBeforeCompileTest` | **❌ W0** | ⬜ pending |
| EXPR-10 | 01-04-T1 | 3 | 编译产物按规则 ID 缓存 | — | N/A | unit | `mvn -pl foundation-expression test -Dtest=CompileCacheKeyTest` | ❌ W0 | ⬜ pending |
| EXPR-11 | 01-04-T1 | 3 | env 白名单字段访问 | T-1-05 | 强敏感字段永不进入 | unit | `mvn -pl foundation-expression test -Dtest=EvalContextWhitelistTest` | ❌ W0 | ⬜ pending |
| EXPR-12 | 01-04-T1 | 3 | SPI 保留 + 可替换实现 | — | N/A | build | `mvn -pl foundation-expression test -Dtest=ExpressionSpiTest` | ❌ W0 | ⬜ pending |
| OBS-02 | 01-07-T1 | 4 | `DecisionRecord` 九字段集冻结 | T-1-11 | 不携带原始入参/key/表达式 | unit | `mvn -pl foundation-core test -Dtest=DecisionRecordContractTest` | ❌ W0 | ⬜ pending |
| OBS-03 | 01-07-T1 | 4 | `ruleVersion` 不可变 | — | N/A | unit | `mvn -pl foundation-rule test -Dtest=SnapshotImmutabilityTest` | ❌ W0 | ⬜ pending |
| OBS-05 | 01-07-T1 | 4 | 有界 ring buffer | T-1-07 | 堆占用可逆受限 | unit | `mvn -pl foundation-core test -Dtest=RingBufferSinkTest` | ❌ W0 | ⬜ pending |
| OBS-06 | 01-07-T1 | 4 | 满时丢弃不阻塞 | T-1-07 | 尾延迟不雪崩 | unit | `mvn -pl foundation-core test -Dtest=RingBufferOverflowTest` | ❌ W0 | ⬜ pending |
| OBS-07 | 01-07-T1 | 4 | 决策记录不受 debug 门控 | T-1-11 | 可追溯性不被开关削弱 | unit | `mvn -pl foundation-core test -Dtest=DebugGateIndependenceTest` | ❌ W0 | ⬜ pending |
| OBS-09 | 01-08-T1 | 5 | 有界 tag 预注册 | T-1-11 | 基数不可逆受限 | unit | `mvn -pl foundation-core test -Dtest=FoundationMetersTest` | ❌ W0 | ⬜ pending |
| SEC-02 | 01-07-T1 | 4 | 脱敏 + debug 开关默认关 | T-1-11 | PCI 数据不入日志 | unit | `mvn -pl foundation-core test -Dtest=MaskedContextTest` | ❌ W0 | ⬜ pending |
| SEC-03 | 01-07-T1 | 4 | SDK 默认脱敏 | T-1-11 | deny-by-default | unit | `mvn -pl foundation-core test -Dtest=DefaultMaskingTest` | ❌ W0 | ⬜ pending |
| SEC-04 | 01-08-T1 | 5 | 指标 tag 白名单 | T-1-11 | 无 PAN/traceId/无界 ruleVersion | unit | `mvn -pl foundation-core test -Dtest=TagAllowlistTest` | ❌ W0 | ⬜ pending |
| SEC-07 | 01-04-T1 | 3 | env ≤64 字段、扁平不可变 | T-1-05 | 业务对象图不入 env | unit | `mvn -pl foundation-expression test -Dtest=ArgumentSourceTest` | ❌ W0 | ⬜ pending |
| PERF-04 | 01-08-T1 | 5 | 无无界结构 | T-1-07 | 远程推送不可无界增堆 | unit | `mvn -pl foundation-core test -Dtest=BoundedStructuresTest` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

仓库当前无 `src/`、零测试，因此以下全部为**新建**（Wave 0 必须先落地，测试文件不存在时上表命令以 `No tests were executed` 退出，必须视为红）：

- [ ] JUnit 5 测试骨架：`spring-boot-starter-test` 接入（`foundation-rule` / `foundation-expression` / `foundation-core` 为纯 JUnit 5，不加载 Spring 上下文）
- [ ] `maven-enforcer-plugin` 规则：`dependencyConvergence` + `requireJavaVersion` + `bannedDependencies`（含 `nacos-client` 1.4.2/2.2.0 收敛）
- [ ] `SandboxAttackMatrixTest`（**12 类**攻击用例 = 9 类逃逸 + 1 类资源放大 + `property_syntax` + `env_mutation`，`class` 取值与 `01-08` Task 4 的 12 值封闭枚举表逐条同名同序，构成双射；`01-02` Task 2 的 `AttackVector` 枚举条目数断言为 12；须实际触达 `BudgetExceededException`）
- [ ] `CveBcelAbsenceTest`（**CVE-2021-41862 门**，`01-CONTEXT.md:137` P1 验收门：解析依赖树无 `org.bcel:bcel` + Aviator 5.4.4 jar 内 `com/bcel` 零命中；**不得以攻击矩阵已绿代替**）
- [ ] `SandboxOptionsAssertTest`（沙箱启动反证：`ENABLE_PROPERTY_SYNTAX_SUGAR` 等已被关闭）
- [ ] `StepBudgetTest`（步数预算 + `BudgetExceededException` 触达）
- [ ] `RegexForbiddenTest`（EXPR-08 静态拒绝，含 `=~` 与正则字面量）
- [ ] `ValidationBeforeCompileTest`（EXPR-09：畸形表达式不生成类；规则总数上限触发拒绝）
- [ ] `ExpressionBudgetLimitsTest` / `EvaluationTimeoutTest`（EXPR-06/07）
- [ ] `ArgumentSourceTest` / `EvalContextWhitelistTest`（SEC-07 / EXPR-11，含完整卡号/CVV/持卡人姓名字段 deny 用例）
- [ ] JMH 基准台（`foundation-bench` 或模块内 `*Benchmark.java`）
- [ ] 快照契约测试（`SchemaParseTest` / `SnapshotAtomicityTest` / `ContentHashTest` / `AtomicityPartialFailureTest`）
- [ ] 决策记录契约测试（`DecisionRecordContractTest` / `TagAllowlistTest` / `RingBufferOverflowTest` / `MaskedContextTest`）
- [ ] `DecisionOutcomeCountersTest`（三个独立决策计数器可分别观测；`01-08` Task 1 产出，已绑定到「独立计数器门」行的 `<automated>` 命令）

| 门（不占 Req ID，见括注） | Task ID | Wave | 检查内容 | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|----------------------|---------|------|----------|------------|-----------------|-----------|-------------------|-------------|--------|
| **独立计数器门**（INV-11 / INV-12 / §5 门） | 01-08-T1 | 5 | 「变量缺失未命中」/「求值为 false」/「求值失败按 fail 策略」**三者可分别观测** | T-1-11 | 三条不得互相合并、不得并入 `sandbox_violation` | unit | `mvn -pl foundation-core test -Dtest=DecisionOutcomeCountersTest` | ❌ W0 | ⬜ pending |
| **CVE-2021-41862 复验门**（§5 门） | 01-02-T4 | 2 | Aviator 5.4.4 无 BCEL 依赖与加载面，逐项复验不继承 STATE.md | T-1-04 | 消除 BCEL 类加载这一条远程代码执行路径 | unit | `mvn -pl foundation-expression test -Dtest=CveBcelAbsenceTest` | ❌ W0 | ⬜ pending |
---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| 10 万卡 BIN 基数下的路由表内存与延迟 | GOV-13, PERF-04 | 需真实数据规模与 JMH harness，单元测试规模不足 | 跑 `RoutePlanSizeTest` 基准变体 + `mvn -pl foundation-bench test -Pjmh`，记录 heap 占用与 p99；确认路由表条目数 == 规则数且不随运行时长增长 |
| 第 4 层（表达式求值）p50/p99 实测 | EXPR-04 | 需 JMH 预热与 fork 隔离，单测测不出稳态分布 | `mvn -pl foundation-bench test -Pjmh`；将输出写入 `evidence/jmh-baseline.md`，**以实测值替换任何估算值** |
| Boot 2.6.13 消费方 smoke 启动 | FND-03, FND-10 | 需独立消费方工程与真实 classpath | 新建临时消费方工程引入 SDK，`mvn spring-boot:run` 确认启动成功且无 `foundation.*` bean 缺失 |
| 公共 API 二进制兼容闸门 | FND-04, FND-06 | Revapi/japicmp 需基线 jar | 生成基线 jar 后运行 `mvn revapi:revapi`，确认无 breaking change |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 180s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
