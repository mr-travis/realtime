# Phase 1: 规则内核与表达式沙箱 - Research

**Researched:** 2026-09-29
**Domain:** 规则 schema/版本治理、表达式沙箱、不可变快照、决策记录契约
**Confidence:** HIGH（沙箱与性能结论均来自本会话 JDK 8 实测；CVE 来自 OSV 权威接口）

## Summary

Phase 1 在一个空仓库上落地 13 模块 reactor 的骨架，并冻结三个后续阶段不可回改的契约：规则 schema 版本信封、`DecisionRecord` 九字段集、meter 命名空间。本阶段最大的技术风险不在架构——架构已由 `research/ARCHITECTURE.md` 定案——而在**表达式沙箱的真实边界**。`PROJECT.md:114` 留下的引擎选型 TBD 必须在本阶段以可复现的攻击矩阵关闭。

本会话在 JDK 8 上对 Aviator 5.4.4 做了实测（`evidence/Probe.java`、`Probe2.java`、`Probe3.java`，输出 `evidence/probe3-output.txt`），得出三条改变设计的结论：

1. **`enableSandboxMode()` 关闭代码执行，但没有关闭 ReDoS。** `/pattern/` 字面量配 `=~` 是可用的正则匹配，`s =~ /^(a+)+b$/` 在 26 个 `a` 上耗时 1227ms、40 个 `a` 上 4000ms 未完成；`EVAL_TIMEOUT_MS=1000` **无法打断**，因为 `java.util.regex` 在单步解释器步骤内运行。`string.replace_all(s, '^(a+)+b$', 'x')` 同样可触发。因此 EXPR-08「表达式内禁止正则」不是风格偏好，而是**唯一有效的控制手段**——引擎没有可用的正则时间预算。
2. **沙箱默认值会改写调用方的 env。** `PUT_CAPTURING_GROUPS_INTO_ENV` 在沙箱下仍为 `true`，`(a)(b)` 捕获组直接写入传入的 Map（`$0,$1,$2`）；加上默认 `USE_USER_ENV_AS_TOP_ENV_DIRECTLY=true` 与 `Assignment` 特性，`amount = 999` 可改写业务传入的 env。这与 INV-7/INV-8 直接冲突，必须由 SDK 用每次新建 env + 显式关闭这些 option 来兜底。
3. **LRU 编译缓存不限制元空间。** 20,000 次**合法** `compile()` 精确产生 20,000 个类、Metaspace 提交量 +7MB；`useLRUExpressionCache(50)` 下 5,000 个不同表达式仍产生 5,000 个类。GC 不回收已加载类。`validate()` 20,000 次只 +12 个类——所以 EXPR-09 的「先 validate 再 compile」是唯一有效的元空间保护，规则总数硬上限是第二道必需防线。

`CVE-2021-41862`（CVSS 3.1 = 9.8 CRITICAL，「AviatorScript through 5.2.7 allows code execution via an expression that is encoded with Byte Code Engineering Library (BCEL)」）按 OSV 的受影响范围为 ≤ 5.2.7，**5.4.4 落在范围之外**；且 BCEL 既不是 5.4.4 的依赖，其源码中也不存在任何 BCEL 加载面 `[CITED: https://api.osv.dev/v1/vulns/CVE-2021-41862]`。这**不构成**免除攻击矩阵的理由——`EXPR-03` 的验收标准是矩阵在默认配置下通过，不是版本号比较。

**Primary recommendation:** 沙箱配置必须由 SDK 独占构造（Aviator instance → `enableSandboxMode()` → 显式关闭 `PUT_CAPTURING_GROUPS_INTO_ENV` / `ENABLE_PROPERTY_SYNTAX_SUGAR` / `USE_USER_ENV_AS_TOP_ENV_DIRECTLY` → 关闭 13 个 Feature → 最后设 `EVAL_TIMEOUT_MS`），并在启动时对 `getOption(...)` 逐项断言；表达式步数预算用 `addOpFunction` 实现（实测 ASM 与 INTERPRETER 两种模式**都会**调用 hook），但必须为**全部** `OperatorType` 注册，否则计数是静默失真。

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

以下 25 项为 `/gsd-discuss-phase` 锁定决策，权威来源为 `01-DISCUSS-CHECKPOINT.json`（`01-CONTEXT.md:176`）。**研究这些如何实现，不重新论证。**

#### 不可动摇的不变量（`01-CONTEXT.md:84-100`，违反即为缺陷，不是权衡）

| # | 不变量 |
|---|---|
| INV-1 | 生效版本号 ↔ 生效内容**一一对应**；禁止部分应用（一项规则不合规即该能力段整段不切换） |
| INV-2 | 原子切换、版本单调性、schema 校验**均按能力段**；跨能力互不连累 |
| INV-3 | schema **只加不减**：`deprecated` 字段永不删除、永不改类型、永不改必填性；`removedIn` 恒为 `null` |
| INV-4 | 启动断言必须成立：`maxSchemaVersion[N] >= 该能力已定义最高版本号`，否则 **fail-fast**，不得降级为运行期拒绝 |
| INV-5 | 冷启动无 last-known-good 且配置中心/规则历史不可达 → **进程启动失败**；日志与健康端点须区分「配置中心不可达」与「MySQL 规则历史不可达」 |
| INV-6 | 运行期决策路径**零网络调用**（单节点模式零网络调用是硬约束） |
| INV-7 | env 变量集 **SDK 侧固定、按能力分**；规则不声明变量 |
| INV-8 | **强敏感字段（完整卡号 / CVV / 持卡人姓名）永不进 env**；由 deny-by-default 白名单在代码层保证 |
| INV-9 | 不提供任何**中间档位变量**（消除可拼错且不报错的自由字符串名） |
| INV-10 | 白名单外的 key 使规则**拒绝加载**，不是忽略 |
| INV-11 | 「因变量缺失未命中」与「表达式评估为 false」是**两个独立指标**，可按 `ruleId` 下钻 |
| INV-12 | 「因求值失败按 fail 策略返回」与上一条也是独立指标 |
| INV-13 | 路由表大小 == 已编写规则数，不随运行时长或实体数增长（GOV-13） |
| INV-14 | `DecisionRecord` 字段白名单在 SDK 层强制（SEC-01）：仅 `ruleId`/`ruleVersion`/`dataLine`/`decision`/`remaining`/`bucket`/`latencyUs`/`failStrategy`/`degraded`；禁止原始入参、原始 key、完整表达式文本 |
| INV-15 | 逃逸成功检测**不在 SDK 职责内**；SDK 只上报可观测的逃逸企图，契约不得暗示更多 |

#### 7 条需求修订（`01-CONTEXT.md:104-116`，已就地改写 REQUIREMENTS.md）

| ID | 新要求 | 驱动 |
|---|---|---|
| `FND-05` | 冷启动需配置中心可达，无 LKG 且不可达即启动失败；`STRICT`（默认）/ `RESILIENT`（须显式 opt-in） | 用户架构指令收窄为冷启动硬失败 |
| `GOV-01` | schema 版本按能力分列；`.support` 声明 `maxSchemaVersion[N]`；段内不声明 `schemaVersion`；`ruleSetVersion` 按能力各自单调；只加不减 | 原「双版本信封」导致跨团队推送被静默丢弃 |
| `GOV-03` | 每能力段各自独立不可变快照 + 各自 `AtomicReference` | F1 原子性降为按能力 |
| `GOV-04` | 某能力段校验通过才切换该段，否则该段整段拒绝并保留该能力前一版本 | F1/F2 |
| `GOV-06` | 按能力段原子拒绝该段，保留该能力前一版本 | F1/F2 |
| `GOV-11` | 冷启动无 LKG 且不可达即 fail-fast；运行期靠内存 LKG；启动断言校验 `maxSchemaVersion` | 附带消解 GOV-05 与原 GOV-11 的自相矛盾 |
| `RL-19` | 清算入站 fail-open 在受版本管理的 `dataLine` 注册表中固定 + **守护测试钉住**（反转即测试失败）+ code-review 检查项 | 护栏意图保留、机制平移 |

#### 4 个决策区的结论

- **2.1 数据线表达**：`dataLine` 是受版本管理的注册表项 `{name, defaultFailStrategy}`；SDK 代码内**无任何业务线名**（`01-CONTEXT.md:162` 禁止项）。
- **2.2 schema 版本策略**：见 GOV-01 修订行。
- **2.3 env 变量契约**：SDK 侧固定、按能力分，规则不声明变量（INV-7）；无中间档位变量（INV-9）。
- **2.4 沙箱逃逸处置粒度**：按能力段整段拒绝 + `sandbox_violation` 计数（`01-CONTEXT.md:131` EXPR-03 门）。

#### Phase 1 必须冻结、不可推迟的东西（`01-CONTEXT.md:11-24`）

`DecisionRecord` 字段集与原因码、meter/计数器契约、规则集版本信封。理由：决策历史**不可回填补齐**（`ARCHITECTURE.md:1026` 标注 `DecisionRecord` 字段集归属 P1，drift 即 P5 无法恢复）。

### the agent's Discretion

以下 10 项**不需要再问用户**，是规划必须自行设计并落成任务的（`01-CONTEXT.md:141-156`，原文）：

1. 每条能力 env 变量集的**最终字段清单与类型**（`amount`=long/分已定，其余逐条确认）。
2. 每条能力变量集大小的**上限**，并纳入 `EXPR-04` JMH 基准。
3. **反射实现细节**：深度上限、集合/`Map` 展开策略、循环引用保护、反射元数据缓存结构（不缓存必击穿 P99）。
4. **白名单的承载形态**：代码常量 / 注解 / 配置，以及它如何与启动断言联动。
5. `RuleHistoryStore` / `ConfigSource` 的**接口签名冻结**（不冻结实现）。
6. schema 定义文件与 `.support` 文件的**具体形态**（目录布局、字段声明格式）。
7. **冷启动重试上限**与最终失败行为（Nacos 与 MySQL 各自）。
8. 各独立计数器的**名称与维度**，以及与 meter 契约的对应。
9. 发布通道的**段合并逻辑**归属与实现位置（SDK 侧不做合并）。
10. `sandbox_violation` 指标与其他失败分类计数器的**命名与聚合边界**。

本研究为上述各项给出**建议方案**（见 `## Recommended Designs for Discretion Items`），供 planner 采纳或调整。

### Deferred Ideas (OUT OF SCOPE)

- P2 限流执行、集群令牌层、Sentinel 接入、ClusterGate（`RL-01`..`RL-20`）
- P3 灰度评估器（`CAN-*`）
- P4 两级缓存（`CACHE-*`）
- P5 决策通道传输层：独立 appender / HTTP batch sink / 健康端点 / `MeterBinder` 自报（`OBS-01`、`OBS-04`、`OBS-08`、`OBS-10`..`OBS-14`、`SEC-01` 的日志侧、P15 规则历史落库）
- P6 敏感数据路径清单、生产压测报告（`SEC-05`、`SEC-06`、`PERF-06`、`PERF-07`）
- 控制台界面子应用（`API-*` 全部在 P5/P6）

**注意**：`OBS-09`（meter 预注册、tag 词汇表有界）标注 P1 交付，`OBS-02`/`OBS-03`（`DecisionRecord` 契约冻结）标注 P1——这些**在范围内**，但只交付**契约与预注册**，不交付传输。
</user_constraints>

<phase_requirements>
## Phase Requirements

`REQUIREMENTS.md:393` 声明 Phase 1 共 48 条。下表逐条给出研究支撑来源。完整原文见 `REQUIREMENTS.md:1-181`。

### 库基础 FND（10 条）

| ID | 摘要 | 研究支撑 |
|---|---|---|
| FND-01 | 公开 API 任意业务线程线程安全可重入 | `AtomicReference` 快照（Pattern 1）；`ArgumentSource` 不可变 + 每次新建 env（`evidence/probe3-output.txt:62` 证明可变 env 是真实风险） |
| FND-02 | 集成依赖全部 `optional`/`provided` | Maven `dependencyManagement` + 模块级 scope；`ARCHITECTURE.md:81` C1 零运行时依赖 |
| FND-03 | 非 Spring 宿主可启动 | `foundation-autoconfigure` 作为 Spring 防火墙（`ARCHITECTURE.md:214`）；手工构造路径 |
| FND-04 | 公共 API 不泄漏 Sentinel/Nacos/Caffeine 类型 | `ARCHITECTURE.md:143-148` C1 只暴露自有类型；`ExpressionEngine` SPI（EXPR-12）作为泄漏闸门 |
| FND-05 | 冷启动 fail-fast；`STRICT` 默认 / `RESILIENT` opt-in | 修订行（`01-CONTEXT.md:110`）；INV-5；开放项 7 |
| FND-06 | semver，deprecate-then-remove | INV-3 只加不减；`.support` 文件版本上限 |
| FND-07 | 启动断言并暴露实际解析版本 | 同 EXPR-02 断言机制；`maven-enforcer-plugin` `dependencyConvergence` 保证基线 |
| FND-08 | 父 POM `bannedDependencies` 防火墙 | `ARCHITECTURE.md:1015` 阶段表「Spring firewall」；排除 `autoconfigure`/`console-api` |
| FND-09 | 规则无 TTL，无同步重试 | GOV-08 配置删除退回安全默认；INV-6 |
| FND-10 | 全部配置 `foundation.*` 前缀 opt-in | `ARCHITECTURE.md:217` `@ConfigurationProperties` 全部 `foundation.*` |

### 规则治理 GOV（15 条）

