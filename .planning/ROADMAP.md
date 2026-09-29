# Roadmap: 信用卡实时数据系统 · 基础能力平台

## Overview

本路线图把 152 条 v1 需求映射到 6 个阶段，交付一个进程内 Java 8 SDK（限流 / 灰度 / 两级缓存）+ 一条版本化治理通道（管理 API）。业务服务引入依赖、加一个注解或调用一次 guard API，即获得生产可用的三类能力；每一次决策事后可追溯到具体规则与规则版本。

**阶段结构不是本次推导的产物。** 六个阶段缝直接采用 `research/SUMMARY.md` 的 Phase-Seam Map（P1–P6，:285–327），该划分由 ARCHITECTURE 与 PITFALLS 两个独立研究员各自得出且无冲突需裁决（:348–350）。本文件只做需求归属、成功标准推导与三条缝修正的落实。

**三条缝修正已落实**（`SUMMARY.md:352–353` + 阶段顺序硬约束 `:331`）：

1. **meter 契约与有界 tag 词汇表前移到 Phase 1**（OBS-09、SEC-04）。理由：meter registry 位于 C3 `foundation-observer`，而 P2 与 P3 两个能力阶段都要经它发指标——接口不存在则无法编译。Phase 5 只交付 sink 传输、批写器与健康贡献器。
2. **故障默认值按业务线参数化**（R6）作为 Phase 2 / Phase 3 的验收标准修正（RL-17/18/19/20、CAN-06），不是新阶段；Phase 6 用真实数据线验证。
3. **Phase 1 未拆分。** `SUMMARY.md:333` 允许拆为 P1a / P1b，本路线图**选择不拆**，理由与备选拆分方案见下方「Phase 1 规模说明」。

**Research 标记图例**（映射自 `SUMMARY.md:357–371` 的权威 research flag）：

| 标记 | 含义 | 规划命令 |
|------|------|----------|
| 🔬 YES | 规划前必须先做研究，否则无法判定通过/失败 | `/gsd-plan-phase N --research-phase N` |
| ⚙️ MODERATE | 编码前有必须先定死的决策项（decide-before-coding），或 scoping 期需一次定向研究 | 先 `/gsd-discuss-phase N` 收敛决策项 |
| ➖ NO | 跳过研究。本阶段的风险是**返工**不是设计——它接线的契约已在 P1 冻结 | `/gsd-plan-phase N --skip-research` |

**本里程碑无 UI 阶段。** 控制台前端界面是独立子应用（`REQUIREMENTS.md` Out of Scope），本子应用只交付管理 API 契约。`grep` 式的 UI 关键词检测会命中 "interface"（两级接口可插拔）等 Java 语义用词，属误报，故不输出 UI hint。

---

## Phases

**Phase Numbering:**
- 整数阶段 (1–6)：v1 里程碑的规划工作，编号与 `research/SUMMARY.md` 的 P1–P6 一一对应
- 小数阶段 (1.1, 2.1)：紧急插入（标记 INSERTED），由 `/gsd-phase --insert` 创建

- [ ] **Phase 1: 规则内核与表达式沙箱** 🔬 - 规则 schema 与双版本信封、原子切换、last-known-good 落盘、Aviator 5.4.4 沙箱、DecisionRecord 契约、meter 契约与有界 tag 词汇表、13 模块 reactor 与 enforcer 防火墙
- [ ] **Phase 2: 限流执行与故障策略** 🔬 - 注解 + 编程式 guard、表达式路由、Redis + Lua 集群令牌层、ClusterGate 状态机、reason 分类降级、业务线参数化故障默认值、JMH 压测台与 ≥3× 余量证据
- [ ] **Phase 3: 灰度评估器** ⚙️ - 白名单 > 流量百分比 > hash 桶、稳定分桶、targeting-key 优先级、fail-open 默认与可覆写、改桶影响量化端点
- [ ] **Phase 4: 两级缓存** ⚙️ - L1/L2、per-key single-flight、单调序列号 + 周期对账、L1 TTL 硬上界、sensitive 默认拒绝
- [ ] **Phase 5: 决策可观测性** ➖ - sink 传输、批写器、Prometheus 指标面、SDK 健康贡献器、示例服务内的离线决策消费者
- [ ] **Phase 6: 管理 API 与接入验证** ⚙️ - 冻结的 /api/v1 契约、CRUD/发布/回滚/审计、≥2 个真实服务接入（集群模式）、生产形态压测报告、敏感数据路径清单

