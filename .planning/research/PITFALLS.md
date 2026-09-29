# Pitfalls Research

**Domain:** 信用卡实时数据系统 · 基础能力平台（进程内 Java SDK：限流 / 灰度 / 两级缓存 / 决策可观测性 / 规则管理 API）
**Researched:** 2026-09-29
**Confidence:** MEDIUM

> **Confidence rationale:** Sentinel / Nacos / Spring Boot 行为结论为 HIGH（官方文档、源码与 issue 原文可查）。表达式引擎与 CVE 结论为 MEDIUM（依赖厂商公告与第三方研究，落地前需逐条复核）。阶段编号是本研究提出的建议名称，ROADMAP 尚未创建。
>
> **Proposed phase names (adopt into ROADMAP):** P1 规则内核与表达式沙箱 · P2 限流执行与故障策略 · P3 灰度评估器 · P4 两级缓存 · P5 决策可观测性 · P6 管理 API 与接入验证

## Critical Pitfalls

### Pitfall 1: SpEL 在本技术栈上无法修补，任何"包一层沙箱"的 SpEL 方案都是假沙箱

**What goes wrong:**
规则来自 Nacos 下发，表达式在业务进程内求值。使用 SpEL 时：表达式可经 `SimpleEvaluationContext` 守卫绕过、`TypeLocator`、构造器与静态方法访问逃逸，达到远程代码执行；逃逸后拿到的是**业务服务进程的权限**——在刷卡授权应答线上等同于直接触达授权数据面。`CVE-2026-59283`（SimpleEvaluationContext guard bypass）与 `CVE-2026-41849/41850/41851/41852` 均落在 Spring Framework 5.3.x 受影响区间。

**Why it happens:**
`PROJECT.md:107` 已正确识别"配置下发 + 无沙箱 = 开放代码执行"，但容易误以为"用 Spring 官方 `SimpleEvaluationContext` 包一层"即满足该约束。JDK 8 是硬约束（`PROJECT.md:86`），而 5.3.x 分支已无修复版本（OSV 对 5.3 分支标记 `Last Affected: 5.3.39`，fixed 为空）。Spring Boot 2.7.6 锁定 Spring Framework **5.3.24**（Boot 2.7.6 release notes：*Upgrade to Spring Framework 5.3.24 #33131*），甚至低于 5.3.26 / 5.3.27 这两个更早的 SpEL 修复线。**"升级 Spring Framework 来修"这条路被 JDK 8 堵死。**

**How to avoid:**
1. **禁止 SpEL 承接配置下发的表达式**，无论是否包 `SimpleEvaluationContext`。写入 ADR 并固化进 code review checklist。
2. 引擎默认选 `PROJECT.md:114` 的第三项——**受限自研 DSL**：只支持字面量、字段访问（限定在显式传入的 `EvalContext` 白名单字段）、比较/逻辑/算术、有限集合操作。方法调用语法**不存在于语法中**，而不是运行时拦截。
3. 若因性能必须引入第三方引擎：隔离 ClassLoader 运行 + AST 解析后按节点类型白名单裁剪（未知节点拒绝编译）+ 表达式长度上限 + AST 节点数上限 + 求值步数上限。
4. 管理 API 的表达式校验**不是安全边界**——Nacos 直连写入同样可达。

**Warning signs:**
- 业务代码直接 `new SpelExpressionParser()`；
- `EvalContext` 放入 DTO 对象而非扁平化 `Map<String, Object>`；
- 规则中出现 `#root`、`T(java.lang.Runtime)`、`new `、`.class`；
- Nacos 出现 `dataId` / `group` 不在本应用白名单内的写入。

**Phase to address:** P1 规则内核与表达式沙箱（必须早于 P2 的任何表达式路由编码）

**Blast radius:** 全量。表达式求值在 SDK 进程内、与业务服务同权限，一条可写配置即可横向影响所有接入方；线上表现为授权应答线、内部账务线、清算入站线同时失陷。

---

### Pitfall 2: 第三方引擎的"沙箱"多为 opt-in，且可被表达式自身关闭

**What goes wrong:**
把安全能力寄托在引擎自带开关上，而部分开关是运行期可变、或可从表达式上下文被影响的，形成"以为有沙箱、实际没有"。已知风险：QLExpress 安全策略为 opt-in（默认不开），且存在从表达式上下文改写 `forbidInvokeSecurityRiskMethods` 的路径；即使开启白名单仍存在 OOM 类 DoS 绕过。MVEL 无沙箱语义（关联 `CVE-2023-50571`）。AviatorScript 关联 `CVE-2021-41862`（CVSS 9.8，影响至 5.2.7，历史公告未见修复版本）。

**Why it happens:**
选型时以"库文档声称支持安全模式"为准，未验证**默认行为**与**可绕过路径**，也未在 JDK 8 上跑实际 PoC。这正是 `PROJECT.md:114` 遗留 TBD 最易翻车之处。

**How to avoid:**
- 选型阶段对每个候选在 JDK 8 上跑固定攻击用例矩阵并留档：反射逃逸、构造器逃逸、静态方法调用、`ClassLoader`/`Thread`/`Runtime` 链、文件与网络 IO、死循环与递归、超大字符串与数组、ReDoS、深层嵌套。
- 只有**默认配置下**全部用例失败的引擎才进入候选。
- SDK 在引擎之外再加一层（AST 白名单 + 字段白名单 + 步数/长度上限）。引擎沙箱是第二道防线，不是第一道。
- 采纳受限自研 DSL 作为默认路径，第三方引擎降级为"经 ADR 论证后启用"的例外。

**Warning signs:**
- 代码出现 `setForbidInvokeSecurityRiskMethods(false)` 或 `AviatorEvaluator.setOption(...)` 等显式放宽；
- 引擎初始化在 `@PostConstruct` 之外且无审计包裹；
- 表达式异常被 catch 后仅打日志并**按 pass 继续**——同时丢掉了"该规则不可信"的信号。

**Phase to address:** P1

**Blast radius:** 同 Pitfall 1（进程内代码执行）。额外风险：某服务为性能例外放宽引擎配置后，该放宽会随 SDK 升级传播到其他接入方。

---

### Pitfall 3: 表达式 DoS 打满授权应答线程池——沙箱不解决可用性

**What goes wrong:**
即使完全封死代码执行，表达式仍可打满 CPU：死循环、深度嵌套、巨型字符串重复拼接，以及最现实的一类 **ReDoS**（恶意回溯正则）。JDK 8 的 `java.util.regex` 没有后续版本引入的启发式硬化。授权应答线是毫秒级、高 QPS 峰值的（`PROJECT.md:77`），一条从 Nacos 推下来的坏规则足以打满线程池，且对所有接入方同时生效。

**Why it happens:**
"沙箱化"通常被理解为"安全"（不逃逸），而**可用性**是正交维度。规则内容由运营书写，人可读但计算量可爆炸。

**How to avoid:**
- 求值必须有**步数预算**：AST 解释执行时计数，超过阈值抛 `EvaluationBudgetExceeded`；不要用 `Thread.interrupt` 或超时线程池做超时（见 Pitfall 13 的线程池污染）。
- 表达式长度上限（如 512 字符）、AST 节点数上限（如 128）、字段访问深度上限。
- **禁止表达式内出现正则**。若业务确需匹配，走预编译的具名匹配器（`PROJECT.md:31` 已要求预编译），由 SDK 在代码中注册，规则只引用匹配器 ID。
- 嵌套深度上限（建议 ≤ 5），并对 `Math.*`、字符串操作、集合操作设置每步单位成本上限。
- 压测中必须包含"最坏表达式"用例，把 SDK 自身 P99 开销与表达式开销**分开**计量（`PROJECT.md:96` 性能预算）。

**Warning signs:**
- 授权应答 P99 突增但 Sentinel 计数无异常（说明不是流量问题而是求值耗时）；
- 某 `ruleId` 的决策耗时指标长尾明显高于同批规则；
- Nacos 规则变更后 60s 内 P99 抬升且不自愈。

**Phase to address:** P1

**Blast radius:** 全量可用性。单条坏规则即可同时拖垮所有接入服务的授权应答路径；因限流 SDK 位于业务调用链前段，劣化会同时表现为超时与限流拒绝，掩盖真实根因。

---

### Pitfall 4: 自研表达式路由层可能重蹈 Sentinel Resource 爆炸的覆辙

**What goes wrong:**
`PROJECT.md:81` 明确指出：直接按商户 / 卡 BIN / 机构建 Sentinel Resource 会导致数量随实体数线性增长、内存爆炸，因此自研"注解注入点 + 表达式路由"层。**但自研层若在编译缓存或规则 Map 上以表达式原文（或入参值）为 key，同样会线性膨胀**：每张卡 BIN、每个商户、每笔交易若生成不同的表达式实例，编译缓存、规则快照、决策记录三处同时被撑爆，表现为规则加载耗时上升、快照内存占用持续增长、单次配置推送耗时超过心跳窗口。

**Why it happens:**
规避了 Sentinel 的显式基数问题，却没有把"基数控制"当作路由层的一等设计目标。表达式路由天然把"规则数"与"入参基数"解耦，但实现时容易图省事直接 `expressionMap.put(rawExpression, compiled)`，把去重责任推给规则编写方。