| ID | 摘要 | 研究支撑 |
|---|---|---|
| GOV-01 | schema 按能力分列 + `.support` 声明上限；段内不声明 `schemaVersion` | 修订行（`01-CONTEXT.md:111`）；INV-2/INV-3/INV-4；开放项 6 |
| GOV-02 | `ruleSetVersion` 回退即拒绝 | INV-2 单调性；`RuleSetSnapshot.version`（`ARCHITECTURE.md:287-301`） |
| GOV-03 | 每能力独立不可变快照 + 单次 `AtomicReference` 切换 | 修订行（`:112`）；Pattern 1（`ARCHITECTURE.md:271-307`） |
| GOV-04 | 部分应用结构上不可能 | 修订行（`:113`）；INV-1；Pattern 1 |
| GOV-05 | LKG temp-file + `ATOMIC_MOVE` | `ARCHITECTURE.md:171` `LastKnownGoodStore`；GOV-12 磁盘可写性 |
| GOV-06 | 按能力段原子拒绝，校验关系约束 | 修订行（`:114`）；`RuleValidator`（`:172`） |
| GOV-07 | Nacos 回调仅置脏标记 + ~200ms 去抖 | `ARCHITECTURE.md:210` `RuleChangeListener`；顺序校验防乱序重复应用 |
| GOV-08 | 配置删除退回内置静态安全默认 | `ARCHITECTURE.md:421-440` Pattern 6「降级是规则不是分支」 |
| GOV-09 | 启动断言 namespace/group/dataId 白名单 | `ARCHITECTURE.md:209` `NacosConfigKeys`；PITFALLS.md:30-31 写入白名单 |
| GOV-10 | 内容哈希不匹配即拒绝 | `RuleSetSnapshot.contentHash`（`ARCHITECTURE.md:291`） |
| GOV-11 | 冷启动 fail-fast + 启动断言 `maxSchemaVersion` | 修订行（`:115`）；INV-4/INV-5 |
| GOV-12 | 监控 Nacos 快照目录可写性 | PITFALLS.md 磁盘满放大降级；独立 meter |
| GOV-13 | 路由表大小 == 规则数 | INV-13；PITFALLS.md:90-112 基数控制 |
| GOV-14 | 编译缓存以归一化文本为 key，有界 LRU + 淘汰指标 | PITFALLS.md:99；**实测 LRU 不约束类生成**（`evidence/probe3-output.txt:49-50`）→ 见 Pitfall 7 |
| GOV-15 | 差量替换而非全量重建 | PITFALLS.md:102；`RoutePlan` 复用 |

### 表达式沙箱 EXPR（12 条）

| ID | 摘要 | 研究支撑 |
|---|---|---|
| EXPR-01 | 禁用 SpEL（含 `SimpleEvaluationContext` 包裹） | 依赖树零 `spring-expression` 断言 + code-review 项；PITFALLS.md:633 |
| EXPR-02 | Aviator 5.4.4 沙箱模式 + 启动断言未被关闭 | **实测无 `isSandboxMode()`**，须读 option 断言（`evidence/probe3-output.txt:52-69`）；`AviatorEvaluatorInstance.java:470-481` |
| EXPR-03 | JDK 8 攻击矩阵默认配置全通过 | `## Sandbox Attack Matrix`（9 类，含实测 ReDoS 确认） |
| EXPR-04 | JMH 实测替换 MEDIUM 置信度数字 | 方向性数据：预编译 `execute` 81ns/op、`execute(String,env)` 38,610ns/op；正式 JMH 仍需 P2/P5 基准台（`PERF-05`） |
| EXPR-05 | 步数预算为主约束，超限抛 `BudgetExceededException` | **实测 `addOpFunction` 在 ASM 与 INTERPRETER 均被调用**（`evidence/probe3-output.txt:37-41`）；`Options` 无原生 step 预算 |
| EXPR-06 | 超时兜底须独立有界线程池 + 拒绝策略 + 导出指标 | PITFALLS.md:73 禁止 `Thread.interrupt`/超时线程池污染；开放项 |
| EXPR-07 | 长度 512 / AST 128 / 深度 5 | Aviator 无 AST 节点数访问器 → 需 `validate()` + 词法/字符预算近似，见 Pitfall 4 |
| EXPR-08 | 表达式内禁止正则 | **实测 ReDoS 确认且 `EVAL_TIMEOUT_MS` 无法打断**（`evidence/probe3-output.txt:9-21`）→ 唯一有效控制 |
| EXPR-09 | 编译前先 `validate()` 防元空间耗尽 | **实测 20,000 次 validate 仅 +12 类 vs 20,000 次合法 compile +20,000 类、+7MB Metaspace**（`evidence/probe3-output.txt:43-47`） |
| EXPR-10 | 按 `ruleId` 导出慢求值计数 | 需有界 tag（SEC-04）；`evalNanos` 已在 `DecisionRecord`（`ARCHITECTURE.md:412`） |
| EXPR-11 | 只能访问显式 `EvalContext` 白名单字段 | INV-7/INV-8/INV-10；Pattern 2 `ArgumentSource`（`ARCHITECTURE.md:309-334`） |
| EXPR-12 | `ExpressionEngine` SPI 保留 | `ARCHITECTURE.md:152-155` 模块边界；FND-04 不泄漏 Aviator 类型 |

### 可观测性 OBS（6 条 Phase 1）

| ID | 摘要 | 研究支撑 |
|---|---|---|
| OBS-02 | `DecisionRecord` 字段集 P1 冻结 | INV-14 九字段；`ARCHITECTURE.md:404-415` |
| OBS-03 | `ruleVersion` 为快照不可变字段 | Pattern 5（`ARCHITECTURE.md:390-419`）；`RuleSetSnapshot` 构造即冻结 |
| OBS-05 | 有界 ring buffer + 批量写出 | `ARCHITECTURE.md:157-164` `RingBufferSink`（Disruptor） |
| OBS-06 | 满时丢弃并计数，绝不阻塞 | `ARCHITECTURE.md:399`；`ARCHITECTURE.md:749` 满缓冲丢弃路径是病态用例 |
| OBS-07 | 决策记录不得以 debug 门控 | `ARCHITECTURE.md:739` step 8 预算 `< 1 µs` |
| OBS-09 | 单一低基数命名空间；meter 预注册、tag 词汇有界 | SEC-04；`FoundationMeters`（`ARCHITECTURE.md:163`）；Anti-Pattern 9（`:947`） |

### 敏感数据 SEC（4 条 Phase 1）

| ID | 摘要 | 研究支撑 |
|---|---|---|
| SEC-02 | `EvalContext` 字段级脱敏 + 显式 debug 开关（默认关） | `ArgumentSource.masked()`（`ARCHITECTURE.md:332`） |
| SEC-03 | 脱敏为 SDK 默认行为 | 白名单承载形态（开放项 4）；`ARCHITECTURE.md:317` 投影是唯一执行点 |
| SEC-04 | 指标标签白名单：有界枚举 + `ruleId` | OBS-09；明细进决策记录 |
| SEC-07 | `ArgumentSource` 统一 PAN 掩码执行点，≤64 字段、扁平不可变 | SEC-07 原文；`ARCHITECTURE.md:324-333` `values.size() > 64` 断言 |

### 性能 PERF（1 条 Phase 1）

| ID | 摘要 | 研究支撑 |
|---|---|---|
| PERF-04 | 无无界结构：路由 LRU / ring buffer / `appliedSeq` / single-flight 均有界 | `ARCHITECTURE.md:757`；**实测 LRU 不约束元空间 → 规则总数需额外硬上限**（`evidence/probe3-output.txt:49-50`） |
</phase_requirements>
## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 规则 schema 定义与版本上限声明（`.support`） | API / Backend（`foundation-rule` + `foundation-core`） | 控制台（`foundation-console-api`，P5+） | 校验必须在 SDK 侧，INV-4 启动断言是 SDK 职责；控制台只编辑不裁决 |
| 载荷解析 → 关系校验 → 表达式编译 → 快照 | API / Backend（`foundation-rule`） | — | 离决策路径；`foundation-rule` 无 Nacos/Spring/Sentinel，可纯 JUnit 测（`ARCHITECTURE.md:259`） |
| 表达式沙箱与求值预算 | API / Backend（`foundation-expression`） | — | 进程内求值，与业务同权限；独立模块使审计成为模块级评审（`ARCHITECTURE.md:258`） |
| 限流/灰度/缓存的 env 字段白名单与掩码 | API / Backend（`foundation-core` 的 `ArgumentSource` 契约） | 能力模块实现投影 | 掩码必须在唯一执行点强制（`ARCHITECTURE.md:317`） |
| 能力段原子切换 | API / Backend（各能力段各自 `AtomicReference`） | — | INV-2；热路径只读 |
| last-known-good 落盘 | 本地文件系统（`LastKnownGoodStore`） | — | temp-file + `ATOMIC_MOVE`，GOV-05 |
| 规则历史读写 | Database / Storage（MySQL，`RuleHistoryStore`） | — | 决策路径零 IO（INV-6）；接口在 P1 冻结、实现后续 |
| 配置下发 | 外部配置中心（Nacos，经 `foundation-nacos`） | — | C8 是唯一知道 Nacos 存在的模块（`ARCHITECTURE.md:206`） |
| meter 预注册与 tag 词汇表 | API / Backend（`FoundationMeters`） | Prometheus 抓取（宿主负责） | SDK 暴露 meter，不拥有抓取端点（`ARCHITECTURE.md:991`） |
| 决策记录字段白名单 | API / Backend（`foundation-core`） | 传输层 P5 | P1 冻结字段集，历史不可回填（OBS-02/OBS-03） |
| 前端 / CDN | — | — | 本阶段无浏览器或静态资产面 |

**Tier 校验结论**：Phase 1 全部能力落在 API/Backend 与本地存储两档。不存在把认证、鉴权或业务逻辑放进 SDK 层的空间；`foundation-core` 是唯一跨平面契约（`ARCHITECTURE.md:257`），若控制台将来需要数据平面的第二个类，即为 schema 设计错误的信号。

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java (JDK) | 1.8 | Runtime target | 项目固定。无虚拟线程、`javax.*` 命名空间，字节码 major ≤ 52 |
| Spring Boot | 2.7.6 | Context / config / actuator | 项目固定；`META-INF/spring.factories` 风格（Boot 2.x，非 `AutoConfiguration.imports`） |
| Spring Cloud | 2021.0.5.0 | Release train | 与 SCA 2021.0.5.0 精确配对 |
| Spring Cloud Alibaba | 2021.0.5.0 | Nacos 集成 + **版本 BOM** | BOM 是 Sentinel / Nacos client 版本的**唯一权威**，不得手工 pin |
| Aviator | 5.4.4 | 沙箱表达式求值 | 最后一条 Java 8 线；`enableSandboxMode()` 自 5.4.3 起可用 |
| Nacos client | 2.2.0 | 配置中心 | 由 SCA BOM 解析；见下方陷阱 |
| Sentinel (`sentinel-core`) | 1.8.6 | 本地滑动窗口 | SCA BOM 解析 `sentinel.version=1.8.6` |
| Caffeine | 2.9.3 | L1 缓存 | 最后一个 Java 8 线（3.0.0+ 需 Java 11）；Boot 管理 |
| Lettuce | 6.1.10.RELEASE | Redis 客户端 | Boot 管理；Netty 共享连接，热路径不逐次取 socket |
| Micrometer | 1.9.6 | 指标门面 | Boot 管理；OBS-09 / SEC-04 契约载体 |
| Jackson | 2.13.4.20221013 | Nacos 载荷 JSON | Boot BOM 管理 |
| SLF4J / Logback | 1.7.36 / 1.2.11 | 日志 | Boot 管理。**Logback 1.3+/2.x 需 SLF4J 2.x，配错会静默丢失全部日志** |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `sentinel-datasource-nacos` | 1.8.6 | 规则持久化到 Nacos | 必需；**必须配 `nacos-client` exclusion** |
| `sentinel-parameter-flow-control` | 1.8.6 | `ParamFlowRule` per-card 计数 | P2 必需 |
| `micrometer-registry-prometheus` | 1.9.6 | Prometheus 端点 | 必需；传递引入 `simpleclient_common:0.15.0`，**不要独立 pin** |
| `spring-boot-starter-test` | 2.7.6 | JUnit Jupiter 5.8.2 | 测试 |
| `maven-enforcer-plugin` | 3.x | `dependencyConvergence` + `requireJavaVersion` | **必需**，见陷阱 |
| JMH | 1.20（`provided`，见下） | 热路径基准 | EXPR-04 / PERF-05；**Aviator 5.4.4 自身以 `provided` 声明 JMH 1.20，本项目可用同版本** |

**Aviator 5.4.4 的完整依赖集** `[VERIFIED: aviator-5.4.4.pom]`：

```
junit 4.13.1 (test) | mockito-all 1.10.19 (test) | spring-context 5.3.36 (provided)
jmh-core 1.20 (provided) | jmh-generator-annprocess 1.20 (test) | beetl 3.1.7.RELEASE (test)
```

**零运行时依赖**——这对 FND-02/FND-03 是决定性的：引入 Aviator 不会给接入方带来任何传递依赖。

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Aviator 5.4.4 | QLExpress4 4.1.3 | 默认隔离模式、不生成类（无元空间曲线），但是解释器，热路径吞吐预期更低，**须先 JMH** |
| Aviator 5.4.4 | 自研受限 DSL | 最高成本：自建解析、错误信息、运算符文档、完整测试面。仅当合规拒绝嵌入任何脚本引擎时采用 |
| Aviator 沙箱 | Sentinel 内建集群模式 | **不采用**：实测其 `ChannelPromise.await(requestTimeout)` 在调用线程阻塞等待，`requestTimeout` 默认 **20 ms**，调用方无法约束；且多一个常驻部署件 |
| Lettuce | Jedis 3.8.0 | 仅当宿主已统一 Jedis 且不接受第二 Redis 客户端；勿同时上 classpath |

