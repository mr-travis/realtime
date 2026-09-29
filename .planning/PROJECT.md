# 信用卡实时数据系统 · 基础能力平台

## What This Is

信用卡实时数据系统的第一个子应用，为后续所有业务子应用提供限流、灰度、缓存三类基础能力，以及统一的决策可观测性和规则管理 API。以 Java SDK（进程内库）形态交付，规则与配置由系统已有的 Nacos 统一下发。系统内并存多条实时数据线（刷卡授权应答、内部服务间账务流转、卡组织清算报文入站），本子应用对它们是通用的，不绑定具体业务场景。

## Core Value

业务服务只需引入依赖、加一个注解，即可获得生产可用的限流 / 灰度 / 缓存能力，且**每一次决策事后都能追溯到具体规则和规则版本**。

## Business Context

- **Customer**: 信用卡系统内的业务服务开发团队（内部消费者）
- **Revenue model**: 不直接创收；作为生产系统的基础能力，通过降低故障率与接入成本体现价值
- **Success metric**: SDK 自身引入的 P99 延迟开销在预算内；≥2 个业务服务完成真实接入；限流/灰度决策 100% 可追溯到规则版本
- **Strategy notes**: 属于更大项目「信用卡实时数据系统」的第一个子应用。控制台界面已明确拆分为独立的后续子应用

## Requirements

### Validated

(None yet — ship to validate)

### Active

**限流**

- [ ] 业务服务可在接口或方法上通过注解声明限流注入点
- [ ] 限流规则以接口 / 方法为粒度，通过可动态配置的表达式匹配方法入参，路由到对应限流规则
- [ ] 表达式求值必须沙箱化，禁止任意方法调用
- [ ] 表达式必须预编译并缓存，限流热路径上禁止现场解析
- [ ] 支持单节点限流模式（本地计数，不依赖任何网络调用）
- [ ] 支持集群限流模式（中心化令牌服务）
- [ ] 集群限流不可用时自动降级为单节点限流
- [ ] 限流决策默认 fail-close（保护后端不被打穿），且逐条规则可覆写

**灰度**

- [ ] 灰度评估器支持多维度组合判定，优先级：白名单 > 流量百分比 > hash 桶
- [ ] 灰度规则经 Nacos 推送后在本地生效，无需重启
- [ ] 灰度决策默认可解释：命中哪条规则、落在哪个桶
- [ ] 灰度规则支持一键回滚（依赖规则版本机制）
- [ ] 灰度决策默认 fail-open（配置不可用时退回全量，不把未验证的变更推上去），且逐条规则可覆写

**缓存**

- [ ] 提供本地二级缓存 + 分布式缓存的统一封装
- [ ] 提供防穿透、防击穿、防雪崩保护
- [ ] 支持多维 key 生成与批量失效
- [ ] v1 只做通用层，不预置业务语义缓存

**可观测性**

- [ ] 每次决策（放行 / 拒绝 / 灰度命中）可事后追溯：命中规则 ID、规则版本、当时剩余配额、灰度桶号、评估耗时
- [ ] 决策日志走独立通道，不混入业务日志
- [ ] 暴露 Prometheus 指标：QPS、拒绝率、命中率、规则版本
- [ ] SDK 自身健康度可监控（规则同步状态、降级状态、令牌服务连通性）

**管理 API**

- [ ] 提供规则增删改查、灰度发布、变更审计的 REST API
- [ ] API 契约预留租户 / 权限模型位，供后续独立的控制台界面子应用使用
- [ ] 规则版本管理（回滚依赖它）

### Out of Scope

- **控制台前端界面** — 拆为独立子应用；本子应用只交付管理 API，契约在本阶段冻结
- **业务语义缓存**（额度 / 账单 / 卡产品预置缓存）— 业务线仍在梳理，写死业务对象会返工；v2 再做
- **非 Java SDK**（Go / Node / Python）— v1 仅 Java（JDK 8）
- **熔断、隔离舱、舱壁降级** — Sentinel 自带熔断能力，但本次范围只含限流、灰度、缓存
- **真实 PCI DSS 认证流程** — SDK 本身不处理 PAN / 卡号，不在合规范围内
- **多租户隔离的完整实现** — v1 只在 API 层预留模型位，隔离逻辑随控制台子应用落地

## Context