---

## Phase Details

### Phase 1: 规则内核与表达式沙箱
**Goal**: 任何来自配置中心的规则，在进入决策路径之前都已被校验、编译、版本化，并带版本号落到一份可事后追溯的决策记录与一组有界指标上；表达式求值在 JDK 8 默认配置下无法逃逸出沙箱。
**Depends on**: Nothing (first phase)
**Research**: 🔬 **YES** — 全路线图研究需求最高的一相。architecture 并不含糊，**含糊的是签字依据**：在 JDK 8 默认配置下跑完固定的攻击用例矩阵、对表达式解析器/求值器做 fuzz、并用测试证明步数预算可被触达之前，Phase 1 无法判定通过。**不得仅凭文档评审签字。** 次要需求：Boot 2.6.13 兼容 smoke 测试（R3 的 P1 退出条件）。
**Requirements**: FND-01, FND-02, FND-03, FND-04, FND-05, FND-06, FND-07, FND-08, FND-09, FND-10, GOV-01, GOV-02, GOV-03, GOV-04, GOV-05, GOV-06, GOV-07, GOV-08, GOV-09, GOV-10, GOV-11, GOV-12, GOV-13, GOV-14, GOV-15, EXPR-01, EXPR-02, EXPR-03, EXPR-04, EXPR-05, EXPR-06, EXPR-07, EXPR-08, EXPR-09, EXPR-10, EXPR-11, EXPR-12, OBS-02, OBS-03, OBS-05, OBS-06, OBS-07, OBS-09, SEC-02, SEC-03, SEC-04, SEC-07, PERF-04
**Success Criteria** (what must be TRUE):
  1. **规则集不存在「部分生效」的可观测状态，且原子边界是「能力段」而非整份。** 一份被篡改（内容哈希不匹配）、结构错误或语义错误的规则集推送进来时，**出错的那个能力段整段拒绝、完整保留该能力的前一版本（一字不差）**，其他能力段不受牵连、不连累；随后到达的该能力 `ruleSetVersion` 更小或相等的推送被丢弃且该能力当前版本号不变。任何一个能力段都不存在「部分字段生效」的可观测状态。[GOV-01, GOV-02, GOV-04, GOV-06, GOV-10]
  2. **冷启动默认要求配置中心可达，「无规则」绝不静默。** Nacos 不可达但磁盘上有 last-known-good 时，进程以磁盘快照继续提供能力且健康正常；**磁盘上也没有任何可用版本时，默认（`STRICT`）进程启动大声失败**；显式 opt-in 的 `RESILIENT` 策略降级为空快照 + `foundation.rule.loaded=false` + 健康 **DOWN**。namespace / group / dataId 配错时启动**大声失败**，不存在「启动正常但无规则」；快照目录不可写被监控。[GOV-05, GOV-09, GOV-11, GOV-12, FND-05]
  3. **表达式沙箱在 JDK 8 默认配置下不可逃逸，且性能是实测数字。** 反射逃逸 / 构造器逃逸 / 静态方法 / `ClassLoader`-`Thread`-`Runtime` 链 / 文件与网络 IO / 死循环与深递归 / 超大字符串与数组 / ReDoS / 深嵌套共 9 类攻击用例全部无法逃逸；启动日志中可查到「沙箱未被关闭」的断言结果；JMH 给出第 4 层（表达式求值）的实测 p50/p99 数字而非估算；`BudgetExceededException` 可由测试触达。[EXPR-02, EXPR-03, EXPR-04, EXPR-05, EXPR-06, EXPR-07, EXPR-09]
  4. **库纪律成立：SDK 不接管宿主。** 非 Spring 宿主（上述依赖全部不在 classpath）可启动并提供能力；公共 API 中不出现 Sentinel / Nacos / Caffeine 任何类型；所有集成依赖为 `<optional>` 或 `provided`；一个 Boot 2.6.13 的消费方工程引入依赖后启动成功；数据面模块依赖 `spring-*` 时构建失败；公共 API 破坏性变更被二进制兼容闸门拦下。[FND-01, FND-02, FND-03, FND-04, FND-06, FND-07, FND-08, FND-10]
  5. **远程推送无法无限制消耗堆内存，指标基数不可逆地受限。** 10 万卡 BIN 基数压测后，路由表大小仍等于已编写规则数、不随运行时长或实体数增长；编译缓存为有界 LRU 并导出淘汰指标；决策记录只落在有界 ring buffer，满缓冲时丢弃并计数、业务线程不被阻塞，且不存在 `log.isDebugEnabled()` 式门控；Prometheus 命名空间单一、tag 词汇表有界（无卡 BIN / 交易号 / 原始表达式文本），并由一条断言「白名单外字段进不了指标标签」测试强制。[GOV-13, GOV-14, OBS-05, OBS-06, OBS-07, OBS-09, SEC-02, SEC-03, SEC-04, SEC-07, PERF-04]