**How to avoid:**
- **编译缓存 key 必须是表达式原文的规范化形式**（trim + 空白归一），并且用有界 LRU（如 1000 条）+ 明确的淘汰指标；禁止无界 `ConcurrentHashMap`。
- 路由规则（表达式 → `ruleId` 的映射表）与被匹配的业务数据**基数必须分离**：路由表条数等于"运营写下的规则条数"，与商户/BIN 数量无关。断言该不变式：路由表大小不随运行时长增长。
- 决策记录中的表达式以 `ruleId` + 规则版本引用，**不内联表达式全文**（否则每笔交易一条长文本，日志量与内存双爆）。
- 规则快照加载做**差量替换**而非全量重建，避免每次推送触发全表重编译。
- 压测中显式加入"高基数入参"用例（10 万不同 BIN），监控规则表大小、编译缓存命中/淘汰率、快照加载耗时。

**Warning signs:**
- 编译缓存 size 随运行时长单调增长且无淘汰；
- Nacos 推送后规则生效延迟随运行时长线性变长；
- 决策日志文件大小增速远超决策 QPS 增速。

**Phase to address:** P1（路由层与编译缓存设计），并在 P2 用 Sentinel 真实流量回归验证

**Blast radius:** 接入服务的内存与 GC。表现为长周期运行后 Full GC 频率上升、授权应答 P99 抬升，且**难以复现**（只在持续运行数小时后出现），是最难定位的一类故障。

---

### Pitfall 5: 集群降级静默生效，把集群配额悄悄放大 N 倍

**What goes wrong:**
这是本项目最容易发生、且**最不可能被察觉**的配额事故。Sentinel 集群限流有两个叠加机制：

1. `DefaultTokenService.requestToken` 在 Token Server 上按 `flowId` 查规则，**查不到直接返回 `NO_RULE_EXISTS`**；
2. `ClusterFlowChecker.applyTokenResult` 对 `NO_RULE_EXISTS` / `BAD_REQUEST` / `FAIL` / `TOO_MANY_REQUEST` 一律走 `fallbackToLocalOrPass`，而 `ClusterFlowConfig.fallbackToLocalWhenFail` **默认就是 `true`**。

叠加第三个机制更糟：`ClusterFlowChecker.calcGlobalThreshold` 在 `thresholdType = FLOW_THRESHOLD_AVG_LOCAL`（**默认值**）下返回 `count × getConnectedCount(flowId)`。也就是说运营配的"集群 1000 QPS"，在 5 个客户端节点上实际是 **5000 QPS**；某个客户端心跳超时掉线后变成 4000 QPS，扩容后变成 7000 QPS——**全局阈值随连接数静默漂移**。

而 `flowId` 不匹配的典型原因正是自研路由层：路由层自己生成 `flowId`，Token Server 侧规则由 Sentinel Dashboard / 数据源下发，两侧一旦版本不同步就是 `NO_RULE_EXISTS`。官方 issue #2457 记录了完全相同的现象——配置了集群限流规则但 `ClusterFlowRuleManager.FLOW_RULES` 为空，日志只打 `no rules in token server`，**集群限流不生效并静默降级为单机限流**，无任何异常抛出。

**Why it happens:**
`fallbackToLocalWhenFail = true` 与 `thresholdType` 默认值都不是"看起来危险"的配置，不需要有人做错事就会发生。危险在于**降级路径与成功路径返回同样的业务结果**（放行），只有内部指标不同，因此没有任何业务侧告警会触发。

**How to avoid:**
- **显式设置** `fallbackToLocalWhenFail`，不依赖默认值；`thresholdType` 显式设为 `FLOW_THRESHOLD_GLOBAL`（整体阈值）除非确实需要均摊，并用 `getConnectedCount` 的实测值反推"均摊模式下的真实全局阈值"写入运维文档。
- SDK 内部**禁止业务代码直接看到 `NO_RULE_EXISTS`**：拦截该状态，强制计入 `sentinel_cluster_degrade_total{reason="NO_RULE_EXISTS"}`，并按"配额正确性"而非"请求成功"告警。
- **`flowId` 必须单一来源**：由路由层确定性地从 `ruleId + 规则版本` 派生，或由 SDK 自己管理映射表；Token Server 侧规则与客户端 `flowId` 的生成逻辑必须同源、同版本。
- 启动与配置推送后做**规则一致性探针**：客户端主动校验已知 `flowId` 在 Token Server 侧可查，不一致则把该规则标记为 `DEGRADED` 并在 SDK 健康度里暴露。
- 提供管理 API 端点返回"当前生效的全局阈值 + 参与节点数 + 实际均摊系数"，让运营能直接看到配额真实值。

**Warning signs:**
- `sentinel_cluster_degrade_total` 持续非零但业务无投诉；
- Token Server 日志出现 `no rules in token server`；
- 相同规则在扩缩容后被限流量明显变化；
- 集群限流拒绝数远低于按 `count` 预期的拒绝数。

**Phase to address:** P2 限流执行与故障策略

**Blast radius:** 后端服务被超量打穿——即限流本身要防的那件事发生了，且**监控显示限流在工作**。授权应答线首当其冲；内部账务线会被压垮并放大为资金类不一致。

---

### Pitfall 6: Token Server 自身有 namespace 级 QPS 天花板，恰好在最需要集群限流时失效

**What goes wrong:**
`DefaultTokenService.acquireClusterToken` 在任何规则判定之前，先经过一个 namespace 级的全局漏桶 `allowProceed(id)`，**默认上限 30,000 QPS**，超限返回 `TOO_MANY_REQUEST`——而 `TOO_MANY_REQUEST` 同样走 `fallbackToLocalOrPass` 降级。也就是说：高 QPS 峰值期间，令牌服务先于业务规则饱和，然后**静默降级为本地限流**，本地阈值 = `count`（远小于集群全局阈值），表现为授权应答在峰值时段被本地限流大量误拒，且完全看不出"这是降级导致的"。

**Why it happens:**
把 Token Server 当成"必然能扛住业务峰值"的组件，没有做容量对齐。三条数据线中授权应答线的峰值 QPS 与内部账务线的高吞吐叠加后，很容易越过 30k 这个硬编码量级。

**How to avoid:**
- 容量规划时把 30,000 QPS 作为**硬约束**写入压测报告：单实例 Token Server 的实测吞吐必须留 ≥ 3× 余量，否则改为**多实例 + 一致性哈希分片**（`flowId` 决定分片）。
- SDK 暴露 `sentinel_token_server_throttled_total` 指标并对其单独告警——这是"集群限流已失效"的唯一可靠信号。
- 明确写入降级策略矩阵：`TOO_MANY_REQUEST` 降级为本地限流是**危险降级**（配额被缩小而非放大），必须与 `NO_RULE_EXISTS`（配额被放大）区分告警。
- 令牌服务实现方式（`PROJECT.md:112` 的 TBD）在评估时，把"Sentinel 自带 ClusterTokenServer 的 30k 天花板与单点问题"与"Redis + Lua 原子扣减"放在同一张表比较；从本项目证据看，**Redis 方案在水平扩展与配额正确性上更可控**，Redis token bucket 也可用 Lua 保证原子性。

**Warning signs:**
- 集群模式下拒绝量随时间呈锯齿状，且与业务真实 QPS 曲线相关；
- Token Server 端到端延迟 P99 接近或超过客户端超时阈值；
- 客户端日志出现 `TOO_MANY_REQUEST` 状态回落。

**Phase to address:** P2

**Blast radius:** 与 Pitfall 5 相反的方向——配额被**缩小**，导致授权应答在正常流量下被大量误拒，直接表现为交易成功率下跌。

---

### Pitfall 7: 单一 fail-close 默认值对三条并存数据线中至少一条是错的

**What goes wrong:**
`PROJECT.md:93` 规定限流默认 fail-close、灰度默认 fail-open，逐条规则可覆写。问题在于 SDK 服务三条要求互相矛盾的数据线（`PROJECT.md:77`）：

| 数据线 | 要求 | 限流不可用时 fail-close 的后果 |
|---|---|---|
| 刷卡授权应答 | 毫秒级、高 QPS 峰值 | 尚可接受（保护后端优先） |
| 内部服务间账务流转 | 高吞吐 | 账务流转被大面积拒绝，形成内部雪崩与资金不一致 |
| 卡组织清算报文入站 | **准确性 + 幂等 + 补偿** | 拒绝入站报文 = 丢失清算数据，且对端不会重试到本系统；比放行更严重 |

同一套默认值无法同时对三条线正确。灰度的 fail-open 同理：它对"新功能灰度"是对的，但对"已上线链路的规则变更"意味着配置损坏时把 100% 流量推向未验证代码。

**Why it happens:**
默认值在系统设计时按"最常见的保护后端"场景拍板，而没有按**数据线**维度参数化。`PROJECT.md:35` / `PROJECT.md:43` 已预留"逐条规则可覆写"，但覆写需要人工配置——而配置本身就是最先会出错的环节。

**How to avoid:**
- 把 fail 策略的默认值下沉到**规则级 + 数据线级**两层：SDK 为每条限流规则记录 `failStrategy`，由管理 API 显式设定而非隐式继承；未设置时按"规则所属资源的前缀/标签"取数据线默认值。
- 清算入站线在 SDK 内**硬编码为 fail-open**（拒绝入站等于丢数据），并在 SDK 文档与 code review checklist 中说明该例外，避免后续被"统一默认值"的清理动作改回去。
- 反向保护：fail-open 的规则必须同时受**独立的本地兜底配额**约束（不依赖远程规则的静态兜底阈值），避免"配置不可用 + 无兜底 = 无限制放行"。
- 决策记录中把实际生效的 `failStrategy` 落盘，否则事后无法解释"当时为什么放行/拒绝"。