### What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| SpEL | 非沙箱：暴露方法调用、类型引用、bean 解析；配置下发的表达式等同远程代码执行 | Aviator 沙箱 |
| `sentinel-cluster-client-default` / `-server-default` | 20 ms 调用线程阻塞 + 额外常驻服务 + 无自身 failover | Sentinel 本地模式 + Redis Lua（Phase 2） |
| `sentinel-datasource-redis` | 引入第二个规则事实源，与 GOV 决策冲突 | `sentinel-datasource-nacos` |
| `nacos-client` 1.4.x | `sentinel-datasource-nacos:1.8.6` 硬声明的版本；与 Nacos 2.x server 报 `NoSuchMethodError` | `nacos-client` 2.2.0 + 显式 `<exclusion>` |
| `useLRUExpressionCache` 当作元空间保护 | **实测无效**：LRU(50) 下 5,000 个不同表达式仍生成 5,000 个类 | `validate()` 前置 + 规则总数硬上限 + Metaspace 监控 |
| 嵌入式/模拟 Redis | 伪造了令牌层存在的意义（延迟、故障转移） | Testcontainers 或预发真实实例（P2） |
| Sentinel Dashboard 作为规则编辑 UI | 写入是 dashboard 节点内存态，与 Nacos 分叉且重启丢失 | Nacos 单一事实源 |

## Package Legitimacy Audit

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `com.googlecode.aviator:aviator:5.4.4` | Maven Central | 2010 起 | 高（长期流行） | github.com/killme2008/aviatorscript | **OK** | Approved（SHA-256 `5432380636ad8b90038a5ec47f06beb3f32fcc95759cb68f0cfaba8253cb4798`） |
| `com.alibaba.csp:sentinel-core:1.8.6` | Maven Central | 2018 | 高 | github.com/alibaba/Sentinel | **OK** | Approved（SCA BOM 解析） |
| `com.alibaba.csp:sentinel-datasource-nacos:1.8.6` | Maven Central | 2018 | 中 | github.com/alibaba/Sentinel | **OK**（含 `nacos-client` 1.4.2 陷阱） | Approved + 强制 exclusion |
| `com.alibaba.nacos:nacos-client:2.2.0` | Maven Central | 2022 | 高 | github.com/alibaba/nacos | **OK** | Approved |
| `com.github.ben-manes.caffeine:caffeine:2.9.3` | Maven Central | 2015 | 极高 | github.com/ben-manes/caffeine | **OK** | Approved |
| `io.lettuce:lettuce-core:6.1.10.RELEASE` | Maven Central | 2016 | 极高 | github.com/redis/lettuce | **OK** | Approved |
| `io.micrometer:micrometer-registry-prometheus:1.9.6` | Maven Central | 2019 | 极高 | github.com/micrometer-metrics | **OK** | Approved |
| `org.openjdk.jmh:jmh-core:1.20` | Maven Central | 2016 | 高 | github.com/openjdk/jmh | **OK** | Approved（`provided`） |

**SLOP verdict 移除**：无。
**SUS 标记**：无。

Phase 1 仅需上表 Core 子集 + JMH；Sentinel/Redis 相关 artifact 在 Phase 2 接入，但父 POM 与 enforcer 在 Phase 1 建立（`ARCHITECTURE.md:1015` 阶段表：P1 交付「Module skeleton with the full reactor + enforcer」）。

## CVE 复验（`01-CONTEXT.md:137` 强制门）

`CVE-2021-41862` 不得继承 STATE.md 结论，逐项复验结果：

| 项 | 复验结果 | 来源 |
|---|---------|------|
| 描述 | "AviatorScript through 5.2.7 allows code execution via an expression that is encoded with Byte Code Engineering Library (BCEL)." | `[CITED: https://api.osv.dev/v1/vulns/CVE-2021-41862]` |
| 别名 | `GHSA-xpv2-8ppj-79hh` | 同上 |
| CVSS | `CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` → 基础分 **9.8 CRITICAL** | 同上 |
| 受影响范围 | `through 5.2.7`（枚举清单止于 `aviator-5.2.6`；GIT fixed = `b29a15a5a8f4b38cc9afb9872c6ddf6b51677078`） | 同上 |
| **5.4.4 是否受影响** | **否**——5.4.4 落在受影响范围之外（这是**显式上界**约束，非「元数据缺失」） | 同上 |
| BCEL 依赖 | 5.4.4 POM **未声明** `bcel`/`bytecode` 任何依赖 | `[VERIFIED: aviator-5.4.4.pom]` |
| BCEL 加载面 | 5.4.4 源码中 `bcel` / `ClassDef` 零命中；`ClassDefiner.defineClass` 只用于 ASM 生成**自身**编译产物 | `[VERIFIED: aviator-5.4.4-sources.jar → com/googlecode/aviator/code/asm/ClassDefiner.java:70-75]` |
| 结论 | CVE-2021-41862 不适用于 5.4.4，**但 EXPR-03 攻击矩阵仍须全通过** | — |

**不得据此结论弱化矩阵。** CVE 描述的是 BCEL 类加载这一条具体路径；它被消除不等于 5.4.4 沙箱没有其他缺口——本节后续实测即发现 ReDoS、env 改写、属性语法泄漏三类 CVE 未覆盖的问题。

## Sandbox Attack Matrix（EXPR-03，`01-CONTEXT.md:131` 强制门）

全部在 **JDK 8 / Aviator 5.4.4 / 默认配置**下实测，证据归档于 `evidence/`（`Probe*.java` + `probe3-output.txt`）。处置粒度按 §2.4：**按能力段整段拒绝 + `sandbox_violation` 计数**。

| # | 攻击类别 | 实测结果 | 逃逸? | 确定的期望行为 |
|---|---|---|---|---|
| 1 | 反射逃逸（`dto.getClass()` / `list.size()` / `map.size()`） | `function_missing` 抛异常 | 否 | 抛 `SandboxViolation`；段整段拒绝；`sandbox_violation{class="reflection"}` |
| 2 | 构造器逃逸（`new java.lang.ProcessBuilder(...)`） | `Feature.NewInstance` 已禁用 | 否 | 同上，`class="constructor"` |
| 3 | 静态方法（`Runtime.getRuntime()` / `Class.forName`） | `Feature.StaticMethods` 已禁用 + `setFunctionMissing(null)` | 否 | 同上，`class="static_method"` |
| 4 | `ClassLoader`/`Thread`/`Runtime` 链 | 空 `ALLOWED_CLASS_SET` 阻断 | 否 | 同上，`class="classloader"` |
| 5 | 文件与网络 IO（`IoModule`） | `removeModule(IoModule)` 已移除 | 否 | 同上，`class="io"` |
| 6 | **ReDoS**（`s =~ /^(a+)+b$/`） | **确认逃逸可用性边界**：n=26 → 1227ms；n=40 → 4000ms 未完成 | **可用（超出预算）** | 编译期**拒绝含正则字面量或 `=~` 的表达式**（EXPR-08）；拒绝即 `sandbox_violation{class="redos"}`，不进入运行期 |
| 6b | ReDoS（`string.replace_all(s, '^(a+)+b$', 'x')`） | 4000ms 未完成 | **同上** | 同上；`string.replace_all` / `replace_first` / `split` 三个函数须在白名单中**排除** |
| 7 | 死循环（`while(true)`） | `MAX_LOOP_COUNT=65535` → 约 109ms 后抛异常 | 否（有界） | `BudgetExceededException`；`sandbox_violation{class="infinite_loop"}` |
| 7b | 深递归 | 产生 `StackOverflowError`（**`Error` 非 `Exception`**） | 否（但**会逃过 `catch (Exception)`**） | 求值边界必须 `catch (Throwable)`；编译期禁用递归特性；`class="stack_overflow"` |
| 8 | 超大字符串与数组 | `range(0, 20000000)` 返回惰性 `Range`，不立即分配 | 部分（惰性） | 步数预算覆盖遍历成本；AST 节点上限 128（EXPR-07）；`class="resource_amplification"`（分类依据见 `01-08` Task 4 的 12 值封闭枚举表） |
| 9 | **属性语法泄漏**（`amount.class`） | `ENABLE_PROPERTY_SYNTAX_SUGAR=true`（默认）→ 返回 `class java.lang.Long`；**置 false 后返回 `null`** | **是（信息泄漏）** | 启动即 `setOption(ENABLE_PROPERTY_SYNTAX_SUGAR, false)`，并在启动断言中校验；`class="property_syntax"` |
| 9b | **env 改写**（`amount = 999` / `let a = 1` / 正则捕获组） | `amount=999` 改写调用方 Map；`let a=1` 注入 `$a`；`(a)(b)` 捕获组写入 `$0,$1,$2` | **是** | 关闭 `Assignment`/`Let` Feature + `USE_USER_ENV_AS_TOP_ENV_DIRECTLY=false` + `PUT_CAPTURING_GROUPS_INTO_ENV=false`；每次决策新建 env；`class="env_mutation"` |
| 10 | 深嵌套（300 项 / 200 层函数） | 均可成功求值 | 否（无硬上限） | 靠 AST 节点 128 + 深度 5（EXPR-07）；`class="deep_nesting"` |

**矩阵的关键结论**：

- 第 6/6b/9/9b 四类是**默认配置下真实可用的越界路径**，其中 6/6b 是 ReDoS（PITFALLS.md:64-86 预言的场景已实证），9/9b 是 CVE 未覆盖的独立缺口。**这四类必须由 SDK 主动关闭，不能依赖 `enableSandboxMode()`。**
- `EVAL_TIMEOUT_MS` **不是**这些问题的解决方案：正则匹配在单步内完成，超时检查无法插入（实测 n=40 超过 4000ms 仍未完成，而 `EVAL_TIMEOUT_MS=1000`）。这直接支持 EXPR-05「步数预算为主约束、线程池超时不得作为主约束」。
- 逐条期望行为已按 §2.4 定义为「段整段拒绝 + 计数」，可直接作为测试断言的 golden 值。

## Architecture Patterns

### System Architecture Diagram