**Plans**: 8 plans

| Plan | Wave | 目标 | Requirements |
|---|---|---|---|
| 01-01 | 1 | 13 模块 reactor、核心契约、依赖防火墙与单条规则路径端到端 tracer | FND-02, FND-03, FND-04, FND-06, FND-07, FND-08, FND-10 |
| 01-02 | 2 | Aviator 5.4.4 有序沙箱构造、9+1 攻击矩阵与编译期静态闸门 | EXPR-01, EXPR-02, EXPR-03, EXPR-08, EXPR-09 |
| 01-03 | 3 | 能力段 schema、段级原子拒绝、版本单调闸门与不可变快照 | GOV-01, GOV-02, GOV-03, GOV-04, GOV-06, GOV-08, GOV-10, FND-01, FND-09 |
| 01-04 | 3 | env 白名单、步数/超时预算、有界 LRU 编译缓存与 `ExpressionEngine` SPI | EXPR-05, EXPR-06, EXPR-07, EXPR-10, EXPR-11, EXPR-12, SEC-07, GOV-14 |
| 01-05 | 4 | last-known-good 落盘、目录探针、10 万 BIN 路由表与差量替换 | GOV-05, GOV-12, GOV-13, GOV-15 |
| 01-06 | 5 | Nacos 接入、冷启动 STRICT/RESILIENT、去抖与 schema 启动断言 | FND-05, GOV-07, GOV-09, GOV-11 |
| 01-07 | 4 | DecisionRecord 契约、有界 ring buffer、脱敏与不可变性 | OBS-02, OBS-03, OBS-05, OBS-06, OBS-07, SEC-02, SEC-03 |
| 01-08 | 5 | 有界指标 tag 词汇表、组合预注册、基数守护测试与 JMH 压测台 | OBS-09, SEC-04, PERF-04, EXPR-04 |

#### Phase 1 规模说明（拆分决定已记录，未执行）

Phase 1 承载 48 条需求，是全路线图最重的一相。`SUMMARY.md:333` 允许拆为 P1a / P1b，本路线图**选择不拆**，三条理由：

1. **8 项跨阶段不变量在研究中被声明为一个整体契约**（`:335–346`）。拆分会把「版本信封 + 模块边界 + last-known-good」与「DecisionRecord 字段集 + meter 契约」分置两相，削弱「不变量清单才是契约」这一论证基础。
2. **P1b 单独不产生任何可观察的用户行为。** 它只交付 observer 契约 + ring buffer + meter 契约——纯内部契约，成功标准会退化成实现任务。
3. **重新编号会切断 P1–P6 与 5 份研究文档的交叉引用**，而阶段编号本身在研究中被反复引用。