**Warning signs:**
- 同一规则 ID 在不同数据线被复用（`ruleId` 命名不含数据线标识）；
- 拒绝量在远程规则服务异常期间激增，且集中在某条数据线；
- 决策日志中 `failStrategy` 字段为空或恒定。

**Phase to address:** P2（限流）与 P3（灰度）必须各自显式实现并在 P6 用真实数据线验证

**Blast radius:** 跨数据线。默认值的错误会同时作用于全部接入方，且清算数据丢失属于不可逆损失，恢复成本最高。

---

### Pitfall 8: Nacos 推送是"配置变了"的信号，不是载荷的可靠交付

**What goes wrong:**
把 `NacosConfigService.addListener` 的回调当作"这里就是完整且最新的配置"。实际语义是：回调只表示**某个相关 dataId 的内容发生了变化**，客户端必须**重新拉取**。由此产生的失败模式：

- **重复回调与乱序**：同一版本内容可能触发多次回调；快速连续发布（v10 → v11 → v10）时回调顺序不保证，业务若在回调内直接 `use` 携带内容，会加载到过期版本。
- **回调内拉取失败**：Nacos 不可用时重新拉取抛异常，规则状态变成"部分更新"——若未处理，SDK 会停在**半更新**状态（部分规则新、部分规则旧）。
- **配置被删除**：`dataId` 被删除是合法事件，行为与"更新为空"取决于 SDK 处理方式；若不显式处理 `null`，可能保留旧规则永不生效新配置。
- **namespace / group / dataId 错配**：namespace 指错环境会读到测试配置，group 指错会读到另一条数据线的规则——两者都不报错，只是结果不对。
- **格式错误配置**：YAML/JSON 解析失败时，若 fallback 到"保留旧配置"是静默的，运营会认为已生效。

**Why it happens:**
Nacos 的 listener 常被当作"推送订阅"而非"变更通知"，因为多数示例代码在回调里 `configs.getProperty()` 恰好可用，掩盖了"必须重拉 + 必须防抖 + 必须校验"这三件事。

**How to avoid:**
- 回调内**只做一件事：置脏标记 + 触发防抖重拉**（如 200ms 合并窗口），绝不在回调线程内做业务逻辑或重活。
- 重拉成功后**先全量校验再原子切换**：整份规则集必须整体通过 schema 校验与表达式编译校验，才替换当前生效快照；任一条失败则**整份拒绝生效**并保留上一版本（同时告警），绝不允许部分生效。
- 规则快照对象整体 `volatile` 替换，读侧无锁；写入侧串行化（单线程 + 队列），避免并发加载出中间态。
- **显式处理删除事件**：`dataId` 消失时按"该规则集已下线"处理，回退到 SDK 内置的静态安全默认（不是"保持旧值"）。
- 启动时强制校验 namespace / group / dataId 白名单，不匹配则启动失败而非静默降级——环境串线是生产事故级别。
- 客户端本地快照文件（Nacos 自身维护的 failover 目录）要确认可读；磁盘满时 Nacos 读快照失败会加剧降级。

**Warning signs:**
- 规则生效延迟与推送时间不吻合（差一个防抖窗口以上）；
- `ruleVersion` 出现回退（v11 → v10）且无对应发布记录；
- Nacos 客户端日志出现 `config data not exist` / `checksum` 不一致；
- 同一 dataId 在不同 namespace 下内容不同。

**Phase to address:** P1（规则内核的加载与快照机制先于 P2/P3 使用），P6 做环境串线演练

**Blast radius:** 规则一致性与可追溯性。版本错乱会直接破坏 `PROJECT.md:94` 的"每次决策可定位到规则与规则版本"承诺，使 100% 追溯指标失效，且事故后无法复盘真实生效配置。

---

### Pitfall 9: SCA 2021.0.5.0 与 Spring Boot 2.7.6 版本偏斜，库形态会把偏斜传染给所有接入方

**What goes wrong:**
`PROJECT.md:79` 指定 Spring Cloud Alibaba 2021.0.5.0 + Spring Boot 2.7.6。SCA 2021.0.5.0 官方对应矩阵是 Spring Cloud 2021.0.5 + **Spring Boot 2.6.13** + Spring Cloud 2021.0.5（Nacos 2.2.0 等）。用 2.7.6 属于跨小版本使用：Sentinel / Nacos starter 的自动配置以 Boot 2.6.x 为基线编写，在 2.7.x 上 `@ConditionalOnClass` 与配置属性绑定行为可能不同。

由于交付形态是**进程内库**（`PROJECT.md:90`），偏斜不是"我一个人承担"，而是**传染**：SDK 引入的 starter 依赖会把接入服务的依赖图整体带偏。Spring Boot 的 `spring-boot-dependencies` 是 BOM，接入方若直接引入 SDK 而未排除传递依赖，接入服务的 Sentinel / Nacos / Micrometer 版本可能全部被 SDK 的依赖管理覆盖。

**Why it happens:**
单体应用里"能跑就行"的版本组合，在库形态下会变成对所有下游的强制约束；而 Boot 2.7.x 只是 `spring.factories` 向 `AutoConfiguration.imports` 迁移的过渡版本，正好是自动配置行为最容易出现细微差异的版本。

**How to avoid:**
- SDK 自身**以 Boot 2.7.6 做编译与集成测试基线**，并补一个 Boot 2.6.13 的兼容性测试（因为 SCA 官方基线在那里），确认两条线都通过。
- 所有 Sentinel / Nacos 相关传递依赖标记 `provided` 或 `optional`（**SDK 不接管接入方的版本**），文档中给出推荐的 `dependencyManagement` 片段。
- 启动时对 Sentinel / Nacos 实际生效版本做**断言与日志**：与 SDK 编译基线不一致时打印 WARN，并在 SDK 健康度端点暴露实际版本。
- Auto-configuration 类必须全部标注 `@ConditionalOnClass` 与 `@ConditionalOnMissingBean`，确保接入方可以逐个覆盖；SDK 不注册强副作用 Bean。
- 在 P6 交付"接入与运维文档"时，把版本矩阵（Boot 2.6.x / 2.7.x × SCA 2021.0.5.0）与已知不兼容项写成表格。

**Warning signs:**
- 接入服务 `mvn dependency:tree` 中 Sentinel / Nacos 版本与自身 Boot 版本不匹配；
- 接入服务启动日志出现 `No qualifying bean` 或 `ClassNotFoundException: com.alibaba.nacos.*`；
- 同一 SDK 在 A 服务正常、在 B 服务启动失败。

**Phase to address:** P1（SDK 骨架与依赖边界），P6 做多版本接入验证

**Blast radius:** 全部接入服务的构建与启动。偏斜在编译期或启动期爆发，修复成本高（需要所有接入方同步改版本），且会阻塞"≥2 个业务服务完成真实接入"这一交付标准（`PROJECT.md:95`）。

---

### Pitfall 10: 用 Pub/Sub 做 L1 失效，丢一条消息就永久陈旧

**What goes wrong:**
两级缓存（`PROJECT.md:47`）的经典一致性方案是：写操作后向 Redis Pub/Sub 发布失效消息，各节点收到后清理本地 L1。但 **Redis Pub/Sub 是 at-most-once 且无持久化**：订阅者断连期间发布的所有消息直接丢失，重连后不会补发；发布方也无法知道有多少订阅者收到。因此一次网络抖动、一次节点重启、一次订阅线程被阻塞，都会让部分节点的 L1 **永久停留在旧值**，直到 TTL 到期——如果 L1 根本没有 TTL（常见的性能优化），那就是永久不一致。

在通用缓存封装里，这会表现为"偶发地读到旧数据，且无法复现、无法解释"。多维 key（`PROJECT.md:49`）会进一步放大：一个业务对象变更可能需要失效 N 个维度组合 key，漏掉任何一个就是一条不一致路径。

**Why it happens:**
把 Pub/Sub 当成"可靠消息通道"。它解决的是"通知"而非"同步"，而缓存失效本质上需要**可收敛的同步语义**。`PROJECT.md:113` 把"两级一致性协议待定"列为 TBD——这个决策的核心不是选 Redis 还是 MQ，而是**选哪种可收敛协议**。

**How to avoid:**
- 每次失效携带**单调递增版本号 / 序列号**，L1 条目记录其来源版本；收到更低版本的失效消息直接丢弃（防乱序），收到更高版本才清理。
- **L1 必须有 TTL 上限**（即使只是兜底，如 30s），并把 TTL 抖动（±10% 随机化）加进去，避免大面积同时过期（见 Pitfall 14）。
- 定期**对账/重校准**：后台任务按 key 前缀抽样比对 L1 与 L2 版本号，不一致则清理。Pub/Sub 只作为"降低陈旧窗口"的加速手段，对账才是收敛保证。
- 高一致性要求的 key 允许**绕过 L1 直读 L2**（SDK 暴露 per-key 或 per-cache 的 `l1Enabled` 开关），把选择权交给业务线。
- 若最终选择 MQ，需接受额外基础设施成本并在 ADR 中说明 `PROJECT.md:87`"不引入第二个配置中心"约束**不限制**引入 MQ（约束仅针对配置中心）——但仍要论证运维成本。
- 批量失效（`PROJECT.md:49`）必须**原子化或可重入**：一次批量操作要么全部生效、要么可安全重试，禁止"删了一半失败"。

**Warning signs:**
- 同一 key 在不同节点返回不同值，且随时间不收敛；
- L1 命中率异常高但 L2 QPS 异常低（说明失效消息大量丢失，流量没回落）；
- 重启后数据"突然变正确"。