```
                    ┌──────────────── 控制面（Phase 5/6，本阶段仅冻结契约）────────────────┐
                    │  foundation-console-api: 规则 CRUD / 发布 / 审计 / 回滚            │
                    │  依赖 foundation-core 唯一契约（不依赖数据面其他模块）              │
                    └───────────────────────────────┬───────────────────────────────────┘
                                                    │ 发布（段合并归发布通道，SDK 不做）
                                                    ▼
  ┌──────────────────────── 配置面（离决策路径） ────────────────────────┐
  │ Nacos server                                                           │
  │   │ gRPC push（仅"变了"信号，不携带内容）                              │
  │   ▼                                                                     │
  │ C8 foundation-nacos                                                    │
  │   RuleChangeListener ──置脏标记 + ~200ms 去抖──┐                      │
  │   NacosSnapshotLoader ──重拉完整载荷───────────┤                      │
  └───────────────────────────────────────────────┼──────────────────────┘
                                                  ▼
  ┌────────────────────────── 规则面（离决策路径） ─────────────────────┐
  │ C4 foundation-rule                                              │
  │   RuleValidator ──结构 + 关系约束（GOV-06）                        │
  │   RuleCompiler  ──① validate() ② compile(cached) ③ 规则数硬上限   │
  │        │                                                          │
  │        ├─► C2 foundation-expression  沙箱实例 + 步数预算           │
  │        │      SandboxPolicy / CompiledExpression / ValidationResult│
  │        ▼                                                          │
  │   RuleSetSnapshot（不可变，构造时冻结 ruleVersion）                │
  │        │  每能力段独立快照 + 独立 AtomicReference（GOV-03）        │
  │        ├─► SnapshotStore（接口）   LastKnownGoodStore（落盘）       │
  │        └─► contentHash（GOV-10）                                    │
  └────────────────────────────────┬───────────────────────────────────┘
                                   │ 单次 volatile 读（INV-6 零网络）
                                   ▼
  ┌────────────────────────── 决策面（热路径 1–8 层） ─────────────────┐
  │ 1. AtomicReference<RuleSetSnapshot> 读                              │
  │ 2. RoutePlan 按 injectionPointId 查（HashMap.get, O(1)）            │
  │ 3. ArgumentSource 投影（≤64 字段、扁平不可变、掩码唯一执行点）      │
  │ 4. 预编译 Expression 求值（沙箱 + 步数预算）                        │
  │ 5–6. 能力执行（Phase 2/3/4）                                       │
  │ 7. Decision 组装                                                   │
  │ 8. DecisionRecord.emit() → 有界 ring buffer（非阻塞、满则丢+计数）│
  └────────────────────────────────┬───────────────────────────────────┘
                                   ▼
  ┌──────────────── 可观测面 ────────────────────────────────────────┐
  │ C3 foundation-observer（本阶段仅契约 + meter 预注册，传输在 P5）   │
  │   DecisionRecord 字段白名单（INV-14 九字段）                      │
  │   FoundationMeters：meter 预注册 + 有界 tag 词汇表（OBS-09/SEC-04）│
  └──────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure

采用 `ARCHITECTURE.md:141-253` 的 13 模块 reactor，Phase 1 交付全部模块骨架 + enforcer。Phase 1 重点实现 `foundation-parent`（BOM + 防火墙）、`foundation-core`（C1 契约）、`foundation-expression`（C2）、`foundation-observer`（C3 契约部分）、`foundation-rule`（C4）、`foundation-autoconfigure`（C9）；`foundation-nacos`（C8）本阶段只需骨架 + key 常量。

### Pattern 1: 沙箱实例的**有序**构造 + 启动断言（本研究新增，EXPR-02）

**What**：`enableSandboxMode()` 会重置多个 option（`AviatorEvaluatorInstance.java:470-481` 逐行可见），因此**顺序敏感**；且引擎**没有** `isSandboxMode()` 访问器，必须靠读 option 反证。

```java
AviatorEvaluatorInstance inst = AviatorEvaluator.newInstance();   // 禁用静态门面
inst.enableSandboxMode();                    // ① 先沙箱
inst.setOption(Options.PUT_CAPTURING_GROUPS_INTO_ENV, false);
inst.setOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR, false);
inst.setOption(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY, false);
inst.setOption(Options.NIL_WHEN_PROPERTY_NOT_FOUND, false);
for (Feature f : DISALLOWED) inst.disableFeature(f);   // ② 缩窄，含 Assignment/Let/ForLoop/WhileLoop/Lambda/Fn/…
inst.setOption(Options.MAX_LOOP_COUNT, budget.maxLoopCount());
inst.setOption(Options.EVAL_TIMEOUT_MS, budget.evalTimeoutMs());   // ③ 超时必须最后
inst.useLRUExpressionCache(compiledExpressionCacheSize);           // ④ 仅 instance 有此 API
```

**启动断言**（无 `isSandboxMode()`，故反证）：

```java
Set<Feature> fs = (Set<Feature>) inst.getOption(Options.FEATURE_SET);
if (!fs.isEmpty()) throw new IllegalStateException("sandbox feature set not empty: " + fs);
if (!((Set<?>) inst.getOption(Options.ALLOWED_CLASS_SET)).isEmpty()) throw ...;
if (!Boolean.FALSE.equals(inst.getOption(Options.PUT_CAPTURING_GROUPS_INTO_ENV))) throw ...;
if (!Boolean.FALSE.equals(inst.getOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR))) throw ...;
if (!Boolean.FALSE.equals(inst.getOption(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY))) throw ...;
```

实测沙箱后的 `FEATURE_SET` 并**不**为空，仍剩 13 项：`[Assignment, ForLoop, StaticFields, Lambda, WhileLoop, LexicalScope, StringInterpolation, Fn, Return, Use, If, ExceptionHandle, Let]` `[VERIFIED: evidence/probe3-output.txt:64]`。因此「沙箱 = feature set 为空」是**错误断言**；正确断言是「feature set ⊆ 期望白名单」。

**Trade-off**：`EVAL_TIMEOUT_MS` 放最后是因为 `enableSandboxMode()` 内含 `setOption(EVAL_TIMEOUT_MS, 1000L)`（`:479`），放在其后会被静默重置。

### Pattern 2: 步数预算 = `addOpFunction` 包装（本研究实测确认可行）

**What**：Aviator 无原生 `MAX_EVAL_STEP`。实测 `addOpFunction(OperatorType, AviatorFunction)` 注册的 operator hook 在 **ASM 与 INTERPRETER 两种模式下都会被调用**（`evidence/probe3-output.txt:37-41`：`opHooksFired=1`，表达式含 11 次加法，hook 生效且结果正确）。`AbstractFunction` 提供全部 arity 的默认实现，只需覆写关心的 `call` 重载。

```java
private static final class StepBudgetFunction extends AbstractFunction {
    private final String name;
    private final AviatorFunction delegate;      // 原实现，必须保留语义
    private final AtomicLong counter;            // 每 evaluator 一个预算窗口
    private final long limit;

    @Override public String getName() { return name; }

    @Override
    public AviatorObject call(Map<String,Object> env, AviatorObject a1, AviatorObject a2) {
        if (counter.incrementAndGet() > limit)
            throw new BudgetExceededException(...);
        return delegate.call(env, a1, a2);
    }
}
```

**必须为全部会被规则用到的 `OperatorType` 注册**（`ADD SUB MULT DIV MOD EQ NEQ LT LE GT GE AND OR INDEX MATCH TERNARY …`）。只注册一部分会让未注册算子**不计数**——预算形同虚设且不报错，属于典型的静默失效（`01-CONTEXT.md:166` 禁止项）。注册后必须有断言测试：构造一个超过 limit 的表达式，断言抛 `BudgetExceededException`。

**Trade-off**：每个算子多一层虚调用。这是本阶段**唯一**的求值内预算手段，且 ASM 模式同样生效；替代方案（独立线程池超时）被 EXPR-06 与 PITFALLS.md:73 明确限制。**开销必须用 JMH 测量**（EXPR-04），本研究的简单循环数据不足以定论。

### Pattern 3: 编译期静态成本闸门（EXPR-07/EXPR-08/EXPR-09 的实际落点）

Aviator 不暴露 AST 节点计数接口，因此三个上限的实际实现是：

| 上限 | 实现 | 依据 |
|---|---|---|
| 长度 ≤ 512 | `expr.length()` 字符串检查 | EXPR-07 |
| AST 节点 ≤ 128 | 编译**前**用 Aviator 自身 lexer 统计 token 数（近似），或对括号/运算符计数 | EXPR-07；`Options` 无对应项 |
| 字段深度 ≤ 5 | 由 SDK 侧 `EvalContext` 投影实现（env 是扁平 Map，深度天然受限），并在编译期拒绝超过白名单的变量名 | EXPR-07 + INV-7/INV-10 |
| 无正则 | 编译前对表达式文本做 `/.../` 字面量与 `=~` / `string.replace_all` / `replace_first` / `split` 的检测 | EXPR-08；实测见矩阵 6/6b |
| 无类生成 | `validate()` 先跑；再 `compile(expr, true, cached)`；再检查**本次推送的规则总数**是否超上限 | EXPR-09 + PERF-04 |

**静态检测的准确边界**：`/pattern/` 是词法层的显式字面量（`ExpressionParser.java:892` → `pattern()`，`:1206-1233`），因此「表达式文本中不得出现未转义 `/` 字面量模式」是可靠的词法判据。但 `string.replace_all(s, '...', 'x')` 传的是**普通字符串字面量**，词法层无法区分它与普通字符串——只能靠**函数名白名单**（`replace_all`/`replace_first`/`split` 不在白名单内）来排除。这一层拦截依赖白名单完备性，因此须有测试断言白名单中不含任何 regex-backed 函数。

### Anti-Patterns to Avoid

- **把 `enableSandboxMode()` 当作完整沙箱**：实测仍有 13 个 Feature 开启、`PUT_CAPTURING_GROUPS_INTO_ENV=true`、属性语法可取 `.class`。引擎沙箱是第二道防线（`PITFALLS.md:50`）。
- **把 LRU 编译缓存当作元空间保护**：实测无效（`evidence/probe3-output.txt:49-50`）。必须规则总数硬上限 + Metaspace 监控。
- **断言 `FEATURE_SET` 为空**：实测沙箱后非空。断言「⊆ 白名单」。
- **把 `EVAL_TIMEOUT_MS` 当作 ReDoS 防线**：正则匹配在单步内完成，超时无法插入（实测 4000ms 未完成）。
- **在规则热路径 catch `Exception`**：`StackOverflowError` 是 `Error`，会穿透。
- **把规则入参对象图直接放入 env**：INV-8；实测 `amount = 999` 可改写调用方 Map。

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 表达式解析 / 编译 / 沙箱 | 自研解析器与求值器 | Aviator 5.4.4 沙箱 | 解析器、运算符优先级、类型提升是数年工作量；自研必然漏边界 |
| 沙箱隔离 | 自研类加载隔离 | `enableSandboxMode()` + option/feature 缩窄 | 已有受维护实现；自研会漏掉本次实测的 4 类缺口 |
| 正则时间预算 | 自研正则超时包装 | **禁止表达式内正则** | JDK 8 `java.util.regex` 无法中断单次匹配（`PITFALLS.md:67`）；换引擎也难 |
| 熔断 / 降级 | 自研 | Phase 2 的 `ClusterGate` 探针状态机 | `ARCHITECTURE.md:336-365` Pattern 3 |
| 指标基数控制 | 自研 tag 裁剪 | Micrometer 预注册 + 有界枚举 | `ARCHITECTURE.md:947` Anti-Pattern 9 |
| 规则历史持久化 | 自研 schema | MySQL + `RuleHistoryStore` 接口 | P1 只冻结接口签名（开放项 5） |
| 版本化 Lua 脚本 | 自研脚本加载 | Redis `EVALSHA`（Phase 2） | PITFALLS 已有方案 |

**Key insight**：本研究的核心价值不在「选哪个引擎」（已定 Aviator），而在**实测出引擎沙箱的真实边界并把它关死**。四次探测（Probe/Probe2/Probe3）发现的 4 类默认可用越界路径，任何一次「读文档 + 信任 `enableSandboxMode()`」的选型流程都会漏掉。

## Recommended Designs for Discretion Items

研究阶段对 `01-CONTEXT.md:141-156` 的 10 项自行设计项给出建议。**这些是建议，不是锁定决策**，planner 可调整。

### D.1 每条能力 env 变量集的最终字段清单与类型

`amount`=long（分）已定。`ARCHITECTURE.md:309-334` 的 `ArgumentSource` + P4 的 `getLevel/load`/`invalidate` 是能力方的诉求。建议：

| 能力 | 建议字段 | 建议上限 | 备注 |
|---|---|---|---|
| 限流 | `amount`(long/分) | 64 | `ARCHITECTURE.md:1126` 提到金额分桶档位 |
| 灰度 | `userId`(String)、`accountId`(String)、`cardBin`(String) | 64 | 分桶维度按 `GL-*` 演进；初始只暴露已确认的 |
| 缓存 | `userId`、`cardBin` | 64 | `getLevel/load/invalidate` 需要稳定的实体标识 |

**中间档位变量一律不设**（INV-9）。`/^(a+)+b$/` 这类拼接档位名会直接触发 ReDoS（矩阵 6）。若业务确需「万元档」，由**控制台**定义命名档位，SDK 侧枚举而非自由字符串。

### D.2 变量集大小上限

各能力上限**须**按能力分设，与 `SEC-07` 的 ≤64 预投影字段是**两层不同的闸门**（64 是全局预投影上限，按能力上限是每段自己的变量集大小）。`01-04` 依 `01-CONTEXT.md` §2.3 字段表（`RATE_LIMIT` 7 / `CANARY` 5 / `CACHE` 3）取带余量的暂定值 `RATE_LIMIT` ≤ 16、`CANARY` ≤ 8、`CACHE` ≤ 8。上限须进入 `EXPR-04` JMH：**测量 8 / 16 / 32 / 64 个变量下的求值 p99，找出拐点后把上限钉在拐点之下**——该 env-size 维度在 `01-08` Task 3 的 `ExpressionEvalBenchmark` 以 `@Param` 实现，实测结果若显示拐点低于 16 / 8 / 8，对应上限必须**下调**。上限是**被测出来的**，不是断言出来的。

### D.3 反射实现细节

- **深度上限**：`01-RESEARCH.md` 原建议 `depth=1`（只读顶层标量/getter，不递归）。**该建议未被采纳**：嵌套 env 在限流/灰度数据线里是常态，`depth=1` 会让这类规则**静默取不到值**而非报错。实际取值由 `01-04` 定为默认 2 / 硬上界 3，待 `01-08` env-size 压测校准。`ARCHITECTURE.md:317` 的扁平 `Map` 是投影产物，不构成调用方传参约束。
- **集合/Map 展开**：只展开 `Map<String,?>`（取 `get`），**不展开** `Collection`（元素类型不定 → 不可静态白名单）。`list.size()` 在矩阵第 1 类已被沙箱拒绝，把它列入白名单会自相矛盾。
- **循环引用保护**：深度 > 1 后循环引用在结构上可能发生，故用 `IdentityHashMap` 维护**当前递归路径**的已访问实例集合（进入检查、离开移除），命中即停止该分支展开。`IdentityHashMap` 而非 `HashMap` 是必需的：`equals`/`hashCode` 可被值对象覆写，用其做身份判定会误判或漏判。路径级而非全局级：同实例出现在两条不同分支是合法数据。
- **反射元数据缓存**：`Field.get/set` 或 `Method.invoke` 的 `MethodHandle` 缓存**必须有界**（`PERF-04`）。建议用启动期一次性构建的 `Map<Class, MethodHandle[]>` 不可变快照——反射元数据不随业务数据变化，**可以在冷启动期构建**，因此热路径不需缓存查找。注意：`Method.invoke` 在 `setAccessible(false)` 下仍可对 public 方法调用，但跨模块私有字段需 `--add-opens`；建议**只用 public getter**，避免 JVM 参数依赖。

### D.4 白名单的承载形态

建议**代码常量 + 启动断言**，不用注解或配置：

- 每能力一个 `Set<String>` 常量（`Compile-time` 不可变）。
- 白名单**不进配置**：配置可改的白名单意味着「谁能改白名单，谁就能读敏感字段」。INV-8 要求「由代码层保证」，配置是配置层。
- 与启动断言联动：schema 校验时，若规则引用了白名单外的变量名 → **拒绝加载该规则**（INV-10），并报出违规变量名。`#default` 是唯一的例外出口（`ARCHITECTURE.md:341`）。
- 需要为业务方扩展时，提供**编译期**（`foundation.core.env.allowlist` 属性）而非运行期配置。