- **目标系统**：信用卡实时数据系统，包含多个子应用；本子是第一个，后续还会有控制台界面等
- **业务数据线**：刷卡授权应答（毫秒级、QPS 峰值高）、内部服务间账务流转（高吞吐）、卡组织清算报文入站（准确性 + 幂等 + 补偿）。多条并存，具体场景仍在梳理中 —— 这是缓存只做通用层、不预置业务对象的直接原因
- **现有基础设施**：Nacos 已部署并作为配置中心 / 注册中心，本子应用必须复用，不引入第二个配置中心
- **技术栈（JDK 8 为硬约束）**：JDK 8、Spring Boot 2.7.6、Spring Cloud 2021.0.5.0、Spring Cloud Alibaba 2021.0.5.0、Maven
- **组件选型现状**：限流采用 Spring Cloud Alibaba 内置的 Sentinel（Sentinel 负责滑动窗口计数）；灰度评估器完全自研（Nacos 与 Sentinel 均无可用灰度能力，Nacos 配置灰度仅有 beta tag 粒度）
- **自研路由层**：Sentinel 的热点参数限流只支持 `paramIdx` + 单值阈值，不支持表达式；且其 Resource 是静态粒度，直接按商户 / 卡 BIN / 机构建 Resource 会导致数量随实体数线性增长、内存爆炸。因此在 Sentinel 之外自研一层「注解注入点 + 表达式路由」，Sentinel 只承担最后的计数执行
- **团队现状**：Sentinel 目前在项目中的使用方式与本设计不冲突，业务维度限流是新增的自研层

## Constraints