**Phase to address:** P4 两级缓存（协议决策必须在 P4 开始前定稿）

**Blast radius:** 数据正确性，且影响面取决于业务方把什么放进了缓存。**由于 v1 是通用层（`PROJECT.md:50`），SDK 无法预判影响范围**——这使它成为最难兜底的一类风险。

---

### Pitfall 11: 「SDK 不处理 PAN」+ 通用缓存 + 通用决策日志 = 无人审计的敏感数据落地点

**What goes wrong:**
`PROJECT.md:71` 明确把"真实 PCI DSS 认证流程"列为 Out of Scope，理由是"SDK 本身不处理 PAN / 卡号"。**这个前提成立的前提是：SDK 不会成为 PAN 的经手方。但通用缓存与通用决策日志会破坏这个前提**：

- 业务服务调用 `sdkCache.get(key, loader)` 时，`loader` 的返回值会进 L2（Redis）与 L1（进程内存）。如果业务方缓存了含卡号的账户对象，SDK 就成了 PAN 的存储介质——而 Redis 是否加密、是否落盘、是否有访问审计，全都不在 SDK 的设计范围内。
- 决策日志（`PROJECT.md:55`）要求记录命中规则、剩余配额、桶号、耗时（`PROJECT.md:54`）。实现时极易"顺手"把 `EvalContext` 快照、命中的 key、路由表达式的入参一并落盘——**限流路由的入参很可能就是卡号或交易标识**。
- 进程内 L1 意味着 PAN 出现在堆内存里，可被 heap dump、带 core dump 的崩溃转储、以及任何内存读取手段获取。

这是一个**"因为声明了 Out of Scope 而变得更危险"**的典型：因为没人认为 SDK 在合规范围内，所以没人审核它的存储与日志路径。

**Why it happens:**
合规边界按"组件职责"划定，而不是按"数据流实际经过哪里"划定。Out of Scope 声明是意图，不是控制措施。

**How to avoid:**
- 在缓存 API 层面提供**显式的敏感数据护栏**：`CacheSpec` 要求调用方声明 `sensitive = true`；声明后 SDK 禁止写 L1、禁止打印 key 明文（只打哈希前缀），并在文档中说明此时 L2 的存储安全由业务方负责。
- 决策日志**只落结构化字段白名单**（`ruleId`、`ruleVersion`、`decision`、`remaining`、`bucket`、`latencyUs`、`failStrategy`），**永不落原始入参、key、表达式全文**。需要排障时通过 `EvalContext` 字段级脱敏器 + 显式 debug 开关（默认关闭）实现。
- 指标标签（Prometheus label）**禁止使用卡号、BIN、交易号等高基数且敏感的维度**——不仅会炸内存（见 Pitfall 15 相关的高基数问题），也是泄露面。限流维度用内部代理 ID。
- 把"SDK 不经手敏感认证数据"作为**需验证的技术不变量**而非声明：在 P6 交付文档中列出 SDK 的全部持久化与日志路径清单（Redis key 前缀、日志字段、指标标签），由接入方安全评审逐项确认。
- 明确 SDK **不做**的事：不提供 PAN 加密、不提供 PCI 证明——但必须提供"让接入方不犯错"的默认值。

**Warning signs:**
- 决策日志中出现 12–19 位数字串或疑似卡号；
- Redis key 模式中出现明文业务标识；
- Prometheus 指标 label 基数持续上升且 `ruleId` 之外还有动态维度；
- 业务方在 SDK 缓存里存放了完整账户对象。

**Phase to address:** P4（缓存护栏）与 P5（决策日志字段白名单）必须共同设计；P6 交付前做安全评审

**Blast radius:** 合规与数据安全。后果不可逆（数据一旦落盘/落日志即视为泄露），且因为 Out of Scope 声明的存在，事故复盘时容易误判责任边界。

---

### Pitfall 12: 灰度 hash key 不稳定或桶数变更，会把已灰度用户重排到未验证代码

**What goes wrong:**
`PROJECT.md:39` 的灰度优先级为：白名单 > 流量百分比 > hash 桶。百分比与 hash 桶的实现依赖稳定寻址。三个失败模式：

1. **缺失 targeting key 时退化为随机**：若取不到稳定标识（例如清算入站线的报文按 `process(null)` 调用、或服务未注入 `INSTANCE_ID`），实现很容易 fallback 到 `UUID` 或 `System.nanoTime()`。结果是**同一个用户每次请求落在不同桶**——用户被随机地在灰度代码与稳定代码之间来回弹，体验上比全量或全不更糟，且统计上无法收敛。
2. **桶数变更导致全体重排**：把 100 桶改成 1000 桶是常见运维操作，但 `hash % bucketCount` 的语义决定了**几乎所有用户都会换桶**。已经在灰度里的用户会被踢回稳定分支（放量"倒退"），不在灰度里的用户会被意外拉进灰度——在授权应答线上，后者意味着**未经灰度验证的代码直接上了生产**。
3. **hash 算法/盐值随版本变化**：为"避免与其他系统撞桶"而加的 salt，若在灰度规则版本升级时被改动，同上全体重排。

**Why it happens:**
"1% 放量"在实现层面常被当作一次性切分动作，而不是需要长期稳定的不变量。桶数与 salt 看起来是配置项而非契约。

**How to avoid:**
- **明确定义 targeting key 的解析顺序与失败语义**：`租户ID > 服务实例ID+会话标识 > 稳定业务键`；**全部取不到时必须 fail-open 到"明确不命中该灰度规则"并打指标，绝不随机**。随机兜底在灰度语义里是纯粹的缺陷。
- **桶数在规则版本中不可变**：修改桶数必须创建新 `ruleVersion` 并走发布/回滚流程，而不是原地改配置。SDK 在加载规则时若检测到桶数变化且未显式声明 `allowRebucket`，拒绝生效。
- hash 算法与 salt 固化在代码常量中（不随配置变化），跨规则版本保持一致；`ruleId` + `version` 之外的任何字段都不得进入 hash 输入。
- 提供**重排影响评估工具**（管理 API 端点）：给定桶数变更方案，计算"当前灰度用户中被移出比例"与"被移入比例"，把数字交给运营决策，而不是让运营以为"桶越多越细"。
- 决策记录落 `bucket` 字段（`PROJECT.md:54` 已要求），使得"重排是否影响已验证用户"可事后核对。

**Warning signs:**
- 灰度分支的请求量在无配置变更时出现剧烈抖动；
- 同一用户相邻两次决策的 `bucket` 不一致；
- 放量比例调整后，灰度侧流量不升反降。

**Phase to address:** P3 灰度评估器

**Blast radius:** 生产正确性与用户体验。桶数重排的破坏性随灰度代码的风险等级放大——若灰度代码涉及授权应答核心逻辑，等同于未经验证即全量发布。

---

### Pitfall 13: 用线程池超时来兜住表达式求值，会污染共享线程池

**What goes wrong:**
面对"表达式可能跑很久"（Pitfall 3），最省事的实现是：把求值丢进一个 ExecutorFuture，`get(1, TimeUnit.MILLISECONDS)` 超时即视为失败。问题在于这个 Executor 通常是**与业务共用或 SDK 内部共享的固定线程池**。一次慢表达式会让任务阻塞在池中线程里超过 1 秒（`get` 超时后**任务不会自动中断**，除非显式 `interrupt` 且表达式引擎响应中断），几十次并发就能把池占满，之后所有求值请求（包括正常的）排队超时。

更隐蔽的变体：为了隔离又新建一个池，却**无界**（或队列无界），于是慢表达式不被拒绝而是堆积，最终 OOM。

**Why it happens:**
"超时"被当作隔离手段，实际上超时只改变了调用方的等待时间，没有限制被调用方占用的资源。JDK 的 `Future.get(timeout)` 与线程中断是两件事。

**How to avoid:**
- **首选步数预算，不使用线程超时**（见 Pitfall 3 的方案）。解释执行 + 步数计数是唯一能确定性地终止求值的手段。
- 若确需超时兜底：使用**独立的有界线程池** + `SynchronousQueue` 或小容量队列 + 明确拒绝策略（拒绝即视为超限失败），**绝不使用无界队列**；任务内部在每步检查 `Thread.interrupted()` 以支持中断。
- 任何线程池都必须有名字与独立监控指标（活跃数、队列深度、拒绝数），并把该指标纳入 SDK 健康度（`PROJECT.md:57`）。
- 慢表达式发生次数作为**规则质量信号**上报到 Prometheus（按 `ruleId` 打标签），倒逼规则编写方收敛，而不是靠超时掩盖。

**Warning signs:**
- SDK 内部线程池活跃线程数长期等于核心线程数、队列持续有积压；
- 表达式求值超时率上升但表达式本身"看起来很简单"；
- 业务服务整体出现线程饥饿但 CPU 使用率不高（典型阻塞而非计算瓶颈）。

**Phase to address:** P1

**Blast radius:** 接入服务的线程资源。SDK 污染共享池后，影响的不限于限流/灰度功能，而是**业务服务自身的请求处理能力**——这是 SDK 作为库最危险的失效模式之一。

---

### Pitfall 14: L1/L2 TTL 相同或相近导致缓存雪崩与回源击穿