**若执行中发现 P1 过大**，通过 `/gsd-phase --insert` 拆为 1.1 / 1.2，8 项不变量的落位为：
`版本信封 + 单调 ruleSetVersion` → 1.1；`DecisionRecord 字段集` → 1.2；`Copy-forward 回滚语义` → 1.1（P6 补执行侧）；`模块边界 + enforcer 规则` → 1.1；`last-known-good 原子落盘` → 1.1；`ClusterGate 为状态而非 try/catch` → Phase 2；`预注册有界 meter + 有界 tag 词汇表` → 1.2；`/api/v1 契约冻结` → Phase 6。

### Phase 2: 限流执行与故障策略
**Goal**: 业务服务在方法上加注解或调用一次 guard API，即在毫秒级授权线上获得配额正确、降级可查、故障方向可辨的限流判定。
**Depends on**: Phase 1
**Research**: 🔬 **YES** — R5 已消除「用哪种令牌实现」的设计问题，因此这里是**测量而非设计**：在实际 Redis 拓扑上测 Lua 脚本 p50/p99（STACK 的数字是 LOW 置信度社区基准）、建立相对实测峰值 ≥3× 的余量、并在热路径构建过程中测量第 4 层与满缓冲 ring buffer。**JMH 压测台在本相落地**（须在热路径被构建的过程中测量），生产形态报告是 Phase 6 交付物。
**Requirements**: RL-01, RL-02, RL-03, RL-04, RL-05, RL-06, RL-07, RL-08, RL-09, RL-10, RL-11, RL-12, RL-13, RL-14, RL-15, RL-16, RL-17, RL-18, RL-19, RL-20, RL-21, RL-22, RL-23, RL-24, RL-25, RL-26, RL-27, RL-28, RL-29, OBS-10, PERF-01, PERF-02, PERF-03, PERF-05, PERF-07, PERF-08
**Success Criteria** (what must be TRUE):
  1. **治理通道闸门（硬约束，来自 `SUMMARY.md:331`）。** 在 Phase 6 的治理发布通道就绪之前，任何生产采纳方环境中都不存在本相产出的限流规则；开发验证环境可经直接 Nacos 写入跑通，且该路径在文档中被显式标注为绕过校验、版本化、审计与回滚的非治理路径——**API 层校验不是安全边界**。同一规则若直接写 Nacos 会到达同一段代码。
  2. **集群配额是真正的全局配额，降级状态可查且方向可辨。** 集群模式下所有实例共享同一份配额，N 个并发请求**不共享**一次配额检查（实际上限不等于 N 倍）；令牌层不可用时自动切本地计数并把阈值按实例数折算（配额不被静默放大）；`degraded_to_local{reason}` 非零即告警，且「放大配额（后端过载而监控显示正常）」与「缩小配额（授权量骤降）」走**不同告警与不同 on-call 手册**；无任何全局 fail-open/fail-close 总开关，降级状态可查询。[RL-06, RL-07, RL-08, RL-09, RL-10, RL-11, RL-12, RL-13, RL-14, RL-26, RL-27, RL-28, RL-29]
  3. **每次决策都带版本与原因码。** 放行与拒绝各产出一条决策记录，携带 `ruleId`、`ruleVersion`、当时剩余配额、实际生效的 `failStrategy` 与机器可读原因码；`LIMITED_BY_RULE` / `NO_RULE_MATCHED` / `DEGRADED_LOCAL` / `FAIL_CLOSE` 四种原因在记录与指标上可区分；白名单 / 旁路在配额消费**之前**求值且单独审计；Spring AOP 自调用不会绕过 advice（编程式 guard API 是实际执行者）。[RL-01, RL-02, RL-03, RL-04, RL-05, RL-21, RL-22, RL-23, RL-24, RL-25, OBS-10]
  4. **故障默认值按业务线参数化，清算入站的例外被钉死。** 卡组织清算报文入站这条业务线在 SDK 中硬编码为 fail-open，且该例外写入 code-review 检查项，使后续「统一默认值」重构无法改回去；每一条 fail-open 规则都配有独立静态本地回退配额——「配置不可用 + 无回退」不等于无限通行；其他业务线的默认值由管理 API 显式设置而非隐式继承。[RL-17, RL-18, RL-19, RL-20]
  5. **性能以本环境实测数字判定，且两个预算线不混算。** SDK 自身 P99 开销与集群 RTT 作为**两条独立预算线**分别报告；集群令牌层在真实拓扑下满足峰值并留出 **≥3× 余量**的实测证据（这是架构决策闸门：据此判定是否改为「本地快路径 + Redis 粗粒度对账」）；热路径 1–8 层无锁、无无界结构；**报告中不出现任何社区基准数字**。[PERF-01, PERF-02, PERF-03, PERF-05, PERF-07, PERF-08]
