---
gsd_state_version: "1.0"
current_phase: 1
current_phase_name: 规则内核与表达式沙箱
status: planning
stopped_at: "Phase 1 规划完成并收口 §13（`state.planned-phase` + `roadmap.annotate-dependencies`）。8 个 plan 经 3 轮独立 checker 复核后 0 blockers；`stopped_at` 记录的是「规划已交付、待执行」，非中断"
last_updated: "2026-09-29T13:50:50.850Z"
last_activity: 2026-09-29
  last_activity_desc: "`/gsd-plan-phase 1` 规划完成并通过 §13 收口：8 个 plan / 5 个 wave，checker 3 轮最终 0 blockers，需求覆盖 48/48，4 项确定性门全绿。未提交（用户指示跳过 commit）"
progress:
  total_phases: 6
  completed_phases: 0
  total_plans: 8
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-29) · .planning/ROADMAP.md (2026-09-29) · .planning/REQUIREMENTS.md (152 条 v1 / 10 条 v2 / 16 项排除)

**Core value:** 业务服务只需引入依赖、加一个注解，即可获得生产可用的限流 / 灰度 / 缓存能力，且每一次决策事后都能追溯到具体规则和规则版本。
**Current focus:** Phase 1 — 规则内核与表达式沙箱（**规划已完成**：8 个 plan / 5 个 wave，待执行）

## Current Position

Phase: 1 (规则内核与表达式沙箱) — READY TO EXECUTE
Plan: 0 of 8 in current phase
Status: Planned — 8 个 plan 已通过 checker（0 blockers）+ 4 项确定性门（`verify-command-paths` / `verify-failure-directions` 26 项 0 blocker 0 warning；`api-coverage-verify-pre` passed；scoped `gap-analysis` 48/48 覆盖）
Last activity: 2026-09-29 — `/gsd-plan-phase 1` 完成规划并收口 §13。8 个 plan 经 3 轮独立 checker（`gsd-plan-checker`）复核：第 2 轮 5 minor + 1 advisory、第 3 轮 4 minor，**全部已修，最终 0 blockers**。需求覆盖 48/48；决策翻译门（INV-1..INV-15）15/15 落入 plan；DAG 无环、wave `1,2,3,3,4,5,4,5` 同 wave 零文件重叠；威胁行 `T-1-01..T-1-25` 齐全。**未提交**（按用户指示跳过 §13d commit）

Progress: [░░░░░░░░░░] 0%

**下一个动作:** `/gsd-execute-phase 1` —— 按 wave `1,2,3,3,4,5,4,5` 顺序执行 8 个 plan（wave 3 含 01-03/01-04，wave 4 含 01-05/01-07，wave 5 含 01-06/01-08；同 wave 无文件重叠，可并行）。**不要**先规划 Phase 2——`DecisionRecord` 字段集、meter 契约、版本信封都在 Phase 1，Phase 2 只能接线不能定契约。

**Phase 1 规划输入**（已消费，规划产物已生成）:
- `phases/01-规则内核与表达式沙箱/01-CONTEXT.md` — 第 1 节（须冻结的三项契约）、第 3 节（INV-1..INV-15 不可动摇不变量）、第 5 节（8 项强制验证门）、第 6 节（10 项规划须自行设计的开放项）
- `phases/01-规则内核与表达式沙箱/01-RESEARCH.md` — 含 `## Validation Architecture`（下一轮 §5.5 据此生成 VALIDATION.md，缺失则 plans 失败 Dimension 8）、`## Edge Coverage`、`## Prohibitions`、D.1–D.9 discretion 项设计建议、6 项 Open Questions
- `phases/01-规则内核与表达式沙箱/evidence/` — JDK 8 实测原始输出，攻击矩阵结论的证据来源

## Performance Metrics

**Velocity:** 尚无执行记录（Total plans completed: 0）

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

*每次 plan 完成后更新*

## Accumulated Context

### Decisions

完整决策表见 PROJECT.md → Key Decisions。以下 9 条为 **2026-09-29 已定案**项，**不得重开**：