**What goes wrong:**
本地 L1 与分布式 L2 若使用**相同或接近的 TTL**，大批 key 会在同一时刻集中过期：L1 过期后同时穿透到 L2，L2 也在同一时刻过期，于是 N 个节点在同一毫秒发起 N 次相同的回源请求（缓存击穿），而回源目标通常是业务数据库或卡 BIN 批量接口。授权应答线的高 QPS 峰值会把这种瞬时放大打到数据库上。

同类问题：批量预热或批量失效后，所有 key 同步过期；以及多维 key（`PROJECT.md:49`）场景下同一业务对象的多个维度 key TTL 相同，导致"部分维度未命中"的比例升高。

**Why it happens:**
两级缓存常被实现为"同一个 TTL 配置项"，忽略了 L1 与 L2 的角色不同：L1 应当**远短于** L2（本地可重建、延迟低）以限制陈旧窗口；L2 才承担降低回源的作用。

**How to avoid:**
- **L1 TTL < L2 TTL，且留出明确倍数关系**（建议 L1 = L2 的 1/5 ~ 1/10），并在 SDK 中强制校验 `l1Ttl < l2Ttl`。
- 所有 TTL 注入**随机抖动**（如 ±10%），包括 L1 与 L2，且抖动种子包含 key 哈希，使不同 key 的过期时刻天然错开。
- **单飞（single-flight）保护**：同一 JVM 内对同一 key 的并发回源合并为一次（`ConcurrentHashMap<K, Future<V>>` 模式），完成后立即移除条目。SDK 必须内建，不能指望业务方各自实现。
- 跨节点的并发回源保护用**分布式锁 + 双重检查**（`PROJECT.md:48` 要求"防击穿"），锁必须有超时与自动释放，且获取失败时降级为"短暂等待后重试一次"而非直接穿透。
- 热点 key 探测：对命中量 Top-N 的 key 延长 L2 TTL 并在 L1 做本地常驻（soft-TTL + 后台刷新），避免热点 key 参与周期性过期。
- 预热与批量失效 API 内部对 TTL 做错峰处理。

**Warning signs:**
- 回源 QPS 曲线呈规律尖峰而非平滑；
- 单个 `key` 的 L1 命中率周期性跌到 0 再恢复；
- 数据库连接池在特定秒级出现等待队列。

**Phase to address:** P4 两级缓存

**Blast radius:** 后端依赖（数据库、卡 BIN 批量接口、内部账务服务）。SDK 引入的缓存击穿会表现为"业务服务数据库压力莫名升高"，而排查者通常不会首先怀疑是基础能力 SDK 的缓存层。

---

### Pitfall 15: 可观测性后置，历史决策无法回填规则版本 —— 100% 追溯指标不可追补

**What goes wrong:**
`PROJECT.md:15` 的成功指标之一是"限流/灰度决策 100% 可追溯到规则版本"，`PROJECT.md:111` 也已决定可观测性作为独立模块。若按"P1 规则内核 → P2 限流 → P3 灰度 → P4 缓存 → P5 可观测性"的顺序交付，那么 P2/P3 产出的决策**在 P5 之前根本没有结构化记录**。上线后发现的"某些决策异常"无法回溯当时生效的规则版本——因为数据从未被采集，且**永远无法追补**。

即使 P5 上线，历史空洞依然存在；而更隐蔽的是，规则版本字段如果没有在 P1 的规则模型里就存在，P5 只能记录"当前版本"，无法还原历史版本。

**Why it happens:**
"可观测性是独立模块"这个正确的架构决策，容易被读成"可观测性可以后做"。但可观测性对规则内核有**反向依赖**：它需要的字段（`ruleId`、`ruleVersion`、`EvalContext` 引用、评估耗时）必须在 P1 的数据模型中就已确定并随每次决策一起产生。

**How to avoid:**
- **在 P1 就定义 `DecisionRecord` 契约**（`ruleId`、`ruleVersion`、`dataLine`、`decision`、`remaining`、`bucket`、`latencyUs`、`failStrategy`、`degraded` 布尔），并在 P1/P2/P3 的每条决策路径上**立即写入**一个有界的内存环形缓冲（ring buffer，固定容量，如 10000 条/实例）。
- 环形缓冲的作用是**在 P5 的落盘通道就绪前不丢数据**；它同时是接入方灰度接入期间唯一的排障依据。P5 的工作是"把 ring buffer 的内容可靠送到独立通道"，而不是"从零开始埋点"。
- `ruleVersion` 必须是规则快照中的**不可变字段**，随规则对象一起原子替换；禁止从当前配置反查版本（反查会得到错误版本）。
- 决策记录通道**必须异步、有界、可丢弃**：ring buffer 满时按"丢弃最旧 + 计数丢弃量"处理，绝不阻塞业务线程（否则可观测性成为可用性风险）。丢弃量本身作为指标暴露——如果丢弃率不为零，说明通道容量不足，这是必须告警的事实。
- 通道选型满足：独立于业务日志（`PROJECT.md:55`）、不引入同步 IO、可批量发送（批量队列 + 定时 flush + 优雅停机 drain）。
- 指标侧（`PROJECT.md:56`）的**标签基数必须有上界**：`ruleId` 基数受规则条数约束（可控），但绝不能把 `EvalContext` 字段、商户、卡 BIN 作为标签。高基数需求走决策记录，不走 Prometheus 标签。
- 在 ROADMAP 中把"P1 已含 DecisionRecord 契约与 ring buffer"作为显式验收项写进 P1 的成功标准，而不是留给 P5 追溯。

**Warning signs:**
- P2/P3/P4 的代码里出现 `if (log.isDebugEnabled())` 才记录决策——生产环境恒不记录；
- 决策记录里 `ruleVersion` 字段缺失、为 null、或等于"当前配置版本"；
- ring buffer 丢弃计数指标不存在；
- Prometheus 出现高基数 label（`exported metric ... has cardinality of N`）。

**Phase to address:** P1（契约与 ring buffer）、P5（落盘与指标）、P2/P3/P4（各决策路径埋点）

**Blast radius:** 事故可复盘性与成功指标达成。数据一旦未采集就无法补齐，直接导致 `PROJECT.md:15` 的"100% 可追溯"无法达成，且排障时只能靠日志猜测，是生产事故定位效率的最大杀手。

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| 用 `SimpleEvaluationContext` 包一层就当"沙箱" | 少写一个表达式引擎，Spring 自带、生态熟悉 | `CVE-2026-59283` 守卫可绕过；5.3.x 已无修复线；等于对外开放业务进程 RCE | **never** |
| 缓存 key 用商户号 / 卡 BIN 等高基数实体 | 路由直观，无需表达式 | 规则表与编译缓存随实体数线性膨胀，长周期运行后 OOM 且难复现 | **never**（违反 `PROJECT.md:81` 的立项动机） |
| 灰度取不到 targeting key 时随机兜底 | 永远"有流量进灰度" | 用户每次请求在两分支间抖动；统计不收敛；灰度结论无效 | **never** |
| 直接改桶数来"调细粒度" | 立即生效、无需改代码 | 全体用户重排，等同未验证代码直接全量 | **never** |
| L1 与 L2 用同一个 TTL | 少一个配置项 | 集中过期 → 击穿 → 数据库被打穿（`PROJECT.md:48` 明确要求防击穿） | **never** |
| 决策记录用 `if (debugEnabled)` 才落盘 | 零常态开销 | 线上决策不可追溯，成功指标不可达成且不可追补 | **never** |
| 依赖 `fallbackToLocalWhenFail` / `thresholdType` 默认值 | 少写配置 | 集群配额被静默放大 N 倍并随节点数漂移 | **never** |
| 表达式求值超时用共享线程池 `Future.get(timeout)` | 实现简单、看起来有超时 | 线程池被慢表达式占满，反噬业务服务自身 | **never** |
| 决策日志记录原始入参便于排障 | 排障方便 | 卡号/交易标识落盘，破坏"SDK 不经手敏感数据"前提 | **never** |
| Sentinel / Nacos 依赖由 SDK 正常传递 | 接入方零配置 | 版本偏斜传染到所有接入方，编译期/启动期爆炸 | 仅在 SDK 是应用（非库）时 |
| 管理 API 表达式只做前端校验 | 快速失败体验好 | Nacos 直连写入绕过全部校验 | **never**（可作为体验优化，不能作为安全边界） |
| 缓存 L1 不设 TTL，只靠 Pub/Sub 失效 | 命中率最高 | 丢一条消息即永久陈旧 | 仅低一致性要求、且有独立对账任务时 |
| 用 MQ 做缓存失效以获得可靠投递 | 一致性可收敛 | 增加运维组件（需论证 `PROJECT.md:87` 约束仅限配置中心） | 大规模或强一致要求时，需 ADR |
| 令牌服务用 Sentinel 自带 ClusterTokenServer | 零额外组件 | namespace 级 30k QPS 天花板 + 单点 | 低 QPS 场景，需实测余量 ≥3× |
| 令牌服务用 Redis + Lua 原子扣减 | 可水平扩展、配额语义可控 | 增加 Redis 依赖与 Lua 维护成本 | **推荐默认**（`PROJECT.md:112` TBD 的建议结论） |
| 灰度 fail-open 统一默认值 | 新功能灰度安全 | 配置损坏时把 100% 流量推向未验证代码 | 仅"新功能"类规则；已上线链路须逐条覆写 |
| 限流 fail-close 统一默认值 | 保护后端优先 | 清算入站线拒绝即丢数据 | 仅授权应答线；清算入站线硬编码 fail-open |
| 自动配置不加 `@ConditionalOnClass` | 少写注解 | 接入方无法排除/覆盖，版本冲突无法规避 | **never** |

## Integration Gotchas