### D.5 `RuleHistoryStore` / `ConfigSource` 接口签名冻结

按 `ARCHITECTURE.md:174-183`，建议 Phase 1 冻结：

```java
public interface RuleHistoryStore {
    Optional<RuleSetSnapshot> findByDataLine(String dataLine);
    List<RuleSetSnapshot> loadAll();
    void append(RuleSetSnapshot candidate);       // 已被 .support 解析的候选
}
public interface ConfigSource {
    /** 返回最新原始配置文本（不做解析）。实现方负责长轮询/gRPC。 */
    String fetchRaw();
}
```

`.support` 文件作为**版本化 schema 声明**（`GOV-09` 白名单声明入口），Phase 1 产出 `SchemaRegistry` + `MaxSchemaVersionReader`，MySQL 实现留到 P5 依赖真正出现时。

### D.6 schema 定义文件与 `.support` 文件的具体形态

`.support` 内容必须由 `ARCHITECTURE.md:300` 的契约反推：含 `dataLine`、`group`、`dataId`、`maxSchemaVersion[3]`（键为能力名）、`supportedCapabilities`（每项含 `minSchemaVersion`/`maxSchemaVersion`）。

分位 schema（`ARCHITECTURE.md:188-190` 提到 `default.json` 与 `auth.json`）**独立于能力**：同一规则文档可携带多个能力段，故建议 `schemas/<dataLine>/<capability>/v<N>.json`，外加 `schemas/<dataLine>/meta.json` 声明 `dataLine` + `capabilities`。`GOV-01` 修订行要求段内不声明 `schemaVersion`——因此 `dataLine`/能力是**目录路径**而非文档内字段，避免歧义。

**关键约束**：`GOV-10` 要求内容哈希与 schema 匹配，而 schema 校验失败必须**不进入** `sha256` 计算。

### D.7 冷启动重试上限与最终失败行为

`FND-05`/`GOV-11` 的默认 `STRICT` 要求「Nacos 与 MySQL 不可达即启动失败」（`01-CONTEXT.md:150`）。重试策略与这不矛盾——重试是启动过程的正常组成部分。给出可调但有界的重试预算：

| 参数 | 建议默认 | 语义 |
|---|---|---|
| `foundation.rule.startup.retry.max-attempts` | 5 | 指数退避（名义 1/2/4/8/16s；**名义和 31s 与 30s 预算的 1s 差额由截断消解**，见下） |
| `foundation.rule.startup.retry.total-budget` | 30s | 时间预算，优先于次数 |
| `foundation.rule.startup.fail-strategy` | `STRICT` | `STRICT`=预算耗尽后抛异常终止 JVM；`RESILIENT`=仅使用 LKG |
| `foundation.rule.startup.halt-on-failure` | `true` | `ARCHITECTURE.md:210` 硬要求 fail-fast |

区分两路不可达（GOV-11）：独立初始化步骤、独立 health indicator、独立错误码；`MySQL` 不可达但 Nacos 可达**不阻止**启动（LKG 路径足够）；两者都不可达且无 LKG → `STRICT` 失败 / `RESILIENT` 告警。

### D.8 各独立计数器的名称与维度

必须独立的原因：INV-11（缺变量未命中 vs 表达式 false 必须可分离）、INV-12（求值失败 vs 上一条）、矩阵各类别独立。

| 计数器 | 语义 | 关键 tag |
|---|---|---|
| `foundation.rate_limit.decision{outcome,reason}` | 限流决策 | `reason ∈ {ALLOW, REJECT, NO_RULE_MATCH, DEFAULT_DENY, FAIL_CLOSE, FAIL_OPEN}` |
| `foundation.canary.decision{outcome,reason}` | 灰度决策 | `reason ∈ {MATCH, NO_MATCH, NO_RULE_MATCH, DEFAULT_MATCH, FAIL_OPEN, FAIL_CLOSE}` |
| `foundation.cache.decision{outcome,level}` | 缓存决策 | `level ∈ {L1, L2, BYPASS, DEFAULT_BYPASS}` |
| `foundation.expression.compilation{outcome}` | 编译结果 | `outcome ∈ {SUCCESS, SYNTAX_INVALID, UNKNOWN_VARIABLE, REGEX_FORBIDDEN, AST_BUDGET, LENGTH_BUDGET, BUDGET_EXCEEDED, INTERNAL_ERROR}` |
| `foundation.expression.targeting_miss{ruleId,capability}` | 目标表达式「变量缺失未命中」（INV-11，独立 meter） | 与下一行**不合并** |
| `foundation.expression.not_match{ruleId,capability}` | 目标表达式「求值为 false」（INV-11，独立 meter） | 与上一行**不合并** |
| `foundation.expression.eval_failed{ruleId,capability,failStrategy}` | 目标表达式「求值失败按 fail 策略返回」（INV-12，独立 meter） | `failStrategy ∈ {FAIL_CLOSE, FAIL_OPEN, CANARY_DEFAULT, RESILIENT_EMPTY, REJECT}` |
| `foundation.sandbox.violation{class,capability}` | 沙箱/预算越界 | `class ∈ {reflection, constructor, static_method, classloader, io, redos, infinite_loop, stack_overflow, resource_amplification, deep_nesting, property_syntax, env_mutation}`（12 值，与 `01-08` Task 4 的封闭枚举表同名同序；**`ast_budget` 命名已废弃**，改用 `resource_amplification`） |
| `foundation.rule.apply{outcome}` | 段应用结果 | `outcome ∈ {APPLIED, REJECTED_SCHEMA, REJECTED_COMPILE, REJECTED_ROLLBACK, REJECTED_HASH, REJECTED_BUDGET}` |
| `foundation.decision.dropped{reason}` | 决策记录丢弃 | `reason ∈ {BUFFER_FULL, BUDGET_EXHAUSTED}` |
| `foundation.degraded{mode,cause}` | 降级状态（GOV-12 亦复用） | `cause ∈ {CONFIG_UNREACHABLE, HISTORY_UNREACHABLE, SNAPSHOT_DIR_UNWRITABLE, REDIS_UNREACHABLE, CLUSTER_GATE_OPEN}` |

**指标名与本表的关系（`01-08` Task 4 裁决，本表已对齐）**：本表的 `foundation.` 前缀是**渲染后的 registry 名称**，由 `01-01` 建立的命名空间纪律统一加；**逻辑计数器名**（代码与测试中引用、`MetricsPort` 契约里出现的那个）是 **`sandbox_violation`**，不带前缀、不带 `foundation.sandbox.violation` 的拼法。两者是**同一个 meter 的两种视角**，不是两个 meter——`MicrometerMetricsAdapter` 只注册一次（`01-08` Task 2），`foundation.` 前缀在渲染时加一次。本表此前把 `foundation.sandbox.violation` 写成权威名，与 `01-08` Task 4 的裁决不一致，已按该裁决统一。

同理，上表新增的三个求值语义计数器**也是三个独立 meter**，不是 `foundation.expression.evaluation{outcome}` 的三个取值：INV-11 / INV-12 要求「可分别观测」，把三者并进一个 meter 的 `outcome` 维度会让独立取数与独立告警都退化成按 tag 拆分。原 `foundation.expression.evaluation{outcome}` 一行**已被上三行取代**（其原 `outcome` 取值域中的 `ENV_MISSING` 归入 `targeting_miss`，`RUNTIME_ERROR` 归入 `eval_failed`；`SUCCESS` / `BUDGET_EXCEEDED` / `TIMEOUT` 属求值健康度而非上述三条语义，不在 INV-11 / INV-12 范围内，落地时按 `01-08` Task 1 的 `DecisionMetrics` 处理）。

**命名空间纪律**：`foundation.*`（FND-10）；SDK **不创建** HTTP server（GOV-09 白名单 + `ARCHITECTURE.md:989`），因此**禁止** SDK 自定义 `/foundation/*` 端点（`:991` 明示）。`/actuator/**` 仅在 `foundation-autoconfigure` 注册 Actuator endpoint。

**高基数字段**：`ruleId` 只在三个求值语义计数器（`targeting_miss` / `not_match` / `eval_failed`）上作为 tag（`SEC-04` 允许 `ruleId`），并受有界集合约束；`ruleVersion` **不作为 tag**（无界）——只在 `DecisionRecord`（`OBS-02`）。`EXPR-10` 的慢求值计数以 tag `{ruleId}` 记录，配有界计数器 + 环形采样；超限时降级为 `{capability}` 粒度并计 `foundation.meter.tag_budget_exceeded`。

### D.9 发布通道的段合并逻辑归属

**SDK 侧不做合并**（`01-CONTEXT.md:155`）。SDK 职责边界：`ConfigSource.fetchRaw()` 返回原始配置文本 → 解析 → 按能力段校验 → 各自发布。发布通道（控制台）负责把多个能力段的发布聚合成一个推送包。SDK 不缓存「待发布片段」，不做增量 patch 协议。

### D.10 `sandbox_violation` 命名与聚合边界

见 D.8 表。建议补充两点：
- 聚合维度固定为 `{class}`（有界枚举 12 项）+ 可选 `{dataLine}`（来自 `.support` 声明，有界）。**不引入 `ruleId`/`ruleVersion`**——矩阵违规多为语法级，ruleId 往往不存在。
- 语义：violation = **静态拒绝或运行越界**（规则层），非 `foundation.expression.compilation{outcome=...}` 的重复计数。`compilation` 按 reason 记一次，`sandbox.violation` 只在**命中攻击类别**时 +1，两者不重复。

## Validation Architecture

`.planning/config.json` 中 `"nyquist_validation": true`（`config.json:31`）与 `"security_enforcement": true`、`"security_asvs_level": 1`（`config.json:52-54`）已确认，故本节为**强制**。

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit Jupiter 5.8.2（Boot 2.7.6 管理）+ Mockito + AssertJ + spring-boot-starter-test |
| Config file | 无独立配置（`spring-boot-starter-test` 默认 surefire） |
| 快速命令 | `mvn -q -pl foundation-rule -am test` （单模块 < 30s） |
| 全量命令 | `mvn test` |
| 性能 | JMH（`jmh-core` 1.20，`provided`；bench 放 `<module>/src/test/java/**` 或独立 `foundation-bench`） |
| Enforcer | `mvn enforcer:enforce`（`dependencyConvergence` + `requireJavaVersion`）——须与 test 同为 CI 必过门 |

`ARCHITECTURE.md:251` 要求「无 Spring、无 Nacos 依赖的模块用纯 JUnit 5 测试」。因此 `foundation-rule`、`foundation-expression`（除集成测试外）、`foundation-core` 的单元测试**不加载 Spring 上下文**——这使沙箱与规则测试可在秒级完成。

### Phase Requirements → Test Map