| # | 决策 | 影响阶段 |
|---|------|----------|
| D1 | 集群令牌层 = Redis + Lua(EVALSHA)；Sentinel 仅本地模式；构建排除 `sentinel-cluster-client-default` / `-server-default`；C12 不再是 Sentinel 集群处理器模块 | P1, P2 |
| D2 | 缓存两级一致性 = 单调序列号（乱序丢弃）+ 周期对账；强制 `l1Ttl < l2Ttl`、per-key single-flight、分布式锁+双检、soft-TTL、每缓存 `l1Enabled` | P1(架构), P4 |
| D3 | targeting-key 全局统一优先级：方法入参 → 路由头 → 主体标识；发布时强制声明，缺失即拒绝发布 | P1(路由), P3 |
| D4 | 离线决策消费者位于示例服务内（C13），不新增部署件 | P5 |
| D5 | 两个目标接入服务均启用集群限流模式（使 ≥3× 余量证据能在真实流量下取得） | P2, P6 |
| D6 | 表达式引擎 = **Aviator 5.4.4 沙箱模式**，SpEL 禁用。**代价是沙箱为运行时开关**，故 EXPR-03 攻击矩阵 + EXPR-04 JMH + EXPR-02 启动断言三项为 P1 硬性验收门；`ExpressionEngine` SPI 保留 | P1 |
| D7 | 故障默认值按业务线参数化，随 `dataLine` 注册项（`{name, defaultFailStrategy}`）走，**SDK 代码内无业务线名**；**清算入站 fail-open 固定在注册表中并由守护测试钉住**（反转即测试失败）+ code-review 检查项；每条 fail-open 规则配独立静态本地回退配额 | P2, P3, P6 |
| D8 | 缓存敏感数据默认拒绝（未显式 `sensitive=false` 即不写 L1、key 只存哈希前缀） | P4 |
| D9 | 性能预算 p99 SDK 开销 < 10 µs（**不含**集群 RTT）；集群 RTT 单独列为一条预算线 | P2, P6 |

另有 8 条 PROJECT.md 早期决策标记为 Pending，实质由上述定案覆盖或不受阶段缝影响（限流用 Sentinel、灰度自研、注解注入 + 表达式路由、表达式沙箱 + 预编译、契约冻结、缓存只做通用层、控制台拆子应用、可观测性独立模块——后者已修正为「独立模块但契约在 P1」）。

**Phase 1 讨论新增 25 项决策**（2026-09-29，**不得重开**），全文见 `phases/01-规则内核与表达式沙箱/01-CONTEXT.md`，不变量编号 INV-1..INV-15。其中改变了既有设计的三条：

| # | 决策 | 覆盖了 |
|---|------|--------|
| F1 | `dataLine` 为**开放注册集合**，SDK 不硬编码业务线（原「内置固定枚举」被反转） | 2.1 Q2 |
| F2 | `ruleSetVersion` / `schemaVersion` / 原子性 / 单调性 / 校验**全部按能力分** | `GOV-01`、`GOV-03`、`GOV-04`、`GOV-06`（连带修订） |
| F3 | env **反射 + 递归展开 + 白名单**；强敏感字段永不进 env，白名单是唯一屏障 | `SEC-01` 决策日志白名单 |

### Pending Todos

None yet.

### Blockers/Concerns

**⚠ `enableSandboxMode()` 不关闭以下 4 类越界路径**（2026-09-29 Zulu JDK 8.96 实测，Aviator 5.4.4 默认配置，证据 `phases/01-.../evidence/probe3-output.txt`）。这 4 类**必须由 SDK 主动关闭**，不能依赖沙箱开关本身：

| # | 类别 | 实测 | 缺口 |
|---|---|---|---|
| 6/6b | **ReDoS** | `s =~ /^(a+)+b$/` n=26 → 1227ms，n=40 → 4000ms 未完成；`string.replace_all(s,'^(a+)+b$','x')` 同样挂死 | `EVAL_TIMEOUT_MS=1000` **无法打断**——`java.util.regex` 在单步解释器步骤内运行，超时检查插不进去。故 EXPR-08「编译期禁止正则字面量与 `=~`」是**唯一有效控制手段**，非风格偏好 |
| 9 | **属性语法泄漏** | `ENABLE_PROPERTY_SYNTAX_SUGAR=true`（默认）时 `amount.class` 返回 `class java.lang.Long`；置 false 后返回 `null` | 信息泄漏。必须启动即关闭 + 启动断言校验 |
| 9b | **env 改写** | `PUT_CAPTURING_GROUPS_INTO_ENV` 沙箱下仍为 `true`，`(a)(b)` 捕获组直接写入传入 Map（`$0,$1,$2`）；配合 `USE_USER_ENV_AS_TOP_ENV_DIRECTLY=true` 与 `Assignment` 特性，`amount = 999` 可改写业务传入的 env | 与 INV-7/INV-8 冲突。必须每次决策新建 env + 显式关闭这三个 option |
| 7b | **深递归抛 `StackOverflowError`** | 产生的是 `Error` 而非 `Exception` | 会**逃过 `catch (Exception)`**。求值边界必须 `catch (Throwable)` |