Common mistakes when connecting to external services.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Nacos 配置中心 | 在 `addListener` 回调里直接使用回调参数当完整配置 | 回调只置脏标记 + 防抖重拉；重拉后全量校验通过才原子切换快照 |
| Nacos 配置中心 | 只处理"更新"，不处理 dataId 被删除 | 显式处理删除事件，回退到内置静态安全默认，而非保留旧值 |
| Nacos 配置中心 | 不校验 namespace / group / dataId | 启动时白名单校验，不匹配直接启动失败（环境串线是生产事故级） |
| Nacos 配置中心 | 配置解析失败时静默沿用旧配置 | 整份拒绝生效 + 保留上一版本 + 告警；禁止部分生效造成半更新状态 |
| Nacos 配置中心 | 忽略客户端本地 failover 快照（磁盘满时读失败） | 监控 Nacos 快照目录可写性，磁盘水位纳入告警 |
| Sentinel ClusterTokenServer | 使用默认 `thresholdType`（AVG_LOCAL） | 显式设 `FLOW_THRESHOLD_GLOBAL`；若用均摊，必须用 `getConnectedCount` 反推真实全局阈值 |
| Sentinel ClusterTokenServer | 依赖默认 `fallbackToLocalWhenFail=true` | 显式设置，并把降级原因分类告警（配额放大 vs 配额缩小） |
| Sentinel ClusterTokenServer | 客户端 `flowId` 与 Token Server 侧规则不同源 | `flowId` 单一来源派生，配置推送后做规则一致性探针 |
| Sentinel ClusterTokenServer | 忽略 30,000 QPS namespace 级漏桶 | 压测留 ≥3× 余量，否则改多实例分片或 Redis 方案 |
| Sentinel ClusterTokenServer | 使用 `windowIntervalMs > 1000ms` 平滑突发 | 该配置下集群限流计数不准（官方 PR #3407）；窗口保持 1s，突发用控制策略处理 |
| Sentinel Dashboard | 规则用内存模式存储 | 生产必须配置持久化数据源（Nacos），否则重启丢规则 |
| Sentinel 客户端 | 超过 10 个 Resource 直接建 Resource | 用自研表达式路由层，`Resource` 数量等于注解注入点数（`PROJECT.md:81`） |
| Spring Cloud Alibaba | SCA 2021.0.5.0 与 Boot 2.7.6 直接混用 | SCA 官方基线是 Boot 2.6.13；补 2.6.13 兼容性测试，依赖标 `optional`/`provided` |
| Spring Boot 自动配置 | 传递依赖不设 `optional` | SDK 不接管接入方版本；启动时断言并暴露实际生效版本 |
| Prometheus | 把商户/卡 BIN/交易号作为指标标签 | 标签仅用 `ruleId` 等有界维度；高基数信息走决策记录通道 |
| 业务服务（数据线） | 期望 SDK 的统一默认值适配所有数据线 | fail 策略按数据线参数化；清算入站线硬编码 fail-open |

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| 编译缓存以表达式原文为 key 且无界 | 缓存 size 单调增长、Full GC 变频繁 | 规范化 key + 有界 LRU（1000 条）+ 淘汰指标 | 持续运行数小时后；尤其在规则由运营频繁新增时 |
| Sentinel Resource 按业务实体建 | Sentinel 内存暴涨、规则加载变慢 | 表达式路由层隔离基数，Resource 数 = 注入点数 | 商户/BIN 数量达到数千级时 |
| 决策记录同步落盘 / 同步 HTTP | 决策 P99 抬升、业务线程被 IO 拖住 | 有界 ring buffer + 异步批量 + 优雅停机 drain | 决策 QPS 达到数万级时 |
| Prometheus 标签含动态维度 | 指标基数爆炸、Prometheus OOM | 标签只允许 `ruleId` 等有界维度 | 决策 QPS × 实体数组合后很快触发 |
| Token Server 单实例 | 峰值时段集群限流静默降级 | 实测余量 ≥3×，否则多实例分片 | 全局 QPS 逼近 30,000 时 |
| `windowIntervalMs > 1000ms` 集群限流 | 限流不准确、超发 | 保持 1s 窗口 | 使用非默认窗口时立即出现（官方 PR #3407） |
| L1 无 TTL + 依赖 Pub/Sub 失效 | 偶发永久陈旧数据，重启后自愈 | L1 TTL 上限 + 版本号 + 定期对账 | 任何一次订阅断连后 |
| L1/L2 同 TTL | 回源 QPS 规律尖峰、DB 等待 | L1 < L2 且留倍数关系 + TTL 抖动 + single-flight | key 数量达到数万、且 TTL 在分钟级时 |
| 热 key 无本地常驻 | 单 key 回源持续打后端 | Top-N 探测 + soft-TTL + 后台刷新 | 单 key QPS 占比超过 10% 时 |
| 表达式每次现场解析 | 限流 P99 明显高于预期 | 预编译 + 缓存（`PROJECT.md:31` 硬约束） | 立即出现（违反硬约束） |
| 单飞只在单 JVM 内实现 | 集群击穿仍然发生 | 分布式锁 + 双重检查 | SDK 部署到多实例后 |
| 规则快照全量重建 | 推送后规则生效延迟随规模增长 | 差量替换 + 原子切换 | 规则条数达到千级时 |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| 用 SpEL 承接 Nacos 下发的表达式 | `CVE-2026-59283` 守卫绕过 → 业务进程 RCE；5.3.x 已无修复线（JDK 8 阻塞升级） | 全面禁用 SpEL；默认走受限自研 DSL（`PROJECT.md:114` 第三项） |
| 信任第三方引擎的"支持安全模式"声明 | QLExpress opt-in 且可被表达式改写安全开关；MVEL 无沙箱；AviatorScript `CVE-2021-41862`（CVSS 9.8） | 默认配置下跑攻击用例矩阵；SDK 侧再加 AST + 字段白名单 |
| `EvalContext` 传入业务 DTO 而非扁平 Map | 表达式可访问 DTO 上的任意方法与关联对象，绕过字段白名单 | 只传 `Map<String, Object>`，值类型限定为标量/集合 |
| 未处理配置删除事件 | 被删除的 dataId 保留旧规则永不更新 | 显式处理删除，回退内置静态安全默认 |
| 决策日志记录原始入参 / key / 表达式全文 | 卡号与交易标识落盘，破坏 `PROJECT.md:71` 的合规前提 | 字段白名单 + 字段级脱敏 + debug 开关默认关闭 |
| Prometheus 标签使用卡 BIN / 商户 / 交易号 | 敏感数据外泄 + 指标基数爆炸 | 标签仅 `ruleId` 等有界维度；敏感维度用内部代理 ID |
| 通用缓存无敏感数据护栏 | 业务方把含卡号对象存入 SDK 缓存，SDK 成为未审计的 PAN 存储 | `CacheSpec.sensitive` 显式声明后禁写 L1、key 仅打哈希前缀 |
| Nacos namespace / group / dataId 不校验 | 测试配置串入生产、读到别的数据线规则 | 启动白名单强校验，不匹配即启动失败 |
| 管理 API 无鉴权 / 越权即可改规则 | 任意调用方可改写影响全部接入方的限流与灰度 | API 侧占位租户/权限模型（`PROJECT.md:62`），并在 P6 明确"v1 仅预留位"的**风险提示** |
| 管理 API 变更无审计留痕 | 无法追溯"谁在什么时候把限流从 1000 改成 10 万" | 变更审计不可关闭（`PROJECT.md:61`） |
| 表达式校验只做在管理 API 层 | Nacos 直连写入绕过全部校验 | API 校验仅作体验优化；安全边界在 SDK 求值侧 |
| 库形态下自动配置无 `@ConditionalOnClass` / `@ConditionalOnMissingBean` | 接入方无法排除，版本冲突无法规避 | 全部条件化；强副作用 Bean 不注册 |
| 决策记录通道满时阻塞业务线程 | 可观测性反噬成可用性风险 | 有界 ring buffer，满则丢最旧并计数告警，绝不阻塞 |
| 认为"Out of Scope 声明 = 没有该风险" | 合规边界按职责而非数据流划定，SDK 成为盲区 | 把敏感数据路径清单（Redis key、日志字段、指标标签）作为交付物逐项评审 |

## UX Pitfalls

Common user experience mistakes in this domain.