**Plans**: TBD

### Phase 3: 灰度评估器
**Goal**: 一条灰度规则推送后无需重启即在本地生效；同一主体每次求值落在同一个桶；配置不可用时退回全量而不是随机。
**Depends on**: Phase 1
**Research**: ⚙️ **MODERATE** — 不是研究缺口，而是**编码前必须定死**的决策项：targeting-key 解析顺序（方法入参 → 路由头 → 主体标识，全局统一，三条业务线可能需要不同顺序）与改桶拒绝规则。flagd 分桶的既有研究已在 `PITFALLS P12`，不需重复。
**Requirements**: CAN-01, CAN-02, CAN-03, CAN-04, CAN-05, CAN-06, CAN-07, CAN-08, CAN-09, CAN-10, CAN-11, CAN-12, CAN-13, CAN-14
**Success Criteria** (what must be TRUE):
  1. **热生效且顺序闸门成立。** 灰度规则经 Nacos 推送后本地生效、无需重启，且生效过程中不存在「部分规则已生效」的中间态；与 Phase 2 同一闸门——在 Phase 6 治理发布通道就绪前，生产采纳方环境中不存在灰度规则，开发验证走被标注的非治理直写路径。[CAN-02]
  2. **同一主体稳定落在同一桶。** 多维度组合判定按白名单 > 流量百分比 > hash 桶的顺序执行，且该顺序是契约的一部分；除 targeting key 外无其他输入进入哈希；规则不变时同一主体每次求值落在同一桶。[CAN-01, CAN-10, CAN-11, CAN-14]
  3. **「随机」是缺陷而不是回退。** 发布时未声明 targeting key 的规则被**拒绝发布**；运行期 targeting key 缺失时该规则 fail-open 为「此规则不匹配」并计指标，同一主体绝不随机跳变；改桶必须显式 `allowRebucket` 并被审计。[CAN-07, CAN-08, CAN-09, CAN-12]
  4. **改桶决策依据是数字而非直觉。** 管理 API 可针对一次候选改桶计算 moved-in / moved-out 比例；把 `bucketCount` 从 100 改为 1000 在未显式 `allowRebucket` 时被拒绝。[CAN-13]
  5. **决策可解释、可回滚，配置不可用时退回全量。** 每条灰度决策的记录含命中哪条规则、落在哪个桶、选中版本与实际生效的 `failStrategy`；灰度规则支持一键回滚；配置不可用时默认 fail-open 退回全量且该行为可逐条规则覆写，灰度侧的故障默认值同样按业务线参数化而非隐式继承。[CAN-03, CAN-04, CAN-05, CAN-06]
**Plans**: TBD

