# Phase 1 外部 API / 库表面覆盖清单（COVERAGE.md）

- 检测来源：`init.plan-phase` 的 api-coverage 检测器对本阶段 scope（8 个 PLAN 全文 + ROADMAP Phase 1 段落）返回 `detected: true`
- 命中信号：`noun: api` / `verb: (surface)`，片段为「冷启动策略作为 `ColdStartStrategy` 枚举从 SDK API 参数传入」
- 判定：命中信号偏弱（指向 SDK 自有 API 面的措辞，而非 REST/gRPC 端点），但本阶段确实消费多个外部库 API 表面，且对 Aviator / Nacos / Sentinel 有明确的安全与版本约束，因此按 `detected: true` 从严处理，逐项记录 INTEGRATE / OPT-OUT 决策。
- 决策模型：**opt-in**（默认覆盖）——被计划或代码实际引用、且未在下表显式 opt-out 的外部库 API 表面视为已覆盖；需窄化覆盖的项必须显式列出并给出理由。
- 下表是本文件的权威机器可读部分；每个 reason 已内联版本、消费计划与证据来源，不再另设附表，以避免多表结构影响解析。

## 覆盖矩阵

| capability | decision | reason |
|---|---|---|
| Aviator 5.4.4 expression engine and sandbox switches | INTEGRATE | 核心集成面：enableSandboxMode、validate、compile、Expression.execute、LRU 缓存；消费计划 01-02 / 01-04；证据为 01-RESEARCH.md 与 evidence/ 下三个 JDK 8 探针 |
| Jackson 2.13.4 schema and DecisionRecord binding | INTEGRATE | 能力段 schema 解析与 DecisionRecord 序列化；消费计划 01-03 / 01-05 / 01-07；版本由 Boot 2.7.6 Jackson BOM 管理 |
| Nacos client 2.2.0 control-plane config fetch | INTEGRATE | 仅控制面经 ConfigServicePort 使用，数据面禁止出现 Nacos 类型；消费计划 01-06；须避开 datasource-nacos 拖入的 1.4.2 |
| Sentinel 1.8.6 core and datasource-nacos rule format | INTEGRATE | 本阶段仅用于依赖收敛校验与规则文档格式，不承载限流判定；消费计划 01-01 / 01-06；版本由 SCA 2021.0.5.0 BOM 锁定 |
| Caffeine 2.9.3 bounded L1 cache | INTEGRATE | 规则与快照的 L1 缓存上界；消费计划 01-01 / 01-04；2.9.3 是 Boot 管理的最后一个 Java 8 线 |
| Micrometer 1.9.6 meter registry and prometheus registry | INTEGRATE | MeterRegistry 端口、PreRegisteredSeries、tag 白名单；消费计划 01-08；基础模块不得出现 Micrometer 类型 |
| JMH provided-scope benchmark harness | INTEGRATE | 性能预算实测基准台，provided 作用域不进运行时制品；消费计划 01-05 / 01-08；执行方式由 01-VALIDATION.md 固定为 -Pjmh 走 test 阶段 |
| Sentinel built-in cluster flow control (cluster-client / cluster-server) | OPT-OUT | 热路径上 ChannelPromise.await(20ms) 阻塞调用线程且调用方无法设限，另需常驻服务且自身无故障转移；01-01 bannedDependencies 拦截 |
| sentinel-datasource-redis as a second rule store | OPT-OUT | 规则源唯一为 Nacos，双源产生优先级歧义，项目已明确否决；01-01 bannedDependencies 拦截 |
| SpEL expression evaluation for pushed rules | OPT-OUT | 非沙箱，暴露方法调用、类型引用与 bean 解析，Nacos 下发的表达式等同上下文内任意代码执行；01-02 表达式面测试拦截 |
| QLExpress 3.x as the expression engine | OPT-OUT | 默认未开启隔离且有沙箱逃逸记录；本阶段已选定 Aviator 5.4.4 作为唯一表达式引擎 |
| Jedis as a second redis client | OPT-OUT | 与 Lettuce 二选一，禁止同时出现在 classpath，否则出现两套客户端与两套连接池语义 |
| Aviator per-request compile on the hot path | OPT-OUT | ASM 逐表达式生成类，语法错误或恶意推送会累积 Metaspace 直至 OOM；热路径必须预编译并缓存，01-02 强制 |
| Caffeine 3.x | OPT-OUT | 需要 Java 11，违反 requireJavaVersion 的 JDK 8 契约，类加载期即失败 |
| Logback 1.3+ or 2.x with SLF4J 1.7.x | OPT-OUT | 需 SLF4J 2.x，与 Boot 2.7.6 锁定的 SLF4J 1.7.36 不匹配，将无 provider 且日志静默消失，掩盖限流与降级事件 |
| Nacos client 1.4.x | OPT-OUT | 1.4.x 是 sentinel-datasource-nacos:1.8.6 硬声明的版本，对 Nacos 2.x 服务抛 NoSuchMethodError；由 dependencyConvergence 强制收敛到 2.2.0 |

## assumption_delta 记录

检测器与人工判断均命中 **pluralization（复数化）** 信号，对应本项目一项**已批准**的身份模型变更（`INV-1` / `INV-2`）：

- **变更**：对外契约由「单份整文档」细化为「按能力分段」——`decision` / `rate-limit` / `canary` 三段独立版本、独立原子性边界、互不连累。依据 `01-CONTEXT.md:86-87`：`INV-1` 生效版本号 ↔ 生效内容一一对应（禁止部分应用）、`INV-2` 原子切换与 schema 校验均按能力段且跨能力互不连累。（此前此处误引 `INV-4`——按 `01-CONTEXT.md:89`，`INV-4` 是「启动断言 `maxSchemaVersion[N] >= 已定义最高版本号` 否则 fail-fast」，与身份模型无关，落在 `01-06` 而非本条。）
- **决策**：**采纳**。这是 Phase 1 唯一被批准的身份模型变更，`INV-1` / `INV-2` 为 LOCKED 来源。
- **落地**：`01-03` schema 定义与 `SchemaParseTest`；`01-05` 路由表面与 `SnapshotStore` 契约位。
- **未采纳项**：数据面 / 控制面拆成两套制品。`ARCHITECTURE.md` 已用模块边界 + `bannedDependencies` 达成「SDK 不接管宿主版本」的目标；拆制品会改变 reactor 结构并使本阶段全部 48 条需求的验证命令失效，不采纳。

## 覆盖结论

- 消费面 7 项全部 INTEGRATE，reason 已内联版本、消费计划与证据来源。
- opt-out 面 8 项均来自 `STACK.md` 的 "What NOT to Use"，并已在 `01-01` 的 `bannedDependencies` 与构建期闸门处给出断言位置。
- 与 `01-RESEARCH.md` Validation Architecture、`01-CONTEXT.md` 的 `INV-1..INV-15` 无冲突。
- 本清单与各 PLAN 的 `<assumption_delta_decision>` 块一致；后续阶段新增库时须先更新本文件再进计划。