| Req ID | 行为 | Test Type | 自动化命令 | 文件 |
|--------|----------|-----------|------------|------|
| FND-01 | 并发读安全 | unit（多线程）| `mvn -pl foundation-rule test -Dtest=SnapshotAtomicityTest` | Wave 0 新建 |
| FND-02 | 无非可选传递依赖 | build | `mvn dependency:tree` + enforcer | Wave 0 新建 |
| FND-03 | 非 Spring 宿主可启动 | unit | `new RateLimitSupport(...)` 无 Spring | Wave 0 新建 |
| FND-04 | 公共 API 不泄漏第三方类型 | unit + build | `mvn dependency:analyze` + API 签名检查 | Wave 0 新建 |
| FND-05 | 冷启动 fail-fast / RESILIENT | integration | `mvn -pl foundation-nacos test -Dtest=StartupFailureTest` | Wave 0 新建 |
| FND-06 | semver 解析 | unit | `mvn -pl foundation-core test -Dtest=SemverTest` | Wave 0 新建 |
| FND-07 | 启动断言暴露实际版本 | integration | `mvn -pl foundation-autoconfigure test -Dtest=VersionAssertTest` | Wave 0 新建 |
| FND-08 | `bannedDependencies` | build | `mvn enforcer:enforce` | Wave 0 新建 |
| FND-09 | 无 TTL 无重试 | unit | 决策路径无网络（Mockito 验证零交互） | Wave 0 新建 |
| FND-10 | 全部 `foundation.*` opt-in | integration | `@SpringBootTest` 无 `foundation.*` → 无 bean | Wave 0 新建 |
| GOV-01 | schema 按能力分列 | unit | `mvn -pl foundation-rule test -Dtest=SchemaParseTest` | Wave 0 新建 |
| GOV-02 | 版本回退拒绝 | unit | `VersionMonotonicityTest` | Wave 0 新建 |
| GOV-03 | 每能力独立 `AtomicReference` | unit | `SnapshotAtomicityTest` | Wave 0 新建 |
| GOV-04 | 无部分应用 | unit | `AtomicityPartialFailureTest` | Wave 0 新建 |
| GOV-05 | LKG temp-file + `ATOMIC_MOVE` | unit | `LastKnownGoodStoreTest` | Wave 0 新建 |
| GOV-06 | 段级原子拒绝 | unit | `RuleValidatorTest` | Wave 0 新建 |
| GOV-07 | 去抖 | unit | `RuleChangeListenerTest` | Wave 0 新建 |
| GOV-08 | 删除退回安全默认 | unit | `DeletedRuleDefaultTest` | Wave 0 新建 |
| GOV-09 | 白名单外即失败 | unit | `NacosConfigKeysTest` | Wave 0 新建 |
| GOV-10 | 哈希不匹配拒绝 | unit | `ContentHashTest` | Wave 0 新建 |
| GOV-11 | 冷启动断言 `maxSchemaVersion` | integration | `StartupSchemaAssertTest` | Wave 0 新建 |
| GOV-12 | 目录可写性探针 | unit | `SnapshotDirWatchdogTest` | Wave 0 新建 |
| GOV-13 | 路由表大小稳定 | unit | `RoutePlanSizeTest` | Wave 0 新建 |
| GOV-14 | 编译缓存有界 LRU | unit | `CompiledExpressionCacheTest` | Wave 0 新建 |
| GOV-15 | 差量替换 | unit | `RoutePlanDiffTest` | Wave 0 新建 |
| **EXPR-02** | **启动断言未被关闭** | **unit** | `mvn -pl foundation-expression test -Dtest=SandboxOptionsAssertTest` | **Wave 0 新建** |
| **EXPR-03** | **攻击矩阵** | **unit** | `mvn -pl foundation-expression test -Dtest=SandboxAttackMatrixTest` | **Wave 0 新建** |
| **EXPR-05** | 步数预算 | unit | `StepBudgetTest` | Wave 0 新建 |
| **EXPR-07** | 长度/AST/深度限制 | unit | `ExpressionBudgetLimitsTest` | Wave 0 新建 |
| **EXPR-08** | 正则拒绝 | unit | `RegexForbiddenTest` | Wave 0 新建 |
| **EXPR-09** | validate 先于 compile | unit | `ValidationBeforeCompileTest` | Wave 0 新建 |
| **EXPR-11** | 白名单字段访问 | unit | `EvalContextWhitelistTest` | Wave 0 新建 |
| EXPR-12 | SPI 保留 | build | `ExpressionEngineTest` | Wave 0 新建 |
| OBS-02 | `DecisionRecord` 字段集冻结 | unit | `DecisionRecordContractTest` | Wave 0 新建 |
| OBS-03 | `ruleVersion` 不可变 | unit | `SnapshotImmutabilityTest` | Wave 0 新建 |
| OBS-05 | 有界 ring buffer | unit | `RingBufferSinkTest` | Wave 0 新建 |
| OBS-06 | 满时丢弃不阻塞 | unit | `RingBufferOverflowTest` | Wave 0 新建 |
| OBS-07 | 决策记录不受 debug 门控 | unit | `DebugGateIndependenceTest` | Wave 0 新建 |
| OBS-09 | 有界 tag 预注册 | unit | `FoundationMetersTest` | Wave 0 新建 |
| SEC-02 | 脱敏 + debug 开关默认关 | unit | `MaskedContextTest` | Wave 0 新建 |
| SEC-03 | SDK 默认脱敏 | unit | `DefaultMaskingTest` | Wave 0 新建 |
| SEC-04 | 指标 tag 白名单 | unit | `TagAllowlistTest` | Wave 0 新建 |
| SEC-07 | ≤64 字段、扁平不可变 | unit | `ArgumentSourceTest` | Wave 0 新建 |
| PERF-04 | 无无界结构 | unit | `BoundedStructuresTest` | Wave 0 新建 |

### Sampling Rate

- **每任务提交**：`mvn -q -pl <module> -am test`（受影响模块）
- **每波合并**：`mvn test`（全量）+ `mvn enforcer:enforce`
- **阶段闸门**：全量绿 + `SandboxAttackMatrixTest` 全通过 + `enforcer:enforce` 绿 + JMH 基线已记录

### Wave 0 Gaps

- [ ] 全部测试基础设施为新建（仓库无 `src/`，零测试）
- [ ] `foundation-expression` 的 `SandboxAttackMatrixTest`（9+1 类，须实际触发 `BudgetExceededException`）
- [ ] `SandboxOptionsAssertTest`（沙箱启动反证）
- [ ] `StepBudgetTest`（步数预算 + `BudgetExceededException`）
- [ ] `RegexForbiddenTest`（EXPR-08 静态拒绝）
- [ ] JMH 基准台（`foundation-bench` 或模块内 `*Benchmark.java`）
- [ ] `enforcer` 规则（`dependencyConvergence` + `requireJavaVersion` + `bannedDependencies`）
- [ ] 快照契约测试（`SchemaParseTest` / `SnapshotAtomicityTest` / `ContentHashTest`）
- [ ] 决策记录契约测试（`DecisionRecordContractTest` / `TagAllowlistTest` / `RingBufferOverflowTest`）

## Edge Coverage

| Edge | 处理 | 依据 |
|---|---|---|
| Nacos 不可达 | 告警 + 保留上一份 LKG + 降级标记 + 独立 meter | INV-6；GOV-11 |
| MySQL 不可达 | 告警 + 独立 meter；不影响 Nacos 路径 | GOV-11（区分两路） |
| 冷启动 + 无 LKG + 不可达 | `STRICT`（默认）fail-fast；`RESILIENT`（须显式 opt-in）告警 | FND-05 修订行；INV-5 |
| 空配置段 | 退回内置安全默认 | GOV-08 |
| 规则版本回退 | 拒绝该段，保留该能力前一版本 | GOV-02/GOV-06 |
| 单条规则 schema 不合法 | 整段拒绝，保留该能力前一版本 | INV-1；GOV-04/GOV-06 |
| 表达式语法错误 | `validate()` 失败 → 整段拒绝 + `compilation{outcome=SYNTAX_INVALID}` | EXPR-09 |
| 表达式正则 / 超预算 | 静态拒绝 + `sandbox.violation{class=redos\|resource_amplification}` | EXPR-07/EXPR-08 |
| 表达式求值栈溢出 | `catch (Throwable)`（`StackOverflowError` 是 `Error`） | 矩阵 7b |
| 表达式求值超时 | 独立有界线程池 + 拒绝策略 + 导出指标 | EXPR-06 |
| 变量缺失 | 不命中（不是 false）；独立指标 | INV-11 |
| 敏感字段 | deny-by-default；白名单外 key 拒绝加载 | INV-8/INV-10；SEC-03 |
| 决策 ring buffer 满 | 非阻塞丢弃 + 计数 | OBS-06 |
| 编译缓存满 | LRU 淘汰 + 淘汰指标；规则数超上限则整段拒绝 | GOV-14 + PERF-04 |
| 快照目录不可写 | 告警 + `snapshotDirUnwritable` 标记 | GOV-12 |
| 指标 tag 超预算 | 降级 + `meter.tag_budget_exceeded` | SEC-04/OBS-09 |
| 空 `dataLine` 段 | 不装配该能力 → fail-open（灰度）/ fail-close（限流） | `ARCHITECTURE.md:341`；GOV-08 |

## Prohibitions

以下为 `01-CONTEXT.md:159-174` + `PITFALLS.md:643-653` 的**硬禁止**，实现时不得以任何形式绕过：

| # | 禁止 | 原因 | 检测手段 |
|---|---|---|---|
| 1 | 表达式中调用任意方法 | 远程代码执行 | 攻击矩阵 1-4；code-review |
| 2 | 表达式中使用正则 | JDK 8 regex 不可中断 → ReDoS | 矩阵 6/6b；`RegexForbiddenTest` |
| 3 | 表达式中使用 `while`/`for` 无限循环 | CPU DoS | 矩阵 7；`MAX_LOOP_COUNT` |
| 4 | 完整业务对象图进 env | 敏感字段泄漏 | INV-8；`ArgumentSourceTest` |
| 5 | 规则用 `-` 指定隐藏资源 | 路径逃逸 | `path.normalize().startsWith(base)` |
| 6 | 强敏感字段（完整卡号/CVV/持卡人姓名）进 env | PCI | INV-8；deny-by-default |
| 7 | `DecisionRecord` 携带原始入参/原始 key/完整表达式 | PCI | INV-14；`DecisionRecordContractTest` |
| 8 | 指标 tag 用 `traceId`/PAN/无界 `ruleVersion` | 基数爆炸 | SEC-04；`TagAllowlistTest` |
| 9 | 决策 ring buffer 满时阻塞业务线程 | 尾延迟雪崩 | OBS-06；`RingBufferOverflowTest` |
| 10 | SDK 业务通道超时用 `Thread.interrupt` | 吞掉中断 | `ARCHITECTURE.md:140` |
| 11 | LRU 编译缓存当元空间保护 | 实测无效 | Pitfall 7 |
| 12 | 断言 `FEATURE_SET` 为空 | 实测 13 项仍开 | Pitfall 8 |
| 13 | 规则热路径 catch `Exception` | 吞掉 `StackOverflowError` | 矩阵 7b |
| 14 | 在决策路径中做版本验证 / 网络调用 | 违反 INV-6 | GOV-02/GOV-05 在规则面 |

## Common Pitfalls

### Pitfall 1: 以为 `enableSandboxMode()` 就是完整沙箱
**What goes wrong:** 实现只调用 `enableSandboxMode()` 就认为「任意方法调用已禁止」，实际仍留 4 类可用越界路径。
**Why it happens:** 该方法只做 6 件事（`AviatorEvaluatorInstance.java:470-481`），其中最易漏的两项——不改 `PUT_CAPTURING_GROUPS_INTO_ENV`、不改 `ENABLE_PROPERTY_SYNTAX_SUGAR`——是默认值，前者让正则捕获组写回 env，后者让 `amount.class` 返回 `Class` 对象。
**How to avoid:** 按 Pattern 1 显式设置并逐项断言。
**Warning signs:** 攻击矩阵第 6/6b/9/9b 行通过但第 1-5 行没测——那说明矩阵本身有盲区。

### Pitfall 2: 断言沙箱后 `FEATURE_SET` 为空
**What goes wrong:** 启动断言 `if (!fs.isEmpty()) throw` 会让**所有启动失败**，因为实测沙箱后仍有 13 项 Feature。
**Why it happens:** `enableSandboxMode()` 只禁用 4 项（`Module`/`NewInstance`/`InternalVars`/`StaticMethods`），其余全部保留；`Feature.StaticFields` 虽保留，靠空 `ALLOWED_CLASS_SET` 阻断。
**How to avoid:** 断言 `fs ⊆ EXPECTED_ALLOWED`，而非 `fs.isEmpty()`。
**Warning signs:** 单测跑不过，但 `enableSandboxMode()` 明明调用了。

### Pitfall 3: 把 `EVAL_TIMEOUT_MS` 当作 ReDoS 或死循环的防线
**What goes wrong:** 期望「1000ms 超时兜底」能挡住 ReDoS，实测 n=40 时 4000ms 仍未完成。
**Why it happens:** Aviator 的超时检查在解释器**步之间**；`AviatorPattern.match` 内部对 `Matcher.matches()` 的一次调用是一次不可中断的步骤。
**How to avoid:** 唯一有效手段是**编译期拒绝正则**（EXPR-08）。超时只作为 `while(true)`（走 `MAX_LOOP_COUNT`，实测 109ms 收敛）等场景的兜底。
**Warning signs:** 压测中出现「超时配置为 1s，实际请求卡 4s+」。

### Pitfall 4: AST 节点数与深度限制无实现落点
**What goes wrong:** EXPR-07 写「AST 128 / 深度 5」，但 Aviator **不暴露** AST 访问器（`Options` 中无对应项），实现者不知从何下手而可能直接跳过。
**Why it happens:** 需求按「引擎应提供的能力」写，未核对引擎实际 API。
**How to avoid:** 按 Pattern 3——长度用字符串长度，节点数用编译前 token/括号计数近似，深度由 SDK 侧扁平 env 天然保证。**近似即可**（目标是不 OOM，不是精确）。
**Warning signs:** 计划里出现「调用 Aviator AST API」而不给出 API 名称。

### Pitfall 5: 沙箱初始化顺序错误
**What goes wrong:** 先设 `EVAL_TIMEOUT_MS=50` 再调 `enableSandboxMode()`，结果被静默重置为 1000ms，代码**不报错**。
**Why it happens:** `enableSandboxMode()` 内部 `setOption(Options.EVAL_TIMEOUT_MS, 1000L)`（`:479`），且无「已设置」保护。
**How to avoid:** 超时放最后（Pattern 1 第 ③ 步），并在启动断言中读取校验。
**Warning signs:** 代码 review 时 `enableSandboxMode()` 出现在 `setOption(EVAL_TIMEOUT_MS, …)` 之后。

### Pitfall 6: 把捕获组/env 改写当作「无害副作用」
**What goes wrong:** 期望「沙箱只影响表达式内部」，实测 `s =~ /([a-z]+)([0-9]+)/` 会向**调用方传入的 Map** 写入 `$0,$1,$2`；`amount = 999` 直接改写业务 env。
**Why it happens:** `PUT_CAPTURING_GROUPS_IN_TO_ENV` 默认 `true`，`USE_USER_ENV_AS_TOP_ENV_DIRECTLY` 默认 `true`，且 `Assignment`/`Let` Feature 保留。
**How to avoid:** 三项全关（Pattern 1）+ 每次决策新建 env（即使关掉也要纵深防御，因为第三方的 `ExpressionEngine` 实现可能不看这些 option）。
**Warning signs:** 求值后业务代码发现自己的 env 多了 `$1` 键。