### Phase 4: 两级缓存
**Goal**: 一份通用的两级缓存在高并发下不把回源打成穿透，不返回超过声明硬上界的过期值，且默认拒绝把敏感数据写进本地层。
**Depends on**: Phase 1
**Research**: ⚙️ **MODERATE** — 两级一致性协议在 `PROJECT.md:113` 曾为开放项，**现已定案**（单调序列号 + 周期对账的收敛式协议），本相不再需要做协议选型决策。剩余需求是一次针对 at-most-once 失效与对账的定向研究，以及把该定案与 R4 的 `sensitive` 护栏一并落到代码形态。**这是 decide-before-coding 闸门，不是 API 研究缺口。**
**Requirements**: CACHE-01, CACHE-02, CACHE-03, CACHE-04, CACHE-05, CACHE-06, CACHE-07, CACHE-08, CACHE-09, CACHE-10, CACHE-11, CACHE-12, CACHE-13, CACHE-14, CACHE-15, CACHE-16
**Success Criteria** (what must be TRUE):
  1. **热点 miss 只回源一次。** 同一 key 的并发 miss 由 **per-key** single-flight 合并为一次回源，而不是 N 次；跨节点回源由分布式锁 + 双检保护，带超时与安全释放；同一毫秒到期的 N 个节点不会把回源打成雪崩（TTL 抖动按 key 哈希播种）。[CACHE-04, CACHE-05, CACHE-06, CACHE-11]
  2. **不一致的硬上界是 L1 TTL，且被强制与可查。** 代码强制 `l1Ttl < l2Ttl` 及比例约束；热键经 soft-TTL 后台刷新本地常驻；运维可查到「最多可能返回多旧的缓存值」这一上界。[CACHE-10, CACHE-12]
  3. **漏掉的失效被修复，而不是永久服务过期值。** 每次失效带单调序列号，乱序到达的被丢弃；一个断连 / 重启 / GC 停顿后永久漏掉的失效由周期对账任务在下一个对账周期内修复——丢失代价从「永久错误」降为「一个对账周期」。[CACHE-09]
  4. **失效症状在压测中被主动复现并可归因。** 「L1 命中率异常高而 L2 QPS 反而低、重启后数据变正确」这一组合被列为该故障的判定信号并在压测中复现，从而被识别为失效通道问题而非缓存高效。[`SUMMARY.md:251` 判定条件]
  5. **敏感数据默认拒绝，边界不可被绕过。** 未显式声明 `sensitive=false` 的缓存项**默认不写入 L1**、key 只以哈希前缀出现；高一致性调用方可通过每缓存 `l1Enabled` 开关完全绕过 L1；本地层 TTL 与容量上界（条数与权重双限）、可插拔两级接口、多维 key 与 O(1) 批量失效均可用；v1 不预置任何业务语义缓存；缓存模块与规则模块之间**双向**不存在依赖；决策路径上无同步 IO。[CACHE-01, CACHE-02, CACHE-03, CACHE-07, CACHE-08, CACHE-13, CACHE-14, CACHE-15, CACHE-16]
**Plans**: TBD

### Phase 5: 决策可观测性
**Goal**: 每一次决策都通过一条独立于业务日志的通道离开进程，事后可被查到，且不泄漏原始入参与高基数标签。
**Depends on**: Phase 2, Phase 3, Phase 4
**Research**: ➖ **NO** — 标准批写器 / 有界队列 / `MeterBinder` 模式，文档充分。**本相的风险是返工而不是设计**：`DecisionRecord` 字段集与 ring buffer 已在 Phase 1 冻结，因此本相只接传输线。判定本相的依据是 Phase 1 的产出，而不是新的研究。若 Phase 1 漂移了，本相无法补救。
**Requirements**: OBS-01, OBS-04, OBS-08, OBS-11, OBS-12, OBS-13, OBS-14, SEC-01
**Success Criteria** (what must be TRUE):
  1. **任意历史决策可被查到并定位到当时的规则版本。** 通过离线消费者查询一条历史决策，得到其 `ruleId` / `ruleVersion` / 剩余配额 / 桶号 / 评估耗时；**按该 `ruleVersion` 反查得到的是当时的规则内容，而不是当前版本**（版本是快照的不可变字段，不是「记录当时的当前版本」）。[OBS-01]
  2. **决策记录只含白名单字段，且由测试强制。** 记录中仅出现 `ruleId` / `ruleVersion` / `dataLine` / `decision` / `remaining` / `bucket` / `latencyUs` / `failStrategy` / `degraded` 九项；原始入参、原始 key、完整表达式文本**无法**进入记录——由一条断言「白名单外字段进不了决策记录」的测试强制；调试通道另有 `EvalContext` 字段级脱敏与默认关闭的显式 debug 开关。[SEC-01]
  3. **决策通道独立于业务日志，保留期是显式属性。** 决策记录不混入宿主业务日志，不受宿主日志配置影响；保留期与脱敏策略是 sink 配置上的显式、文档化属性，而非隐含默认。[OBS-04, OBS-14]
  4. **指标面与健康面可读。** Prometheus 暴露 QPS、拒绝率、命中率与规则版本，命名空间单一、tag 有界；SDK 自身开销经 `MeterBinder` 自报；健康端点可查规则同步状态、降级状态与 Redis 连通性。[OBS-08, OBS-11, OBS-12]
  5. **不新增部署件，且批写器不重新引入阻塞。** 离线决策样本消费者位于示例服务内；sink 传输与批写器在 ring buffer 满时走丢弃并计数的路径，生产者不被阻塞，丢失量在指标上可见。[OBS-13]