> 本项目无控制台前端（`PROJECT.md:67`），此处 UX 指**接入方开发者（业务服务团队）与值班运维**的使用体验。

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| 限流被拒时只返回统一错误码 | 业务方无法判断是自己被限流、表达式路由没匹配、还是规则服务异常，排查成本极高 | 决策结果中区分 `LIMITED_BY_RULE` / `NO_RULE_MATCHED` / `DEGRADED_LOCAL` / `FAIL_CLOSE`；错误信息带 `ruleId` |
| 集群降级对调用方完全静默 | 业务方在峰值时段收到大量本地限流误拒，却不知原因是 Token Server 饱和 | 对外暴露降级状态（响应头或 SDK 回调），并在文档中写明降级语义 |
| 灰度命中不返回桶号与命中原因 | 业务方无法自查"为什么我进了灰度"，只能反查规则 | 决策结果返回 `bucket` + `matchedDimension`（`PROJECT.md:41` 已要求） |
| 规则配错时报错不指向具体规则 | 运营面对"表达式编译失败"却不知是哪条、哪一段 | 错误信息带 `dataId`、规则 ID、字段路径、失败原因 |
| 配置生效延迟不透明 | 运营改完规则不确定何时生效，反复提交 | 规则版本暴露 `appliedAt` 与 `effectiveVersion`；提供"当前生效版本"查询端点 |
| 压测报告只给平均延迟 | 无法判断是否满足 P99 预算（`PROJECT.md:96`） | 出具 P50/P95/P99/P999 与各组件耗时拆分 |
| 真实生效配额不可见 | 运营配"1000 QPS"但实际是 5000（AVG_LOCAL 放大） | 管理 API 返回"生效全局阈值 × 参与节点数 = 实际配额" |
| 接入方需自行拼装 Sentinel / Nacos 版本 | 接入摩擦高、易踩版本偏斜 | 提供 BOM 或推荐的 `dependencyManagement` 片段 |
| 缓存陈旧无任何信号 | 业务方读到旧数据却不知数据已过期 | 缓存条目带版本与新鲜度；可开启陈旧度指标 |
| 回滚操作需要重启 | 灰度出问题时止损慢 | Nacos 推送即时生效 + 一键回滚到上一版本（`PROJECT.md:42`） |
| 无 per-service 熔断开关 | 单个接入方配置错误无法快速隔离 | SDK 级 kill switch（本地生效，不依赖 Nacos） |
| 决策记录查不到 | 排障只能靠业务日志猜 | 提供按 `ruleId` / 时间 / traceId 查询决策记录的能力 |

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **表达式沙箱:** 常见缺失"默认配置下就安全"——verify 在 JDK 8 上跑完攻击用例矩阵，SpEL 零引用
- [ ] **表达式沙箱:** 常见缺失步数/长度/嵌套上限——verify 存在并能被测试触发 `EvaluationBudgetExceeded`
- [ ] **表达式沙箱:** 常见缺失第三方引擎 PoC 记录——verify 选型文档含可复现用例与结论
- [ ] **表达式路由:** 常见缺失基数不变式——verify 路由表大小不随运行时长/实体数增长
- [ ] **编译缓存:** 常见缺失有界化——verify LRU 上限 + 淘汰率指标 + 高基数压测
- [ ] **限流降级:** 常见缺失降级可观测——verify `sentinel_cluster_degrade_total{reason}` 非零时告警
- [ ] **限流配额:** 常见缺失真实阈值可见——verify 管理 API 返回生效全局阈值与节点数
- [ ] **限流配额:** 常见缺失依赖 Sentinel 默认值——verify `thresholdType` 与 `fallbackToLocalWhenFail` 显式设置
- [ ] **令牌服务:** 常见缺失容量报告——verify 压测含 30k QPS 天花板与 ≥3× 余量说明
- [ ] **令牌服务:** 常见缺失 `flowId` 一致性探针——verify 启动与推送后能检出规则不同步
- [ ] **规则加载:** 常见缺失删除事件处理——verify 删 dataId 后行为是回退默认而非保留旧值
- [ ] **规则加载:** 常见缺失部分生效防护——verify 单条规则非法时整份拒绝生效
- [ ] **规则加载:** 常见缺失环境校验——verify namespace/group/dataId 不匹配时启动失败
- [ ] **失败策略:** 常见缺失数据线差异化——verify 清算入站线 fail 策略硬编码为 fail-open
- [ ] **失败策略:** 常见缺失 fail-open 的兜底配额——verify 配置不可用时仍有静态本地阈值
- [ ] **灰度:** 常见缺失 targeting key 失败语义——verify 全部取不到时明确不命中而非随机
- [ ] **灰度:** 常见缺失桶数不可变约束——verify 改桶数被拒绝或需显式 `allowRebucket`
- [ ] **灰度:** 常见缺失重排影响评估——verify 管理 API 能算出移入/移出比例
- [ ] **缓存:** 常见缺失 L1 TTL 上限——verify TTL 存在且 `l1Ttl < l2Ttl` 被强制校验
- [ ] **缓存:** 常见缺失失效消息版本号——verify L1 记录来源版本，乱序消息被丢弃
- [ ] **缓存:** 常见缺失对账任务——verify 存在抽样比对 L1/L2 的后台任务
- [ ] **缓存:** 常见缺失 single-flight——verify 单 JVM 同 key 并发回源合并为一次
- [ ] **缓存:** 常见缺失分布式防击穿——verify 多实例下并发回源被锁合并
- [ ] **缓存:** 常见缺失 TTL 抖动——verify 同批 key 过期时刻分散
- [ ] **缓存:** 常见缺失敏感数据护栏——verify `sensitive=true` 时禁写 L1、key 仅哈希
- [ ] **可观测性:** 常见缺失 P1 的 `DecisionRecord` 契约——verify 规则内核中已含 `ruleVersion` 不可变字段
- [ ] **可观测性:** 常见缺失 ring buffer——verify 有界缓冲存在且丢弃量有指标
- [ ] **可观测性:** 常见缺失异步落盘——verify 决策路径上无同步 IO
- [ ] **可观测性:** 常见缺失高基数标签防护——verify 标签仅 `ruleId`，无实体维度
- [ ] **可观测性:** 常见缺失降级状态指标——verify 规则同步状态/令牌服务连通性/降级状态均可监控
- [ ] **SDK 依赖:** 常见缺失版本断言——verify 启动时校验 Sentinel/Nacos 实际版本并暴露
- [ ] **SDK 自动配置:** 常见缺失条件化——verify 全部 `@ConditionalOnClass` / `@ConditionalOnMissingBean`
- [ ] **SDK 依赖:** 常见缺失 `optional`——verify 接入方 `dependency:tree` 不被 SDK 强制改写
- [ ] **管理 API:** 常见缺失鉴权——verify 未鉴权请求被拒绝（v1 至少内网隔离 + 令牌）
- [ ] **管理 API:** 常见缺失变更审计——verify 每次规则变更留痕且不可关闭
- [ ] **交付:** 常见缺失敏感数据路径清单——verify 列出 Redis key / 日志字段 / 指标标签供安全评审
- [ ] **交付:** 常见缺失 P99 压测报告——verify 分组件耗时拆分，覆盖最坏表达式
- [ ] **交付:** 常见缺失版本兼容矩阵——verify Boot 2.6.x / 2.7.x 均通过集成测试

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| P1 SpEL RCE（配置被写入恶意表达式） | HIGH | ① 立即用 SDK kill switch 关闭表达式求值（本地生效，不依赖 Nacos）；② 吊销 Nacos 写权限与该 namespace 的访问凭据；③ 从配置中心历史与版本快照中定位恶意 dataId 与写入账号；④ 全量业务服务进程重启（内存中可能已驻留被污染的编译缓存）；⑤ 评估是否需要重装 JDK（若曾执行过外部代码）；⑥ 全量数据面凭证轮换 |
| P2 引擎安全开关被放宽 | HIGH | ① 关闭该 SDK 实例的表达式模块；② 定位放宽代码所在服务与提交人；③ 撤销该配置的引入或回退到受限 DSL；④ 复核该期间所有规则表达式的 AST 审计日志 |
| P3 表达式 DoS 打满线程池 | LOW | ① 从 Nacos 回滚到上一规则版本（若坏规则来自发布）；② 若为存量坏规则，用管理 API 下线该 `ruleId`；③ 无需重启——规则快照原子替换即时生效 |
| P4 编译缓存 / 规则表膨胀 | MEDIUM | ① 提升堆内存并触发一次 Full GC 观察回收；② 定位基数爆炸的 key 模式（表达式原文或入参）；③ 改为规范化 key + 有界 LRU；④ 若已 OOM 需滚动重启并先修 key 生成逻辑 |
| P5 集群配额被静默放大 N 倍 | MEDIUM | ① 核对 `getConnectedCount` 与 `thresholdType`，把配额临时改为显式 `FLOW_THRESHOLD_GLOBAL`；② 清理 Token Server 与客户端的 `flowId` 不一致；③ 复核放量期间是否有后端被打穿的迹象；④ 恢复后必须回补告警，避免静默期再次复发 |
| P6 Token Server 饱和降级 | LOW | ① 扩容 Token Server 实例或多实例分片；② 或临时把相关规则切为本地限流并按 `count × 节点数` 重新计算阈值；③ 复核峰值时段误拒对授权成功率的影响 |
| P7 失败策略默认值选错 | MEDIUM | ① 对受影响数据线的规则批量改为正确的 `failStrategy`（清算入站线强制 fail-open）；② 复核 fail-close 期间被拒的清算报文，走对端补报或本地补偿队列；③ 资金类不一致需与账务方对账 |
| P8 规则版本错乱 / 半更新 | MEDIUM | ① 以 Nacos 中已知良好版本强制覆盖（SDK 需提供"指定版本生效"能力）；② 清理客户端 failover 快照后重启；③ 按决策记录中的 `ruleVersion` 核对受影响时间窗；④ 补上删除事件与全量校验逻辑 |
| P9 版本偏斜导致接入方启动失败 | MEDIUM | ① 提供含正确 `dependencyManagement` 的接入文档片段；② 或 SDK 侧把 Sentinel/Nacos 标为 `optional` 交还版本控制权；③ 逐个接入方验证启动；④ 长期需补 Boot 2.6.13 兼容测试 |
| P10 L1 永久陈旧 | MEDIUM | ① 触发一次全量对账强制清理 L1（需管理 API 提供 `flushNamespace`）；② 紧急时提升 L1 TTL 兜底窗口；③ 检查订阅连接稳定性；④ 对高一致性 key 临时关闭 L1 |
| P11 PAN 落入 SDK 缓存 / 日志 | HIGH | ① 立即停止相关缓存写入并清除 Redis 中对应 key（需评估 L2 备份与副本）；② 清除或归档含敏感字段的决策日志；③ 通知安全与合规方按内部流程定级；④ 排查 heap dump / core dump 是否留存；⑤ 长期加入 `sensitive` 护栏与字段白名单 |
| P12 灰度用户被重排 | MEDIUM | ① 回滚到重排前的规则版本（若已发布新桶数）；② 对被移入灰度的用户强制走稳定分支（临时白名单反查）；③ 对已被移出的灰度用户记录"曾验证过"状态，避免重复验证浪费 |
| P13 共享线程池被污染 | MEDIUM | ① 立即将 SDK 表达式模块切换到独立有界线程池或关闭；② 业务服务滚动重启以释放被占满的池；③ 短期可先用 kill switch 关闭表达式求值功能 |
| P14 缓存击穿 / 雪崩 | LOW | ① 提高后端临时容量或限流保护；② 触发热点 key 本地常驻；③ 收紧回源并发上限；④ 复盘 TTL 配置关系与抖动参数 |
| P15 决策记录缺失 | HIGH（不可追补） | ① 确认 ring buffer 丢失的数据量与时间窗；② 用 Nacos 规则版本历史 + 业务日志做**间接**还原，并明确标注为推断而非审计证据；③ 补齐埋点后接受历史空洞，在审计说明中写明覆盖起始时间；④ 根本修复是禁止 P5 之前上线决策路径 |

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| P1 SpEL 假沙箱（5.3.x 无修复线） | P1 规则内核与表达式沙箱 | 依赖树零 `spring-expression` 直接使用；ADR 已归档禁用 SpEL 的决定 |
| P2 第三方引擎 opt-in 沙箱可被关闭 | P1 规则内核与表达式沙箱 | 攻击用例矩阵文档齐全；默认配置下全部用例失败方进入候选 |
| P3 表达式 DoS / ReDoS 打满线程池 | P1 规则内核与表达式沙箱 | 步数/长度/嵌套上限存在且可被测试触发；压测含最坏表达式用例 |
| P4 自研路由层基数爆炸 | P1 规则内核与表达式沙箱 | 路由表大小不随运行时长/实体数增长；10 万 BIN 高基数压测通过 |
| P5 集群配额静默放大 N 倍 | P2 限流执行与故障策略 | `thresholdType`/`fallbackToLocalWhenFail` 显式设置；降级指标按 reason 分类并告警；`flowId` 一致性探针通过 |
| P6 Token Server 30k QPS 天花板 | P2 限流执行与故障策略 | 压测报告含 Token Server 容量与 ≥3× 余量；Redis+Lua 或多实例分片方案落地 |
| P7 单一 fail 策略对三条线不适用 | P2 限流执行与故障策略 / P3 灰度评估器 | 清算入站线 fail-open 硬编码；三数据线各自的失败策略用例全部通过 |
| P8 Nacos 推送语义与半更新 | P1 规则内核（加载与快照） | 删除事件回退默认；单条非法规则导致整份拒绝生效；namespace 错配启动失败 |
| P9 SCA/Boot 版本偏斜传染 | P1 SDK 骨架与依赖边界 / P6 接入验证 | Boot 2.6.13 与 2.7.6 双线集成测试通过；依赖为 `optional`；启动断言生效版本 |
| P10 Pub/Sub at-most-once 致 L1 永久陈旧 | P4 两级缓存 | 失效带单调版本号；乱序消息被丢弃；L1 有 TTL 上限；对账任务存在 |
| P11 通用缓存/日志成为 PAN 落地点 | P4 缓存护栏 + P5 日志字段白名单 / P6 安全评审 | `sensitive=true` 禁写 L1；决策日志字段白名单生效；敏感数据路径清单已交付评审 |
| P12 灰度 hash key 不稳定 / 桶数重排 | P3 灰度评估器 | targeting key 缺失时明确不命中（无随机）；桶数变更被拒或需显式 `allowRebucket`；重排影响评估端点可用 |
| P13 共享线程池被慢表达式污染 | P1 规则内核与表达式沙箱 | 求值使用独立有界池或步数预算；池指标纳入 SDK 健康度；无无界队列 |
| P14 L1/L2 同 TTL 击穿 | P4 两级缓存 | `l1Ttl < l2Ttl` 被强制校验；TTL 抖动生效；single-flight + 分布式防击穿均通过 |
| P15 可观测性后置致历史不可追补 | P1 定义 `DecisionRecord` 契约与 ring buffer / P5 落盘与指标 | P1 成功标准显式包含契约与 ring buffer；决策路径无同步 IO；标签基数有界；P2-P4 埋点在各自阶段验收 |