### Pitfall 7: 把 `useLRUExpressionCache` 当作元空间保护
**What goes wrong:** Nacos 持续推送新规则表达式，LRU 大小 50，但 Metaspace 持续增长直至 OOM。
**Why it happens:** LRU 只限制**缓存条目引用**；Aviator 每次 `compile` 都生成一个新类，类一旦加载即被 JVM 永久持有（直到 classloader 卸载），**GC 不回收**。实测 LRU(50) 下 5,000 个不同表达式仍 **+5,000 个类**。
**How to avoid:** (a) `validate()` 前置（实测 20,000 次仅 +12 类）；(b) **规则总数硬上限**（PERF-04）；(c) Metaspace 指标告警；(d) 已缓存表达式零重新编译（GOV-14）。LRU 只作内存卫生，不是安全边界。
**Warning signs:** Metaspace 随 Nacos 推送单调增长；重启后回落。

### Pitfall 8: 以为 `validate()` 通过就一定不会生成类
**What goes wrong:** 用 `validate()` 探测过界表达式，实现仍按「validate 不生成类」设计预期。
**Why it happens:** 实测 `validate()` 20,000 次仅 +12 类（可忽略），但**这不代表 `validate()` 覆盖了所有拒绝条件**——它只查语法。语义拒绝（未知变量、正则、AST 预算）仍须 SDK 自己的检查。
**How to avoid:** 把「语法检查（引擎）+ 语义检查（SDK）」分层写清；语义检查失败不进入 `compile`。
**Warning signs:** 规则集里有大量语法正确但语义非法的表达式。

### Pitfall 9: 用共享可变 env 跨决策复用
**What goes wrong:** 每次决策新建 `Map` 的性能担忧促使实现者复用同一个 env（线程本地或成员变量），导致并发下互相污染。
**Why it happens:** 分配成本直觉上「很贵」。
**How to avoid:** `ArgumentSource.projection()` 每次新建（`ARCHITECTURE.md:315`）；Caffeine 缓存 env 只能用于**同一** （不可变）输入，不可跨请求。性能问题用 JMH 解决，不用复用解决。
**Warning signs:** 出现 `ThreadLocal<Map<String,Object>>`。

### Pitfall 10: 混淆「拒绝」与「忽略」
**What goes wrong:** 遇到未知变量（白名单外）时选择 `log.warn` 跳过该变量。
**Why it happens:** 这是宽容的直觉。
**How to avoid:** INV-10 要求白名单外 key **拒绝加载**（整段拒绝），不是忽略。「拒绝加载」比「忽略」更严格，正是为了防止「未被发现的安全问题被静默降级」。
**Warning signs:** 规则引用了不存在的变量却只看到 WARN 日志、规则仍生效。

### Pitfall 11: 误以为 GOV-05/GOV-11 不冲突
**What goes wrong:** 计划把两者当独立任务，GOV-05 落盘、INV-5 落盘，并列安排。
**Why it happens:** 已消解的旧矛盾（GOV-05 与原 GOV-11）已不在 `REQUIREMENTS.md`。
**How to avoid:** 两者现在**互补**：GOV-05 是「有 LKG 时怎么恢复」，INV-5 是「无 LKG 且不可达时 fail-fast」。一起实现，一起测试（`LastKnownGoodStoreTest` + `StartupFailureTest`）。

### Pitfall 12: `.support` 里的白名单当成 SDK 硬编码
**What goes wrong:** GOV-09 声明 namespace/group/dataId 白名单，但把真实值写进代码而非 `.support`。
**Why it happens:** 「白名单」直觉上像常量。
**How to avoid:** `.support` 是数据，代码只定义「哪些 key 必填、格式如何」；真实值在 `.support` 中，启动断言读 `.support`。
**Warning signs:** 代码里出现字面量 `"DEFAULT_GROUP"` / `"NACOS_DATA_ID"`。

### Pitfall 13: `NacosConfigKeys` 改动引起全 SDK 重编译
**What goes wrong:** 改一个 key 名称导致所有依赖模块重编译。
**Why it happens:** C8 的 `NacosConfigKeys` 被 `autoconfigure` 直接引用（`ARCHITECTURE.md:248`）。
**How to avoid:** 保持 C8 只有常量/校验器无依赖（不依赖 core）；`autoconfigure` 只调用校验器。
**Warning signs:** C8 出现对 `foundation-core` 的 import。

## Code Examples

### 有序沙箱初始化 + 启动反证（Pattern 1）
来源：`AviatorEvaluatorInstance.java:470-481`（实测行为）+ 探针 `Probe3.java`/`probe3-output.txt:52-69`。

```java
public final class AviatorSandboxFactory {
    private static final Set<Feature> DISALLOWED = EnumSet.of(
        Feature.Assignment, Feature.Let, Feature.LexicalScope,   // env 改写
        Feature.ForLoop, Feature.WhileLoop,                      // 死循环
        Feature.Lambda, Feature.Fn,                              // 函数定义/调用
        Feature.StaticFields, Feature.Use,                       // 静态访问
        Feature.StringInterpolation,                             // 隐式字符串拼接
        Feature.Return, Feature.ExceptionHandle                  // 控制流逃逸
    );
    private static final Set<Feature> EXPECTED_ALLOWED = EnumSet.of(Feature.If);

    public static AviatorEvaluatorInstance create(SandboxBudget budget) {
        AviatorEvaluatorInstance inst = AviatorEvaluator.newInstance();
        inst.enableSandboxMode();                                          // ① 必须最先
        inst.setOption(Options.PUT_CAPTURING_GROUPS_INTO_ENV, false);
        inst.setOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR, false);
        inst.setOption(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY, false);
        inst.setOption(Options.NIL_WHEN_PROPERTY_NOT_FOUND, false);
        for (Feature f : DISALLOWED) inst.disableFeature(f);                // ②
        inst.setOption(Options.MAX_LOOP_COUNT, budget.maxLoopCount());
        inst.setOption(Options.EVAL_TIMEOUT_MS, budget.evalTimeoutMs());   // ③ 最后
        inst.useLRUExpressionCache(budget.compiledCacheSize());            // ④
        assertSandboxInvariants(inst);                                     // 启动反证
        return inst;
    }

    private static void assertSandboxInvariants(AviatorEvaluatorInstance inst) {
        Set<?> fs = (Set<?>) inst.getOption(Options.FEATURE_SET);
        if (!fs.isEmpty() && !EXPECTED_ALLOWED.containsAll(fs))
            throw new IllegalStateException("unexpected Aviator features enabled: " + fs);
        if (!((Set<?>) inst.getOption(Options.ALLOWED_CLASS_SET)).isEmpty())
            throw new IllegalStateException("ALLOWED_CLASS_SET must be empty in sandbox");
        if (!Boolean.FALSE.equals(inst.getOption(Options.PUT_CAPTURING_GROUPS_INTO_ENV)))
            throw new IllegalStateException("capture groups must not write back into env");
        if (!Boolean.FALSE.equals(inst.getOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR)))
            throw new IllegalStateException("property syntax must be disabled (leaks .class)");
        if (!Boolean.FALSE.equals(inst.getOption(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY)))
            throw new IllegalStateException("top env must not be the user env");
    }
}
```

### 步数预算（Pattern 2）
来源：`OperatorType.java` + `AbstractFunction`（实测 `addOpFunction` 在 ASM 与 INTERPRETER 均触发，`probe3-output.txt:37-41`）。

```java
public final class StepBudgetFunction extends AbstractFunction {
    private final String name;
    private final AviatorFunction delegate;
    private final LongAdder steps;   // 或 AtomicLong；热路径只读预算
    private final long limit;

    public StepBudgetFunction(String name, AviatorFunction delegate, LongAdder steps, long limit) {
        this.name = name; this.delegate = delegate; this.steps = steps; this.limit = limit;
    }

    @Override public String getName() { return name; }

    @Override
    public AviatorObject call(Map<String, Object> env, AviatorObject a1, AviatorObject a2) {
        if (steps.sum() > limit) throw new BudgetExceededException("step budget " + limit + " exceeded");
        return delegate.call(env, a1, a2);
    }
    // … 为规则会用到的其余 arity 覆写 call …
}
```

> 注意：为**全部** `OperatorType` 注册，否则未注册算子不计数。注册完成后必须有测试：超限表达式 → `BudgetExceededException`。

### 编译期静态检查（Pattern 3，EXPR-07/08/09）
来源：本研究矩阵第 6/6b 行 + `ExpressionParser.java:892,1206-1233`（`/pattern/` 字面量）+ EXPR-09 实测。

```java
public final class ExpressionGuard {
    private static final int MAX_LEN = 512;
    private static final Set<String> REGEX_BACKED_FUNCTIONS =
        Set.of("string.replace_all", "string.replace_first", "string.split");
    private static final Pattern REGEX_LITERAL = Pattern.compile("(?<!\\\\)/.*?/");

    public static ValidationResult validate(String expr, Set<String> envAllowlist) {
        if (expr.length() > MAX_LEN) return ValidationResult.reason(AST_BUDGET);  // LENGTH
        if (REGEX_LITERAL.matcher(expr).find() || expr.contains("=~"))
            return ValidationResult.reason(REGEX_FORBIDDEN);                       // REDOS
        for (String f : REGEX_BACKED_FUNCTIONS)
            if (expr.contains(f + "(")) return ValidationResult.reason(REGEX_FORBIDDEN);
        // 提取变量名并与 envAllowlist 比对：白名单外 → UNKNOWN_VARIABLE（INV-10 拒绝，不忽略）
        for (String v : extractIdentifiers(expr))
            if (!envAllowlist.contains(v)) return ValidationResult.reason(UNKNOWN_VARIABLE);
        return ValidationResult.ok();  // 通过后才允许 validate() + compile(cached)
    }
}
```

### 不可变快照 + 单次 volatile 读（Pattern 1 from ARCHITECTURE）
来源：`ARCHITECTURE.md:271-307`、`RuleSetSnapshot`（`:287-301`）。

```java
public final class RateLimitSupport {
    private final AtomicReference<RuleSetSnapshot> snapshotRef;   // 每能力一个
    public Decision decide(ArgumentSource args) {
        RuleSetSnapshot snap = snapshotRef.get();                // 单次读，1 层
        // … 纯本地计算，零网络（INV-6）…
        DecisionRecord.emit(new DecisionRecord(
            snap.version(), hit.ruleId(), hit.ruleVersion(), /* … 九字段白名单 … */));
        return d;
    }
}
```

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| SpEL / OGNL / MVEL 表达式 | 沙箱化专用引擎（Aviator 5.4.4） | 沙箱是选型的**第一判据**，不是吞吐 |
| 表达式内正则（业务习惯） | 编译期禁止 + 业务侧预计算档位 | 消除不可中断的 ReDoS 面（PITFALLS.md:64-86） |
| 逐请求解析表达式 | `validate()` → `compile(cached)` → 执行 `Expression` | ~476×（38,610ns → 81ns，方向性）；EXPR-04 用 JMH 定论 |
| Sentinel 内建集群限流 | 本地模式 + 共享计数器（Phase 2） | 20ms 调用线程阻塞不可控（`01-CONTEXT.md:101`） |
| 高基数指标（tag 带 `ruleId`/时间） | 低基数有界命名空间 + 预注册 meter | 防 OOM（`PITFALLS.md:90-112`；Anti-Pattern 9） |
| 拒绝「用第三方规则引擎」 | 保留（`01-CONTEXT.md:152`），仅收紧为「Aviator 5.4.4 沙箱」 | 决策溯源等已有测试 |

**Aviator 专项已确认的实战陷阱**（`01-CONTEXT.md:103`）：

| 陷阱 | 结论 | 实测证据 |
|---|---|---|
| 内建集群 token client 排队 `ChannelPromise.await(requestTimeout)`，`requestTimeout` 默认 20ms | 不用；走本地模式 + Phase 2 Redis Lua | `ARCHITECTURE.md:336-365` |
| Sentinel cluster 模式 `requestTimeout` 20ms vs 期望 2-3ms | 超时量级不达标 | 决策已收敛到 Phase 2 |
| 本地模式不初始化 token client、不开 Redis、不注册 Nacos listener | 冷启动硬保证 | `FND-09` + `ARCHITECTURE.md:137` |
| 集群不可达降级为单节点计数 | 用有界超时 + 熔断；降级显式可查询（`GOV-12`/`OBS-09`） | Phase 2 |
| `rejected_by_sentinel_local` / `rejected_by_cluster_token` / `degraded_to_local` 三指标 | 三个独立计数器 | Phase 2 模板；D.8 |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | JDK 8 是目标 runtime（非仅 CI） | Standard Stack | 全部字节码/依赖版本选择失效 |
| A2 | Aviator 5.4.4 沙箱在生产配置下行为与探针一致（探针用默认 env） | Attack Matrix / Patterns | 攻击矩阵 golden 值需重测 |
| A3 | 步数预算用 `addOpFunction` 覆盖所有 `OperatorType` 的开销可接受（未实测） | Pattern 2 | 若开销过高需改方案（EXPR-04） |
| A4 | `token.sum()` 原子递增在 ~30ns 内（LongAdder/AtomicLong） | Pattern 2 | 步数预算的每步成本影响 PERF-01 p99 |
| A5 | MySQL 规则历史是 Phase 5 依赖（P1 只冻结接口） | D.5 | 若 P1 需实现 MySQL，范围扩大 |
| A6 | 简循环性能（81ns/38,610ns）方向可信，正式值由 JMH 给 | EXPR-04 / PERF-01 | 仅影响预估，不影响设计 |
| A7 | 各能力 env 字段初始清单（D.1）是合理默认 | D.1 | 字段需在 P1 与业务方对齐 |
| A8 | 规则总数硬上限的具体数值未定（建议值待业务确认） | Pitfall 7 / PERF-04 | 上限过小误拒业务规则 |
| A9 | AST 节点 128 可用 token 计数近似 | Pitfall 4 / EXPR-07 | 近似误差导致误拒/漏拒 |
| A10 | Nacos 快照目录在部署形态下有可写路径 | GOV-12 | 无路径则 LKG 无法落盘 |
| A11 | Sentinel 1.8.6 local 模式在 2.7.6 下无已知冲突 | Standard Stack | 需 SCA BOM 复验 |
| A12 | `spring.factories`（非 `AutoConfiguration.imports`）是 Boot 2.7.6 的正确注册方式 | Standard Stack | Boot 2.7 双路径兼容 |