**Plans**: TBD

### Phase 6: 管理 API 与接入验证
**Goal**: 规则有了一条受治理的发布通道，控制台子应用可按一份冻结的契约开工，并且两个真实业务服务已在集群限流模式下跑过生产形态压测与安全评审。
**Depends on**: Phase 2, Phase 3, Phase 4, Phase 5
**Research**: ⚙️ **MODERATE** — 交付前需要版本兼容矩阵与安全路径评审。这些是**验证任务，不是未知技术**。另需一个**显式风险声明而非沉默**：控制台子应用将基于一个既不强制多租户也不强制 RBAC 的 API 去建多租户 + 角色权限的 UI，这个缺口属于 P6 交接文档，不属于代码注释。
**Requirements**: API-01, API-02, API-03, API-04, API-05, API-06, API-07, API-08, API-09, API-10, API-11, API-12, API-13, API-14, API-15, API-16, API-17, API-18, API-19, SEC-05, SEC-06, PERF-06, DEL-01, DEL-02, DEL-03, DEL-04, DEL-05, DEL-06, DEL-07, RL-30
**Success Criteria** (what must be TRUE):
  1. **编辑、发布、回滚是三个独立操作，版本永不就地改写。** 规则增删改查、灰度发布与变更审计都走这条通道；编辑在发布前**不生效**；变更审计 append-only，且审计 ↔ 规则版本 ↔ 决策日志三方可通过同一组 ID 关联；回滚是**前向写入一个新的单调版本**而非重指既有版本（重指会使实例持有的版本与内容脱钩，决策记录中的版本不再标识内容）；schema 已推进时回滚返回 `409 SCHEMA_INCOMPATIBLE` 而不静默强转。[API-01, API-10, API-11, API-12, API-13, API-14]
  2. **契约纪律成立且控制台可据此开工。** `/api/v1` 契约冻结：每个变更操作带幂等键、支持 `ETag` / `If-Match`、错误遵循 RFC 7807、每个响应携带当前 `ruleSetVersion`、DTO 不暴露实体、`tenantId` 不出现在路径中、契约只做增量演进；未认证或未授权的调用**无法改变限流**（否则该端点就是 DoS 按钮）；API 预留租户 / 权限模型位。[API-02, API-03, API-04, API-05, API-06, API-07, API-08, API-09, API-15, API-18]
  3. **生效状态与真实配额可查。** 发布前校验规则并区分「没有配置任何规则」（`NO_RULES_CONFIGURED`）与「规则源不可用」（`RULE_SOURCE_UNAVAILABLE`）；健康 / 就绪端点上报的是**生效**配置状态而不是进程存活；有效全局配额（阈值 × 参与节点数 = 真实配额）可通过 API 查到。[API-16, API-17, API-19]
  4. **真实接入、真实压测、真实安全评审，且治理通道先于规则到达生产。** 至少 2 个业务服务完成真实接入并**均以集群限流模式运行**；生产形态压测报告给出分组件 p50/p95/p99/p999（含最坏表达式用例），集群 RTT 与 SDK 开销分列且不引用任何社区基准数字；敏感数据路径清单（逐个列出所有 Redis key 前缀、日志字段、指标标签）作为交付物交由采纳方安全评审；自本阶段起，规则进入生产采纳方的**唯一**路径是本治理通道。[DEL-04, DEL-06, RL-30, PERF-06, SEC-05, SEC-06]
  5. **交付物齐备，且已知缺口以文字声明为风险。** 交付 SDK + 集群令牌层（Redis + SDK 自有版本化 Lua 脚本，**v1 不新增独立部署件**）+ 示例服务 + 接入与运维文档 + 双 Boot 版本兼容矩阵（Boot 2.6.x / 2.7.x × SCA 2021.0.5.0）；令牌层与缓存层共用同一 Redis 时 key 命名空间分离并写入运维文档；交接文档**以文字显式声明**为风险：v1 不实现多租户隔离与角色权限（单一默认租户、单一服务账号，审计仍记录 principal），控制台子应用将基于一个不强制这两者的 API 建 UI。[DEL-01, DEL-02, DEL-03, DEL-05, DEL-07, API-18]