## Research Flags for Phases

- **P1 规则内核与表达式沙箱** — 需要更深入研究：表达式引擎选型决策（`PROJECT.md:114`）需在 JDK 8 上做**可复现的**攻击用例矩阵与性能基准，否则 P1 无法给出可辩护的选型结论。这是整个里程碑最需要 research 的阶段。
- **P2 限流执行与故障策略** — 需要更深入研究：令牌服务实现方式（`PROJECT.md:112`）需在 Redis+Lua 与 Sentinel ClusterTokenServer 之间做量化对比（吞吐、配额正确性、水平扩展、运维成本），并实测 30k QPS 天花板。
- **P3 灰度评估器** — 需在本阶段确定 targeting key 解析顺序与三数据线的差异；桶数不可变约束与重排评估算法需在编码前定稿。
- **P4 两级缓存** — 需在编码前定稿两级一致性协议（`PROJECT.md:113`），并明确 v1 通用层对敏感数据的护栏设计。
- **P5 决策可观测性** — 标准模式，通道选型（ring buffer → 批量 → 独立通道）不需要额外 research；风险在 P1 已埋点。
- **P6 管理 API 与接入验证** — 需在交付前做版本兼容矩阵与安全路径清单评审；`PROJECT.md:62` 的租户/权限占位需明确 v1 风险提示。

## Sources

- Spring Boot 2.7.6 release notes（Spring Framework 5.3.24 升级）— https://github.com/spring-projects/spring-boot/releases/tag/v2.7.6 (HIGH)
- Spring Boot 2.7.6 依赖版本表 — https://docs.spring.io/spring-boot/docs/2.7.6/reference/html/dependency-versions.html (HIGH)
- CVE-2026-59283 SpEL 沙箱绕过 — https://spring.io/security/cve-2026-59283 (MEDIUM)
- Groovy AST 注入分析（SecureASTCustomizer 可绕过）— https://codeql.github.com/codeql-query-help/java/java-groovy-injection/ (HIGH)
- ReDoS 查询与缓解说明 — https://codeql.github.com/codeql-query-help/java/java-redos/ (HIGH)
- Sentinel 集群流控官方文档 — https://sentinelguard.io/en-us/docs/cluster-flow-control.html (HIGH)
- Sentinel `FlowRuleManager` / `loadRules` 前版本替换语义（源码）— https://github.com/alibaba/Sentinel/blob/master/sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/block/flow/FlowRuleManager.java (HIGH)
- Sentinel 集群规则数据源与 `NO_RULE_EXISTS` 语义 — https://github.com/alibaba/Sentinel/issues/1670 (HIGH)
- Sentinel 集群限流静默降级为单机限流实例（`no rules in token server`）— https://github.com/alibaba/Sentinel/issues/2457 (HIGH)
- Sentinel 集群流控设计与已知问题（30k QPS namespace 漏桶、均摊阈值）— https://github.com/alibaba/Sentinel/issues/1639 (HIGH)
- Sentinel `windowIntervalMs > 1000ms` 集群限流不准（PR #3407）— https://github.com/alibaba/Sentinel/pull/3407 (HIGH)
- Spring Cloud Alibaba 版本与 Spring Boot 对应矩阵 — https://sca.aliyun.com/en/docs/2021/overview/version-explain/ (HIGH)
- SCA 2021.0.5.0 release notes — https://github.com/alibaba/spring-cloud-alibaba/releases/tag/2021.0.5.0 (HIGH)
- Nacos 配置发布/订阅官方文档（回调仅表示变化、需重新拉取）— https://nacos.io/en/docs/latest/manual/user/config/publish-query-listen/ (HIGH)
- Spring Boot 自动配置与 `spring.factories` 迁移 — https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html (HIGH)
- 两级缓存 pub/sub 一致性实现参考 — https://github.com/nwwarm/spring-redis-hybrid-cache (MEDIUM)
- Redis 令牌桶 / Lua 原子限流实现 — https://redis.io/docs/latest/develop/use-cases/rate-limiter/nodejs/ (HIGH)
- 稳定哈希与百分比放量分桶语义（Cloudflare Flagship）— https://developers.cloudflare.com/flagship/targeting/percentage-rollouts/ (HIGH)
- PCI DSS 快速参考指南（敏感认证数据授权后不可留存、日志与掩码要求）— https://listings.pcisecuritystandards.org/documents/PCI%5FDSS-QRG-v3%5F2%5F1.pdf (HIGH)
- 表达式引擎性能与能力对比基准 — https://github.com/howiefh/expression-engine-benchmark (MEDIUM)

---
*Pitfalls research for: 信用卡实时数据系统 · 基础能力平台（进程内 Java SDK）*
*Researched: 2026-09-29*
*Overall confidence: MEDIUM — Sentinel / Nacos / Spring Boot 结论 HIGH；表达式引擎与 CVE 结论 MEDIUM，落地前需逐条复核*