- **技术栈**: JDK 8 + Spring Boot 2.7.6 + Spring Cloud 2021.0.5.0 + Spring Cloud Alibaba 2021.0.5.0 + Maven — 用户指定；所有依赖必须选 JDK 8 兼容版本，不可直接采用最新版
- **配置中心**: 必须复用已有 Nacos，不引入第二个配置中心
- **表达式求值安全**: 规则来自配置下发，表达式求值必须沙箱化，禁止任意方法调用（否则等同于开放远程代码执行）
- **表达式求值性能**: 限流在热路径上，表达式必须预编译并缓存，禁止每次现场解析
- **SDK 形态**: 进程内库，不做成独立服务；单节点模式不得依赖任何网络调用
- **控制台解耦**: 管理 API 契约在本子应用交付时冻结，控制台界面子应用按此契约开发
- **配额精度**: 必须同时支持单节点与集群两种限流模式。集群配额由 SDK 自有的共享计数器提供（Redis + Lua 原子判定），客户端超时必须有界；集群不可用时降级为单节点，但**降级状态必须显式、可查询、按原因分类告警**，不得依赖任何第三方库的默认行为
- **故障策略**: 限流默认 fail-close，灰度默认 fail-open，逐条规则可覆写（默认值需按业务线参数化，见 research/SUMMARY.md R6）
- **决策可追溯**: 每次限流 / 灰度决策必须能定位到具体规则与规则版本
- **交付标准**: SDK + 集群令牌层（Redis + SDK 自有版本化 Lua 脚本，v1 不新增独立部署件）+ 示例服务 + 接入与运维文档，且至少 2 个业务服务完成真实接入
- **性能预算**: SDK 自身引入的延迟开销需量化并出具压测报告。**压测同时是架构决策闸门**：须给出集群令牌层在真实拓扑下满足峰值并留出 ≥3× 余量的实测证据，据此决定是否从「Redis 为判定点」改为「本地快路径 + Redis 粗粒度对账」

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 限流采用 Sentinel 而非全自研 | 复用成熟的滑动窗口计数实现，把自研面收敛到业务规则表达和可观测性 | — Pending |
| 灰度评估器完全自研 | Sentinel 与 Nacos 均无可用灰度能力，Nacos 配置灰度仅 beta tag 粒度 | — Pending |
| 限流注入点为接口 / 方法注解，参数经表达式路由到规则 | 避免 Sentinel Resource 数量随业务实体数线性增长导致内存爆炸 | — Pending |
| 表达式求值必须沙箱化 + 预编译缓存 | 规则来自配置下发，无沙箱等同于开放代码执行；热路径不可现场解析 | — Pending |
| 管理 API 契约在本阶段冻结 | 控制台界面拆为独立子应用，契约需提前对齐，是一次性决策 | — Pending |
| 缓存 v1 只做通用层，不预置业务语义缓存 | 业务线仍在梳理，写死业务对象会返工 | — Pending |
| 控制台界面拆为独立子应用 | 界面工作量与 SDK 无共享技术路径，独立交付更快 | — Pending |
| 可观测性作为独立模块而非各模块内建能力 | 决策日志、指标、追踪需统一收口，避免各模块重复实现且口径不一致 | — Pending |
| 集群令牌层：Redis + Lua 原子判定，Sentinel 仅本地模式 | Sentinel 集群模式在任意默认值下都不提供全局限流：`thresholdType` 默认 `FLOW_THRESHOLD_AVG_LOCAL` 使全局阈值 = `count × 连接数`，`fallbackToLocalWhenFail` 默认 `true` 使规则不匹配时静默降级为本地计数；令牌服务器另有命名空间级 30k QPS 硬上限。先按「Redis 为判定点」实施，压测后再评估是否降级为本地快路径 + Redis 对账 | — Decided 2026-09-29 (research R2/R5) |
| 缓存两级一致性：单调序列号 + 周期对账 | Redis Pub/Sub 是 at-most-once，断连/重启/GC 停顿会**永久**漏掉失效并服务过期值。选收敛式：每次失效带单调序列号（乱序丢弃）+ L1 TTL 作为不一致硬上界 + TTL 抖动 + 周期对账任务，把丢失代价从"永久错误"降为"一个对账周期"。同时强制 l1Ttl < l2Ttl、per-key（而非全局）single-flight、分布式锁+双检、热键 soft-TTL 后台刷新、每缓存 l1Enabled 开关 | — Decided 2026-09-29 (research R2 路线 P4) |
| 灰度 targeting-key 全局统一优先级 | 方法入参 → 路由头 → 主体标识，单一顺序适用三条业务线；发布时强制声明 targeting key，缺失即拒绝发布。发布前须固定，不得隐式继承 | — Decided 2026-09-29 |
| 离线决策消费者位于示例服务内 | 作为 C13 的一部分交付，不新增部署件，使 v1 能闭环验证"决策可追溯"这一核心价值 | — Decided 2026-09-29 |
| 两个目标接入服务均启用集群限流模式 | 接入时同时跑单节点与集群两种模式，使 Redis 延迟分布与 ≥3× 余量证据能在真实流量下取得，压测闸门得以真正闭合 | — Decided 2026-09-29 |
| 表达式引擎：Aviator 5.4.4 沙箱模式，SpEL 禁用 | Boot 2.7.6 锁定 Spring 5.3.24，5.3 分支已过 OSS 支持终止且 2026 SpEL 公告在该分支无修复版本。Aviator 5.4.4 含 5.4.3→5.4.4 沙箱逃逸修复，CVE-2021-41862 影响范围 ≤5.2.7 不覆盖该版本。**代价**：沙箱是运行时开关而非语法级保证，因此以下三条为 P1 硬性验收门，非可选项——(a) JDK 8 攻击用例矩阵在**默认配置**下全部通过（反射逃逸 / 构造器逃逸 / 静态方法 / ClassLoader-Thread-Runtime 链 / 文件与网络 IO / 死循环与深递归 / 超大字符串与数组 / ReDoS / 深嵌套）；(b) JMH 实测满足第 4 层预算；(c) `enableSandboxMode()` 必须在启动时断言其未被关闭。`ExpressionEngine` SPI 保留，可无下游改动切回自研 DSL | — Decided 2026-09-29 (research R1) |
| 故障默认值按业务线参数化，清算入站硬编码 fail-open | 统一 fail-close 对卡组织清算报文入站是错的：拒收等于丢清算数据，且对手方不会向本系统重投。每条 fail-open 规则必须配独立静态本地配额，避免"配置不可用 + 无回退 = 无限通行"。清算入站的例外需写入 code-review 检查项，防止后续"统一默认值"重构改回去 | — Decided 2026-09-29 (research R6) |
| 缓存敏感数据默认拒绝 | 通用缓存与决策日志是两条洗卡路径。未显式声明 `sensitive=false` 时，本地层默认不写入、key 只存哈希前缀。调用方忘记声明即为残余漏洞，故拒绝优先 | — Decided 2026-09-29 (research R4) |
| 性能预算 p99 SDK 开销 < 10µs（不含集群 RTT） | 集群 RTT 单独列为一条预算线。两者混算就是日后被"argue away"的起点。需签字否则 P2 压测无法判定通过/失败 | — Decided 2026-09-29 (research R6 修正 6) |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Business Context check — consumer、success metric 是否仍准确？
4. Audit Out of Scope — reasons still valid?
5. Update Context with current state (业务线梳理进展、已接入服务清单)

---
*Last updated: 2026-09-29 after initialization*