**Plans**: TBD

---

## 跨阶段不变量（不得在后续阶段被交易掉）

来源：`research/SUMMARY.md:335–346`。**不变量清单本身才是契约，阶段名不是。**

| 不变量 | 建立于 | 破坏后的代价 |
|---|---|---|
| `DecisionRecord` 字段集 | Phase 1 | 决策历史变得不可关联；已记录数据无法补字段，且**历史不可回填** |
| 版本信封 + 单调 `ruleSetVersion` | Phase 1 | 可追溯与回滚同时失去根基 |
| Copy-forward 回滚语义 | Phase 1 / Phase 6 | 每个历史版本变得歧义，事后分析不可能 |
| 模块边界 + enforcer 规则 | Phase 1 | 需在里程碑中途、时间压力下做多模块拆分 |
| last-known-good 落盘（原子写） | Phase 1 | Nacos 故障后的冷启动变成一次故障 |
| `ClusterGate` 是状态而非 try/catch | Phase 2 | 令牌层抖动变成授权线上的延迟故障 |
| 预注册有界 meter + 有界 tag 词汇表 | Phase 1（修正后） | 仪表盘一旦依赖上标签，基数极难回退 |
| `/api/v1` 契约冻结 | Phase 6 | 控制台子应用建在之后还会变的契约上 |

## 阶段顺序的硬约束

- **Phase 3 / Phase 4 的规则由 Phase 6 的发布通道书写。** 在 Phase 6 存在之前，把灰度或限流规则送进采纳方的唯一方式是直接写 Nacos——那绕过校验、版本化、审计与回滚。
- 因此：**Phase 1–5 可对直接 Nacos 写入做开发与验证，但灰度与限流规则不得在 Phase 6 治理发布通道就绪前到达生产采纳方。** 该闸门已写入 Phase 2 与 Phase 3 的成功标准。
- Phase 6 同时是「≥2 个真实接入」的发生地（交付标准），与上述顺序一致。

## 不产生阶段的内容

- **10 条 v2 需求**（V2-01…V2-10）不映射到任何阶段。触发条件见 `REQUIREMENTS.md`。
- **16 项排除项**（`REQUIREMENTS.md` → Out of Scope）不产生阶段。其中三项与本路线图直接相关并已在阶段标准中体现为**反向断言**：控制台前端界面（独立子应用）、非 Java SDK（v1 仅 Java）、业务语义缓存（CACHE-08 显式断言 v1 不预置）。熔断 / 隔离舱不产生阶段；`ClusterGate` 使用的本地失败计数器是降级机制，不违反该排除项。
- **无 UI 阶段**：控制台前端是独立子应用，本里程碑只交付管理 API 契约。

## Progress

**Execution Order:** 1 → 2 → 3 → 4 → 5 → 6（整数顺序执行；小数阶段如经 `/gsd-phase --insert` 创建则按数字序插入其间）

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 规则内核与表达式沙箱 | 0/8 | Not started | - |
| 2. 限流执行与故障策略 | 0/TBD | Not started | - |
| 3. 灰度评估器 | 0/TBD | Not started | - |
| 4. 两级缓存 | 0/TBD | Not started | - |
| 5. 决策可观测性 | 0/TBD | Not started | - |
| 6. 管理 API 与接入验证 | 0/TBD | Not started | - |