**另一条实测结论：LRU 编译缓存不限制元空间。** 20,000 次**合法** `compile()` 精确产生 20,000 个类、Metaspace +7MB；`useLRUExpressionCache(50)` 下 5,000 个不同表达式仍产生 5,000 个类（GC 不回收已加载类）。`validate()` 20,000 次只 +12 个类——故 EXPR-09「先 validate 再 compile」是唯一有效的元空间保护，**规则总数硬上限是第二道必需防线**。

以上均**不推翻**已锁定的 25 项决策：Area 4 Q1「按能力段整段拒绝」仍是正确处置粒度；Area 3 的反射+白名单方案被 env 改写实测**加强**（正是 `STACK.md`「绝不传可变 map」红线的必然结果）。它们是**新增的强制实现约束**，须在 PLAN.md 中落为任务。

**UNVERIFIED 登记簿**（权威来源 `research/SUMMARY.md:375–399`）。**其中 4 项"需要人决策"已全部闭合**：

| 项 | 状态 | 闭合位置 |
|---|---|---|
| 延迟预算 p99 < 10 µs | ✅ 已闭合 → D9 | P2 判定 |
| 缓存一致性协议 | ✅ 已闭合 → D2 | P1 架构 / P4 |
| 故障默认值按业务线参数化 | ✅ 已闭合 → D7 | P2 / P3 / P6 |
| `sensitive` 默认值 | ✅ 已闭合 → D8 | P4 |

**仍未闭合、且不得被继承为事实的两项**：

1. **社区延迟数字全部为 LOW / UNVERIFIED**（STACK.md 的 Redis Lua p50≈0.3ms / p99≈1.5ms 等，来源互不一致、拓扑与载荷敏感）。仅作数量级规划输入。**任何 SLO、任何验收结论都不得引用这些数字**；P2 用 JMH 对真实 Redis 替换，P6 发布本环境报告。（PERF-08）
2. **Aviator CVE 需在启用前逐项复验。** `CVE-2021-41862`（CVSS 9.8）为厂商公告（MEDIUM 置信度），影响范围 ≤ 5.2.7，5.4.4 落在该范围外并含 5.4.3→5.4.4 沙箱逃逸修复。**这是选定 D6 的证据基础，Phase 1 必须逐项复验而非继承。**

**其他需要注意的**：
- P3 的 targeting-key 顺序与改桶拒绝规则是 **decide-before-coding** 项，三条业务线可能需要不同顺序 → 先 `/gsd-discuss-phase 3`。
- 数据线分类尚未成为数据模型中的一等概念（`SUMMARY.md:420`）。R6 的修复要求规则 schema 携带数据线维度，而 schema 与版本信封在 P1 冻结——**不可推迟到 P1 之后**。
- 控制台子应用的多租户 / RBAC 缺口必须以**文字**写入 P6 交接文档，不能只写代码注释。

### Deferred Items

v2 需求（`REQUIREMENTS.md` → v2 Requirements），均不映射到任何阶段：V2-01 业务语义缓存 · V2-02 分布式本地配额协调 · V2-03 两级限流策略 · V2-04 变更预览/干跑 · V2-05 跨能力原子发布 · V2-06 细化 last-known-good · V2-07 测试支持模块 · V2-08 完整诊断端点 · V2-09 热路径可插拔扩展点 · V2-10 引擎 JMH 横向对比基线（若启用 Aviator 需在 P1 补齐）。

## Session Continuity

Last session: 2026-09-29 — 项目初始化（PROJECT.md / REQUIREMENTS.md / research/）→ 路线图与状态建立 → Phase 1 讨论收口
Stopped at: `01-CONTEXT.md` 生成完成（4 区 / 25 决策 / 4 修正）；checkpoint 交叉引用勘误 + Area 3 Q4 前提注解已落；`RL-19` 修订经用户单独授权，REQUIREMENTS.md 复校 152/152 唯一 ID、0 处残留
Resume file: `phases/01-规则内核与表达式沙箱/01-CONTEXT.md`