## Security Domain

`config.json:52-54` 确认 `"security_enforcement": true`、`"security_asvs_level": 1`、`"security_block_on": "high"`，故本节为**强制**。Phase 1 是整个系统的安全基座——后续三个阶段都建立在本阶段冻结的契约上，因此 ASVS 覆盖必须前置。

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no（Phase 1 不做身份认证） | 控制台/API 侧在 P5/P6 |
| V3 Session Management | no | — |
| V4 Access Control | **yes** | 规则加载为**显式白名单**（GOV-09 namespace/group/dataId）；`.support` 决定可加载哪些段；白名单外配置源**拒绝**而非忽略 |
| V5 Input Validation | **yes**（本阶段核心） | `RuleValidator`（GOV-06）+ `ExpressionGuard`/`validate()`（EXPR-07/08/09）+ `Semver`（FND-06）+ 启动断言（FND-07/GOV-11） |
| V6 Cryptography | no（Phase 1 不做加密） | `SEC-06` 明确 SDK **不**做 PAN 加密；P6 交付敏感数据路径清单 |
| V7 Error Handling | **yes** | 区分「配置中心不可达」/「MySQL 不可达」/「规则非法」/「表达式非法」/「求值失败」；`catch (Throwable)` 而非 `Exception`（矩阵 7b）；异常信息不携带规则内容 |
| V8 Data Protection | **yes** | INV-8 敏感字段 deny-by-default；INV-14 `DecisionRecord` 字段白名单；SEC-04 指标 tag 白名单；所有 `&` 归一化 + 不可变 map |
| V9 Logging | **yes** | SLF4J 1.7.36 + Logback 1.2.11（配错会静默丢全部日志）；日志不得含规则内容、表达式文本或敏感字段 |
| V12 Files / Resources | **yes** | 规则集路径 `normalize().startsWith(base)` 防 `-` 路径逃逸；`.support` 解析失败即拒绝该段 |
| V13 API / Web | partial | `.support` 唯一可信键来源（GOV-09）；SDK **不**定义 HTTP 端点（`ARCHITECTURE.md:991`） |

**ASVS L1 下的判定**：V5/V8/V9 是本阶段**高权重**范畴——`security_block_on: high` 意味着这三类的任何 finding 都会阻断阶段完成。攻击矩阵（EXPR-03）与 `TagAllowlistTest`（SEC-04）/`DecisionRecordContractTest`（INV-14）/`ArgumentSourceTest`（SEC-07）是这三类的自动化证据。

### Known Threat Patterns for Aviator 5.4.4 (Spring Boot 2.7 / JDK 8)

| Pattern | STRIDE | Standard Mitigation | 本研究实测状态 |
|---------|--------|---------------------|----------------|
| 反射逃逸（`getClass().forName()`） | Elevation of Privilege / Information Disclosure | `ALLOWED_CLASS_SET` 空 + `StaticMethods`/`NewInstance`/`Module` 禁用 | **已阻断**（矩阵 1-4） |
| 静态方法链（`Runtime.getRuntime()`） | Elevation of Privilege | `Feature.StaticMethods` 禁用 + `setFunctionMissing(null)` | **已阻断**（矩阵 3） |
| IO 访问（`IoModule`） | Information Disclosure | `removeModule(IoModule)` | **已阻断**（矩阵 5） |
| **ReDoS（表达式内正则）** | Denial of Service | **编译期拒绝**（EXPR-08） | **默认可用**（矩阵 6/6b）；`EVAL_TIMEOUT_MS` 无效 |
| **属性语法泄漏（`amount.class`）** | Information Disclosure | `ENABLE_PROPERTY_SYNTAX_SUGAR=false` | **默认可用**（矩阵 9） |
| **env 改写（`amount = 999`、捕获组）** | Tampering | 关闭 `Assignment`/`Let` + 3 个 env option + 每次新建 env | **默认可用**（矩阵 9b） |
| 死循环 CPU DoS | Denial of Service | `MAX_LOOP_COUNT` + 步数预算 | **已阻断**（矩阵 7，约 109ms 收敛） |
| 栈溢出（深递归） | Denial of Service | `catch (Throwable)` + 编译期特性收紧 | **部分**（矩阵 7b） |
| 元空间耗尽（规则 churn） | Denial of Service | `validate()` 前置 + 规则数硬上限 + Metaspace 监控 | **默认不安全**（Pitfall 7） |
| 规则替换竞态（非原子） | Tampering | 不可变快照 + 单次 `AtomicReference` 读 | 设计已就位（GOV-03/04） |
| 敏感字段经 env / 日志 / 指标泄漏 | Information Disclosure | deny-by-default 白名单 + 字段白名单 | 设计已就位（INV-8/INV-14、SEC-04） |
| 规则路径逃逸（`-` 前缀 / `..`） | Tampering | `normalize().startsWith(base)` | 待实现 |

**降级安全默认值**（`ARCHITECTURE.md:97-98`、`:170`）：

| 能力 | `failCloseByDefault` | 理由（`ARCHITECTURE.md:756`） |
|---|---|---|
| 限流 | `true` | 故障时保守拒绝，保护上游 |
| 灰度 | `false` | 故障时保守放行全量，保证可用性 |
| 缓存 | `true` | 缓存故障保守穿透，保护后端 |

用户要求「默认值按业务线参数化」→ `dataLine` 注册表项携带 `defaultFailStrategy`（`01-CONTEXT.md:118`）。**注意**：`RL-19` 的清算入站 fail-open 在注册表中是**特例**（反转即守护测试失败）。

## Open Questions

1. **规则总数硬上限的具体数值**
   - 已知：必须有上限（PERF-04），机制为「超限整段拒绝 + `BUDGET_EXCEEDED` 计数」+ Metaspace 告警。
   - 未知：数值。未做业务量估算。
   - 建议：规划时设为**可配置**（`foundation.rule.max-rules`，默认 10,000），JMH/P2 压测后调；先有护栏再调优。→ Assumptions Log A8。

2. **各能力 env 字段初始清单需业务确认**
   - 已知：`amount`(long/分) 已定；`ARCHITECTURE.md:309-334` 定义了 `ArgumentSource` 契约与 ≤64 上限。
   - 未知：灰度/缓存的初始维度字段（D.1 给了建议）。
   - 建议：D.1 表格作为 P1 冻结输入，标注「与业务方对齐」为 checkpoint。→ A7。

3. **步数预算的 per-step 开销**
   - 已知：机制可行（ASM/INTERPRETER 均触发 hook）。
   - 未知：`addOpFunction` 覆盖全部 `OperatorType` 后的实际开销。
   - 建议：EXPR-04 的 JMH 必须含「开/关预算」对照臂。→ A3。

4. **AST 节点上限 128 的近似精度**
   - 已知：Aviator 不暴露 AST API。
   - 未知：token 计数与真实节点数的误差。
   - 建议：接受近似（目标是防 OOM 非精确计数），但须测典型表达式（`amount > 1000 && channel == 'POS'`）确认不误拒。→ A9。

5. **MySQL 规则历史是否 P1 落地**
   - 已知：`ARCHITECTURE.md:1021` 阶段表把 P5 依赖列为「按需」，P1 只冻结接口签名。
   - 未知：是否因 GOV-05/LKG 需要而提前。
   - 建议：P1 只冻结接口 + `FileLastKnownGoodStore` 实现（无外部依赖，纯 JUnit 可测）；MySQL 实现随 P5 首个真实依赖出现。→ A5。

6. **Nacos 快照目录路径**
   - 已知：GOV-12 要求监控可写性。
   - 未知：部署形态下的实际路径与持久性。
   - 建议：配置项 `foundation.rule.snapshot-dir`，启动时探测可写 + 写-读-删一次；失败即按 `STRICT`/`RESILIENT` 处理。→ A10。

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 8 | 全部（FND-06 major ≤ 52） | ✓ | `1.8.0_xxx` | 无（硬约束） |
| Maven | 构建 | ✓ | — | 无 |
| Aviator 5.4.4 | EXPR-02..09 | ✓（已下载并实测） | 5.4.4，SHA-256 `543238…cb4798` | 无（已锁定） |
| Redis | Phase 2（P1 不需要） | — | — | — |
| Nacos | GOV-07/09（集成测试可 mock） | — | — | 测试期 mock `ConfigSource` |
| MySQL | 规则历史（P1 只冻结接口） | — | — | `FileLastKnownGoodStore` 覆盖 LKG 路径 |
| JMH | EXPR-04 / PERF-05 | ✓（Aviator POM 提供 1.20 `provided`） | 1.20 | 无 |
| `rg` | 工具链 | ✗ | — | 用 `grep`（`zsh: command not found: rg`） |
| `timeout` (coreutils) | 探针 | ✗ | — | 用 Java daemon-thread 限制（`zsh: command not found: timeout`） |

**Missing dependencies with no fallback**: 无。
**Missing dependencies with fallback**: `rg` / `timeout`（仅影响开发便利，不影响产出）。

**探测工具已可用**：`maven-enforcer-plugin`（`dependencyConvergence` + `requireJavaVersion`）须在 P1 建立（`ARCHITECTURE.md:1015` 阶段表）。

## Sources

### Primary — 官方仓库/文档（已下载并实测）
- `aviator-5.4.4.jar` + `aviator-5.4.4-sources.jar` — SHA-256 `5432380636ad8b90038a5ec47f06beb3f32fcc95759cb68f0cfaba8253cb4798`；源码关键行：`AviatorEvaluatorInstance.java:470-481`（`enableSandboxMode`）、`Feature.java`（枚举成员）、`Options.java`（默认值）、`AviatorPattern.java:39-42,81-110`（正则编译/执行）、`ExpressionParser.java:892,1206-1233`（`/pattern/` 词法）、`OperatorType.java:60,139-141`（`MATCH` 语义与参数交换）、`ClassDefiner.java:70-75`（ASM 类定义）。
- `aviator-5.4.4.pom` — 零运行时依赖；无 BCEL；JMH 1.20 `provided`。
- `https://api.osv.dev/v1/vulns/CVE-2021-41862` — CVE-2021-41862 / GHSA-xpv2-8ppj-79hh，CVSS 3.1 = 9.8，受影响 `through 5.2.7`。
- `spring-cloud-alibaba-dependencies:2021.0.5.0` / `spring-boot-dependencies:2.7.6` / `sentinel-datasource-nacos:1.8.6` POM（见 STACK.md 来源）— 版本权威。

### 内部规划文档（本次全部重读，行号引用）
- `01-CONTEXT.md` — D1..D25、INV-1..15、7 修订、强制门（`:11-24,84-174`）。
- `REQUIREMENTS.md` — 48 条 Phase 1 requirement（`:1-181`）。
- `ARCHITECTURE.md` — 模块/职责/模式/失败模式（`:81,97-98,137,141-253,257-365,390-419,745-766,941-961,989-991,1015,1021-1026`）。
- `PITFALLS.md` — 沙箱/表达式 DoS/基数/禁中断（`:50,64-86,90-112,643-653`）。
- `STACK.md` — 固定版本、依赖陷阱、不可复用延迟数字。
- `SUMMARY.md` — §R6 故障策略、决策清单。
- `config.json` — `nyquist_validation:true`、`security_enforcement:true`、`security_asvs_level:1`、`commit_docs:false`。

### Tertiary — 未独立验证，标记待验
- Aviator 官方文档中的沙箱行为描述（`yuque.com/boyan-avfmj/aviatorscript/ou23gy`）— 以本会话实测为准；文档描述可能滞后。

## 证据归档

| 文件 | 内容 |
|---|---|
| `evidence/Probe.java` | nil/逃逸/循环/LRU/env/反射探针 |
| `evidence/Probe2.java` | feature/`.class`/类计数/LRU/性能/env 探针（`+20000x` 标签已修正） |
| `evidence/Probe3.java` | regex/ReDoS/捕获组/property sugar/op hook/Metaspace 探针 |
| `evidence/probe3-output.txt` | Probe3 完整原始输出（矩阵 6/6b/9/9b 与 Pitfall 7 的直接来源） |

## Metadata

**Confidence breakdown**:
- 标准栈: HIGH — 版本来自 `AGENTS.md` 锁定 + `STACK.md` POM 验证 + Aviator POM 实读。
- 架构: HIGH — `ARCHITECTURE.md` + `01-CONTEXT.md` 双源，行号可查。
- 沙箱攻击面: HIGH — 本会话 JDK 8 实测 9+1 类，证据文件归档。
- 性能数字: MEDIUM → LOW — 简循环方向性数据，**非 JMH**，正式值由 `EXPR-04`/`PERF-05` 产出。
- ASM 类生成 vs Metaspace: MEDIUM — 实测 20,000 次仅 +12 类，但异常/边界场景的类生成行为未穷尽。

**Research date**: 2026-09-29
**Valid until**: 稳定项 30 天（架构/沙箱 opt）；性能项 7 天（须 JMH 复测）。

