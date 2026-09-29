# Project Research Summary

**Project:** 信用卡实时数据系统 · 基础能力平台 (embedded foundation-capability SDK + control plane)
**Domain:** Greenfield in-process Java 8 SDK (rate limiting / canary / two-level cache) + its versioned management control plane, for a multi-line credit-card real-time data system
**Research inputs:** [STACK.md](STACK.md) · [FEATURES.md](FEATURES.md) · [ARCHITECTURE.md](ARCHITECTURE.md) · [PITFALLS.md](PITFALLS.md) · [../PROJECT.md](../PROJECT.md)
**Researched:** 2026-09-29
**Confidence:** MEDIUM-HIGH overall (see Confidence Assessment). Stack pins HIGH, architecture boundaries HIGH, features HIGH, third-party ecosystem behavior MEDIUM-LOW, token-layer and expression-engine conclusions were OPEN in PROJECT.md and are **resolved decisively below**.

---

## TL;DR (中文要点)

- **表达式引擎已定案：v1 用受限自研 DSL**（语法层不存在方法调用），**全面禁用 SpEL**（Spring 5.3.x 无修复线 + JDK 8 堵死升级路径）。备选引擎 Aviator 5.4.4 仅在 ADR 论证 + 攻击用例矩阵 + JMH 基准齐备后启用。
- **集群限流不是"一个全局阈值"**：Sentinel 默认 `thresholdType=AVG_LOCAL` 会把配额放大 N 倍并随连接数漂移，且静默降级。PROJECT.md 的集群限流需求必须按本文件 R2 重写才成立。
- **技术栈不动**（SCA 2021.0.5.0 vs Boot 2.7.6 的偏斜可容忍），但必须用 Spring 防火墙 + `optional` 依赖 + Boot 2.6.13/2.7.6 双线兼容测试**证明**它可容忍。SCA 2021.0.6.x 管理同样的 Sentinel 1.8.6 / Nacos 2.2.0，不构成换栈理由。
- **"SDK 不处理 PAN"目前是假命题**，除非 SDK 主动加四道护栏（`sensitive` 护栏 / 决策日志字段白名单 / 指标标签白名单 / P6 敏感数据路径清单交付物）。
- **集群令牌层定案：Redis + Lua（EVALSHA），Sentinel 只跑本地模式**。不用 Sentinel 自带 cluster transport（其 20ms 调用线程阻塞不可由调用方约束），也不用自带 ClusterTokenServer（30k QPS 硬天花板 + 静默降级）。
- **PITFALLS 的 P1–P6 与 ARCHITECTURE 的构建顺序一致**，无冲突；两处需要修订（指标契约落位、失败策略按数据线参数化）。
- 阶段结构与构建顺序**沿用 ARCHITECTURE.md 的 Phase-Seam Map 与 Maven reactor 顺序，不重新发明**。

---

## Executive Summary

This is not a microservices product. It is two physically separate systems that share exactly one artifact — the rule schema. The **control plane** (a deployable management API, one instance) owns versioned rules, publish/rollback, and audit, and materializes the current rule set into the **already-deployed Nacos**. The **data plane** is an in-process library embedded in N instances × M business services; it never talks to the control plane, never talks to the config center on the decision path, and serves every decision from one immutable, version-stamped `RuleSetSnapshot` swapped by a single `AtomicReference`. Everything the four researchers agree on follows from that one structural fact: the hot path must be a memory read, so freshness, validation, versioning, and degradation must all be designed as off-path concerns. The SDK is explicitly **not** competing on algorithms — Sentinel's limiter, flagd's bucketing, and Caffeine's cache are each better than a re-implementation. It competes on **one governed control plane over three mechanisms with a single decision record, a single audit trail, and a single degradation model** (FEATURES: "what the SDK is actually competing on").

The recommended approach, now that the research is synthesized: keep the stack exactly as PROJECT.md pins it (JDK 8 / Boot 2.7.6 / SC 2021.0.5.0 / SCA 2021.0.5.0 / Sentinel 1.8.6 / Nacos client 2.2.0), keep Sentinel for what it is good at (sliding-window counting, `ParamFlowRule` for per-card limits) but run it **local-only**, and put the cross-node counter in a **Redis Lua script owned by the SDK with a ~2–3 ms client timeout**. Rule arguments route through a **restricted self-built DSL** compiled once per rule version; **SpEL is disqualified outright**. Delivery is a 13-module Maven reactor whose module boundaries are the mechanism that enforces the coupling contract (no `spring-*` outside `foundation-autoconfigure`, control plane depends on `foundation-core` alone, `foundation-nacos` is the only module that knows Nacos exists), with `maven-enforcer-plugin` rules in the parent POM from commit one. Phase structure is the six seams already mapped in ARCHITECTURE.md (P1 rule kernel + expression sandbox → P2 rate-limit execution + failure policy → P3 canary → P4 two-level cache → P5 decision observability → P6 management API + integration validation), with the `DecisionRecord` contract, the bounded ring buffer, the version envelope, and the enforcer rules all landing in P1 because none of them can be retrofitted.

The material risks are not all the same kind of risk, and the roadmap must treat them differently. **Security/authority risk** is the expression engine: a config-server-pushed expression evaluated in-process is remote code execution in the business service's own JVM, and on the authorization line that means direct access to authorization data (PITFALLS P1–P2, blast radius: all adopters simultaneously). **Correctness risk nobody will notice** is the cluster quota: Sentinel's cluster defaults multiply the configured limit by the connected-instance count and silently fall back to local counting when the token server has no matching rule — meaning the backend gets overrun while monitoring reports "rate limiting is working" (P5), and the opposite failure (token-server saturation) silently *shrinks* the quota and false-rejects authorization traffic (P6). **Scope-claim risk** is the PCI statement: the SDK claims to be outside PCI scope because it does not handle PAN, but a generic cache and a generic decision log are both trivially laundered cardholder data through (P11). **Irreversibility risk** is observability ordering: decision history cannot be backfilled, so if the `DecisionRecord` contract does not exist in P1, the 100%-traceable-to-rule-version success metric can never be met (P15). **Delivery risk** is version skew: SCA 2021.0.5.0's official baseline is Boot 2.6.13, not the pinned 2.7.6, and because this ships as a library the skew propagates into every adopter's dependency tree (P9) — tolerable only if proven by a two-Boot-version compat test.

---

## Conflict Resolutions

Five tensions were open across PROJECT.md and the four research documents. Each is resolved decisively below. **Do not re-open these during roadmap creation; if you disagree with a resolution, change PROJECT.md explicitly first.**

### R1 — Expression engine: v1 ships a restricted self-built DSL. SpEL is banned. Aviator 5.4.4 is the named, ADR-gated alternate.

**The conflict.** PROJECT.md:114 leaves the engine open across AviatorScript / QLExpress / restricted self-built DSL, and marks it for this research stage. STACK.md recommends **Aviator 5.4.4** with `enableSandboxMode()`. PITFALLS.md P1 disqualifies **SpEL** entirely and P2 argues third-party sandboxes are runtime toggles, not grammar-level guarantees, defaulting the choice to the **restricted self-built DSL**.

**Evidence.**
- SpEL is not a sandbox and cannot be patched on this stack: `CVE-2026-59283` (SimpleEvaluationContext guard bypass) and `CVE-2026-41849/41850/41851/41852` all fall in the Spring 5.3.x affected range; OSV marks the 5.3 branch `Last Affected: 5.3.39` with no fixed version; Boot 2.7.6 pins Spring Framework **5.3.24** (already below 5.3.26/5.3.27), and JDK 8 being a hard constraint blocks the upgrade path (PITFALLS P1). "Wrap it in `SimpleEvaluationContext`" is listed in PITFALLS' debt table as **never** acceptable.
- Sandbox mode in a general engine is a switch that can be flipped: QLExpress security is opt-in and has a path to rewrite `forbidInvokeSecurityRiskMethods` from the expression context; MVEL has no sandbox semantics; AviatorScript's cited `CVE-2021-41862` (CVSS 9.8) has an affected range of **≤ 5.2.7** — STACK's pin of **5.4.4** sits outside that range and includes the 5.4.3 → 5.4.4 sandbox-escape fix (ARCHITECTURE sources, HIGH). So Aviator is **not** disqualified; it is *insufficient on its own*.
- Availability is orthogonal to safety: even a perfectly sandboxed expression can burn CPU via ReDoS / deep nesting / huge string ops, and JDK 8's `java.util.regex` has no later hardening (PITFALLS P3).

**RESOLUTION (v1, decisive):**
1. **SpEL is disqualified.** Zero direct use of `spring-expression` for Nacos-delivered expressions, whether or not wrapped in `SimpleEvaluationContext`. Record as an ADR and a code-review checklist item; the verification is a dependency-tree assertion (PITFALLS Pitfall-to-Phase Mapping, P1 row).
2. **v1 default = restricted self-built DSL**, implemented inside `foundation-expression` (C2) behind the SPI that ARCHITECTURE already specifies (`ExpressionEngine` / `CompiledExpression` / `SandboxPolicy` / `ValidationResult`). The grammar supports literals, field access limited to an explicit `EvalContext` allow-list, comparison / logic / arithmetic, and a bounded set of collection operations. **Method-call syntax does not exist in the grammar** — it is not intercepted at runtime (PITFALLS P1).
3. **Aviator 5.4.4 is the named alternate** and may be enabled only by ADR after: (a) the fixed JDK 8 attack-case matrix passes **under default configuration** (reflection escape, constructor escape, static methods, `ClassLoader`/`Thread`/`Runtime` chains, file/network IO, infinite loop/recursion, oversized string/array, ReDoS, deep nesting), and (b) a JMH comparison shows it meets the layer-4 budget. QLExpress**4** 4.1.3 is the second alternate (STACK.md: default isolation, no codegen so no metaspace profile, interpreter throughput cost must be benchmarked). QLExpress 3.3.4 stays on the never-use list.
4. **Non-negotiable regardless of engine** (PITFALLS P3, P13): step budget with `EvaluationBudgetExceeded`; expression length cap (e.g. 512 chars); AST node cap (e.g. 128); field-depth cap and nesting ≤ 5; **no regex inside expressions** — business matching goes through named, pre-compiled matchers registered in SDK code and referenced by ID; **no thread-pool timeout as the primary bound** (a `Future.get(timeout)` does not cancel the task and is how an SDK poisons the host's thread pool). Per-`ruleId` slow-evaluation counts are exported as a rule-quality signal.
5. **The engine is the second line of defense, never the first.** ARCHITECTURE Pattern 2 (`ArgumentSource`, bounded extraction, ≤ 64 pre-projected fields, flat immutable `Map<String,Object>`, never the business DTO, never the raw request object) is the first line and is what makes the "no arbitrary method invocation" constraint true regardless of which engine is behind the SPI. With a small DSL this boundary is cheap; with a general engine it is load-bearing. (Synthesis note: the same boundary is also the single place PAN/card-number masking is enforced for all three capabilities.)

**What would reverse it.** (a) A compliance review that rejects embedding any script engine in the process — irrelevant for v1, because v1 is not embedding one. (b) Evidence that the restricted grammar cannot express the top real rule use cases, **plus** a JMH result showing Aviator 5.4.4 sandbox mode fits the layer-4 budget — then Aviator 5.4.4 replaces the DSL through the SPI with no downstream change. (c) Any move off JDK 8 / off Spring 5.3.x (see R3), which would re-open the whole engine question including whether a sandboxed framework expression is acceptable at all.

**Downstream consequence the roadmap must carry.** FEATURES.md recommends deferring rule-argument expressions to v1.x (its A7 row: "v1 uses an explicit enumerated attribute set"). **That recommendation is overridden** by PROJECT.md:29–31 (expressions route method arguments to rules, sandboxed, precompiled + cached) and PROJECT.md:81 (the custom routing layer exists *because* `ParamFlowRule` only supports `paramIdx` + a single threshold). Expressions are v1. FEATURES' anti-feature A7 — *per-call* parsing/compilation of arbitrary SpEL/script — still stands in full, and is the distinction the roadmap must preserve.

### R2 — "Cluster rate limiting" is not a global limit. The requirement as written is wrong and must be restated.

**The conflict.** PROJECT.md:33–34 and the constraint at PROJECT.md:92 treat cluster rate limiting plus automatic fallback to single-node as a settled requirement. PITFALLS P5 shows the Sentinel cluster path, used with any default, does not provide that.

**Evidence (PITFALLS P5, corroborated by ARCHITECTURE risk A3 and STACK.md's cluster-mode exclusion).** Three mechanisms compound:
1. `DefaultTokenService.requestToken` looks up rules by `flowId` on the token server and returns **`NO_RULE_EXISTS`** when there is no match.
2. `ClusterFlowChecker.applyTokenResult` routes `NO_RULE_EXISTS` / `BAD_REQUEST` / `FAIL` / `TOO_MANY_REQUEST` all into `fallbackToLocalOrPass`, and **`ClusterFlowConfig.fallbackToLocalWhenFail` defaults to `true`**.
3. `ClusterFlowChecker.calcGlobalThreshold` under the default `thresholdType = FLOW_THRESHOLD_AVG_LOCAL` returns `count × getConnectedCount(flowId)`. A configured "cluster 1000 QPS" is **5000 QPS on five client nodes**, drops to 4000 when a node's heartbeat expires, and becomes 7000 after a scale-out. The global threshold **drifts with the connection count**, live.
4. Official issue #2457 records exactly this with a configured rule and an empty `ClusterFlowRuleManager.FLOW_RULES`: the log prints only `no rules in token server`, cluster limiting does not apply, it silently degrades to local, and **no exception is thrown**. The typical cause is precisely the custom routing layer — the router derives `flowId` itself while the token server's rules come from a different source/version.
5. The degradation path and the success path return the **same business result (allow)**. Only internal metrics differ, so no business-side alert fires.

**RESOLUTION.**
- **The requirement stands; its wording does not.** Restate PROJECT.md's cluster-rate-limit requirement as: *cluster limiting is provided by an SDK-owned shared counter (R5), with a bounded client timeout, and a degradation path to local counting that is **explicit, queryable, and alert-classified by reason** — not a library default.* Without that last clause the requirement is unsatisfiable on Sentinel cluster semantics.
- **"Automatic fallback" is a safety property only if the degradation state is a first-class state.** Per PITFALLS' debt table, depending on `fallbackToLocalWhenFail` / `thresholdType` defaults is **never** acceptable. If the Sentinel cluster path is ever used, four things are mandatory, all already prescribed: set `thresholdType = FLOW_THRESHOLD_GLOBAL` explicitly; set `fallbackToLocalWhenFail` explicitly; derive `flowId` from a **single source** (`ruleId` + rule version, or an SDK-owned mapping) and run a **rule-consistency probe** after startup and after every push; and expose the **effective global threshold × participating node count = real quota** through the management API.
- **Degradation reasons must be classified by direction, because the two directions are opposite failures** (PITFALLS P6): `NO_RULE_EXISTS` and stale-version skew **amplify** the quota (backend overrun — the exact thing rate limiting exists to prevent, while monitoring shows "working"); `TOO_MANY_REQUEST` (token-server saturation) **shrinks** the quota to `count` and mass false-rejects authorization traffic (visible as a transaction-success-rate drop). Different alert, different on-call runbook. Three counters minimum, per STACK: `rejected_by_sentinel_local`, `rejected_by_cluster_token`, `degraded_to_local` — and `degraded_to_local` must carry the reason.
- **Architecturally this is already the design.** ARCHITECTURE Pattern 3 (`ClusterGate`: a scheduled probe flips `CLUSTER`/`LOCAL`; the hot path only *reads* the state; per-request try/catch is Anti-Pattern 7) plus FEATURES D2 (queryable, alertable degradation state) is the mitigation that must exist **regardless of which token implementation is chosen** (ARCHITECTURE open question 1). R5 removes the amplifier rather than papering over it.

**What would reverse it.** Nothing in the research supports building on Sentinel's cluster semantics with defaults. The only reason to keep the Sentinel token server is capacity evidence at a scale below its 30k QPS ceiling (R5) — and the `ClusterGate` + reason-classified degradation is required either way.

### R3 — The SCA/Boot version skew is tolerable. Keep the pinned stack. Contain it, and prove it.

**The conflict.** PROJECT.md:79/86 hard-requires Spring Boot **2.7.6** with Spring Cloud Alibaba **2021.0.5.0**. SCA 2021.0.5.0's official matrix maps to Boot **2.6.13**.

**Evidence.** PITFALLS P9 and ARCHITECTURE risk A1 both flag the mismatch: Sentinel/Nacos starter auto-configuration is written against a Boot 2.6.x baseline, and 2.7.x is the transitional release where `spring.factories` is being migrated toward `AutoConfiguration.imports` — the exact band where auto-config behavior differences appear. Because the deliverable is an in-process library (PROJECT.md:90), the skew is not private: the SDK's transitive Sentinel/Nacos/Micrometer versions land in the adopter's `dependency:tree` and can be overridden by the SDK's own dependency management. Countervailing evidence: STACK.md records that the 2021.0.x train documents Boot 2.6.x/2.7.x coverage, and that **SCA 2021.0.6.x exists and still manages Sentinel 1.8.6 / Nacos 2.2.0** — so bumping SCA would not change the pinned Sentinel/Nacos versions and would not by itself remove the skew (Boot would still have to move).

**RESOLUTION.**
- **The stack does not move.** This is a user-pinned hard constraint; the skew is intra-Boot-2.x, inside the train range SCA documents, and the only candidate bump (SCA 2021.0.6.x) does not resolve it. Also note the Spring Framework consequence is already moot for the security question: Boot 2.7.6 pins 5.3.24, below 5.3.26/5.3.27, and the 5.3 branch has no fix for the 2026 SpEL advisories anyway (R1).
- **"Tolerable" is currently an assumption, not a fact, and the roadmap must convert it into a fact in P1 and P6.** The containment, all already specified:
  - `bannedDependencies` Spring firewall in the parent POM on every module except `foundation-autoconfigure` / `foundation-console-api` (mind the `<includes>` exemption-list gotcha).
  - Every Sentinel / Nacos / Micrometer / Redis integration marked `<optional>true` / `provided` — **the SDK must not take over the adopter's versions** (FEATURES XX-2, PITFALLS P9). The SDK must work in a non-Spring host with none of them on the classpath.
  - Every auto-configuration class annotated `@ConditionalOnClass` + `@ConditionalOnMissingBean`; no strong-side-effect beans. Boot **2.7** registration is via `META-INF/spring.factories`, **not** `AutoConfiguration.imports` (3.x only).
  - A **startup assertion** that logs and health-exposes the actually-resolved Sentinel/Nacos/Micrometer versions and warns when they differ from the compile baseline, so a skew surfaces immediately rather than at 3 a.m.
  - **`sdk-compat-tests`** as separate consumer modules — `boot-2.6.13-smoke` and `boot-2.7.6-smoke` — because testing "a consumer on Boot 2.6.13 can use this jar" from inside the jar is circular. P1 acceptance = the 2.6.13 smoke test passes; P6 delivers the full version matrix (Boot 2.6.x / 2.7.x × SCA 2021.0.5.0) in the integration doc.
- **The server artifact must be excluded.** `spring-cloud-starter-alibaba-sentinel` transitively includes Sentinel's cluster **server** artifact (ARCHITECTURE sources, HIGH) — which R5 makes unwanted anyway. Same for `sentinel-cluster-client-default`.

**What would reverse it.** The Boot 2.6.13 compat test failing; a security advisory that forces a Spring Framework upgrade (JDK 8 blocks it); or a Boot 3 / Java 17 migration, which would require the `jakarta.*` namespace and a Sentinel major-version change (STACK.md release-train note — Spring Cloud 2021.0.x is EOL; this is a recorded long-term risk, not a present action).

### R4 — "The SDK does not handle PAN, so it is out of PCI scope" is false by construction unless the SDK actively defends it.

**The conflict.** PROJECT.md:71 puts "real PCI DSS certification process" out of scope with the justification "the SDK itself does not handle PAN / card numbers." PITFALLS P11 re-read PROJECT.md and found the justification does not hold: the cache module is deliberately **generic**, and the observability module records decision context that can include method arguments.

**Evidence.** Three laundering paths, all in v1 scope:
- **Cache.** `sdkCache.get(key, loader)` puts the loader's return value into L2 Redis and L1 process memory. If a caller caches an account object containing a card number, the SDK becomes PAN storage — and whether Redis encrypts, persists, or access-audits is entirely outside the SDK's design. In-process L1 additionally puts PAN in the heap, reachable by heap dump and by any core dump.
- **Decision log.** PROJECT.md:54 requires per-decision traceability; the implementation temptation is to also record the `EvalContext` snapshot, the matched key, or the route expression's arguments — and **rate-limit route arguments are plausibly the card number or transaction identifier**. PCI DSS v4.0 Req 3.4 requires PAN unreadable anywhere stored, including logs; Req 10.2/10.3/10.5/10.6/10.7 set audit-event content, anti-tamper, review, and **one-year retention**.
- **Metric labels.** Cardinality discipline (ARCHITECTURE Anti-Pattern 9) and data-protection discipline (PITFALLS A8 row) point the same way: bounded enums + `ruleId` only; a card BIN or account in a label is sensitive data in a place nobody scrubs.
- The structural reason this gets missed: the compliance boundary was drawn by **component responsibility**, not by where data actually flows. An Out-of-Scope declaration is an intent, not a control.

**RESOLUTION — the claim becomes a verified technical invariant, enforced by four mechanisms that must appear in the roadmap:**
1. **Cache guardrail.** `CacheSpec` requires the caller to declare `sensitive`. When set: no L1 write, key never printed in plaintext (hash prefix only), and the docs state that L2 storage security is the caller's responsibility. *(Synthesis recommendation requiring sign-off: default `sensitive = true` / opt-out explicitly, because a caller who forgets to declare is the residual hole in "caller-declared" — a generic cache should be deny-by-default for the local tier. PROJECT.md does not decide this; P4 must.)*
2. **Decision-log field allow-list, enforced at the SDK layer.** Only `ruleId`, `ruleVersion`, `dataLine`, `decision`, `remaining`, `bucket`, `latencyUs`, `failStrategy`, `degraded`. **Never** raw arguments, raw keys, or full expression text. Debugging uses field-level redaction on `EvalContext` plus an explicit debug switch, **default off**. Masking is an SDK default (FEATURES OB-5), not a per-call-site discipline.
3. **Metric label allow-list.** Bounded enums (capability, decision type, fail reason, effective mode) plus `ruleId`; high-cardinality detail goes to the decision record. The rate-limit dimension uses an **internal proxy ID**, never a card BIN or transaction id.
4. **A P6 delivery artifact: the sensitive-data path inventory.** Every Redis key prefix, every log field, and every metric label, listed and reviewed per adopter by security. Without this, "the SDK does not handle sensitive auth data" remains a claim. The SDK explicitly does **not** do PAN encryption or provide PCI attestation — but it must provide defaults that stop the caller from making the mistake. Retention of the decision channel must be an explicit, documented property of the sink configuration, because a one-year audit requirement and a shorter default config-history retention (FEATURES notes one managed Nacos offering documenting a 30-day cap) will not coexist silently.

**What would reverse it.** Nothing — these are additive guardrails with no cost to the non-sensitive path. What *would* invalidate the Out-of-Scope framing is if v1 gained a business-semantic cache (explicitly out of scope) or a genuine PAN-handling helper; neither is on the roadmap.

### R5 — Cluster token layer: Redis + Lua (EVALSHA), Sentinel in local mode. The Sentinel token server is not used.

**The conflict.** PROJECT.md:112 leaves this open: Redis centralized decrement (Lua atomic) vs. Sentinel's built-in `ClusterTokenServer`.

**Evidence for the decision.**
- **Timeout ownership (STACK.md, verified in 1.8.6 bytecode).** `NettyTransportClient.sendRequest` calls `ChannelPromise.await(getRequestTimeout())` — a **blocking wait on the calling thread**, `requestTimeout` default **20 ms**, `connectTimeout` **10,000 ms**, port 18730. Every rate-limited request in cluster mode can therefore add up to 20 ms of stall that the caller cannot bound. On a millisecond authorization line at peak QPS, that is the outage. STACK.md's alternative: the SDK owns the timeout, ~2–3 ms, with the Lua call on one hop.
- **Quota correctness (PITFALLS P6).** `DefaultTokenService.acquireClusterToken` passes through a **namespace-level global leaky bucket `allowProceed(id)` with a hard default ceiling of 30,000 QPS** *before any rule evaluation*; exceeding it returns `TOO_MANY_REQUEST`, which also routes to `fallbackToLocalOrPass` → local threshold = `count` → mass false rejection of authorization traffic at peak, with nothing indicating it was a degradation. A multi-business-line SDK serving card authorization will cross that ceiling. Additional known defects: `windowIntervalMs > 1000ms` makes cluster counting inaccurate (PR #3407, keep the window at 1s and handle burst by control policy), plus namespace bucket leakage and averaged thresholds (issue #1639).
- **HA and ops.** Sentinel's cluster mode is one more always-on deployable with no failover of its own, holding all cluster state in memory (STACK.md). Alibaba maintainers concede the HA problem is unsolved.
- **Degradation control.** With Redis, the fallback is the SDK's own bounded-timeout catch → local evaluator, plus the `ClusterGate` probe — the SDK owns both the timeout and the state machine, and can emit the reason-classified degrade counters R2 requires. With the Sentinel transport, degradation happens inside a library that cannot be instrumented to that level.
- **Corroboration.** PITFALLS' technical-debt table: Redis + Lua = **推荐默认** (the proposed conclusion for PROJECT.md:112 TBD), with the cost being an extra Redis dependency and Lua maintenance. STACK.md's "What NOT to Use" excludes the Sentinel cluster artifacts for the same reasons. Both independent researchers landed on Redis; ARCHITECTURE deliberately left the choice open and listed the token-server's ceiling and correctness caveats, and states the `ClusterGate` is required either way. FEATURES defers "distributed local-quota coordination" to v2 as a research project.
- **Lettuce 6.1.10.RELEASE** (Boot-managed) is the client: one shared Netty connection instead of a per-call socket checkout, which is what makes a 2 ms budget realistic on a hot path. Redis is already a dependency for L2, so this adds **no new infrastructure** — only a new key namespace.

**RESOLUTION (v1):**
- Sentinel runs **local mode only**. `sentinel-cluster-client-default` and `sentinel-cluster-server-default` are excluded from the build.
- The cluster counter is a **Redis Lua script** acquired via **EVALSHA** (pre-built script; the body never crosses the wire), with the exact algorithm STACK.md specifies: `ZREMRANGEBYSCORE` to trim the sliding window, `ZCARD` for used, return `{allowed, remaining, retryAfterMs}`, and **`PEXPIRE` on the key is mandatory** (without it the sorted set grows forever, one entry per card ever seen). Client timeout ~2 ms; on timeout or error, trip to degraded-local counting and increment `degraded_to_local`.
- **The `ClusterGate` (ARCHITECTURE Pattern 3) is unchanged and required** — probe-driven `CLUSTER`/`LOCAL` state, not per-request try/catch (Anti-Pattern 7). The `TokenService` SPI in `foundation-core` is what stays fixed regardless, so the thin-gateway option below remains available.
- **Cache and token roles must be logically separated** in the same Redis: distinct key prefixes, ideally separate instances, so a token-layer flush cannot take out the business cache (ARCHITECTURE integration note).
- **Amendments to ARCHITECTURE.md's wiring this implies:** C5 no longer depends on `sentinel-cluster-client-default`; C12 is no longer a Sentinel `ClusterTokenServer` handler module. The SPI, the `ClusterGate`, and the 8-layer cost budget are unaffected.

**One decision this surfaces and the roadmapper must carry into requirements scoping.** PROJECT.md:95's delivery standard lists "SDK + 集群令牌服务" (cluster token service) as a deliverable. With the Redis decision, the token path is SDK → Redis directly, which means either (a) the "cluster token service" is the existing Redis plus an SDK-owned, versioned Lua script — **no new deployable**, or (b) a thin token gateway stays in front of Redis to own the 2 ms budget and to shard by `flowId`. **Recommendation (synthesis): (a) for v1** — one less always-on component, and the 2 ms budget is already owned by the SDK; keep `TokenService` behind the SPI so (b) can be added without touching the capabilities. This is a **delivery-standard rewording that PROJECT.md must confirm**, not a research output.

**What would reverse it.** A measured demonstration that Redis cannot sustain the required peak with ≥3× headroom in the actual topology (PITFALLS P6's stated bar), or an organizational requirement that all cross-node coordination live behind one owned service. Also note ARCHITECTURE open question 7: if the two target services ship local-only initially, P2's cluster half is **deferred, not cancelled** — the requirements still stand.

### R6 — Found during synthesis, not in the orchestrator's list: one fail-close default is wrong for at least one of the three data lines.

PROJECT.md:93 mandates "限流默认 fail-close，灰度默认 fail-open，逐条规则可覆写." PITFALLS P7 shows the same default cannot be correct for all three coexisting data lines (PROJECT.md:77):

| Data line | Requirement | Consequence of fail-close |
|---|---|---|
| 刷卡授权应答 | millisecond, high peak QPS | acceptable — protecting the backend first |
| 内部服务间账务流转 | high throughput | wide-area rejection of accounting flow → internal avalanche and fund inconsistency |
| 卡组织清算报文入站 | accuracy + idempotency + compensation | **rejecting inbound clearing messages loses clearing data and the counterparty will not retry to this system — worse than allowing** |

**RESOLUTION.** Keep PROJECT.md's per-rule-override mechanism (it is the mechanism that resolves this), but make the defaults **data-line parameterized** at two levels (rule level + data-line level, with the data-line default derived from the resource's prefix/label, set explicitly by the management API rather than implicitly inherited); **hard-code the clearing-inbound line to fail-open** in the SDK and document the exception in the code-review checklist so a later "unify the defaults" cleanup does not revert it; and give every fail-open rule an **independent static local fallback quota** so "config unavailable + no fallback" can never mean "unlimited pass". The actually-applied `failStrategy` must be recorded in `DecisionRecord`, or no incident can later explain why a request was allowed or refused. Canary fail-open is right for *new-feature* canaries and wrong for *rule changes on an already-live path* — same treatment: per-rule, not global. Related: the PITFALLS debt table lists "a single global safety switch" as **never** acceptable; a global switch also makes the degradation state unqueryable.

---

## Carried-Forward: PROJECT.md decisions this synthesis does **not** revisit

| PROJECT.md decision | Status after synthesis |
|---|---|
| 限流采用 Sentinel 而非全自研 (`:104`) | **Preserved.** Sentinel does the sliding-window counting, always. R5 narrows it to local mode; the counting decision stands. |
| 灰度评估器完全自研 (`:105`) | **Preserved.** Confirmed: Nacos and Sentinel have no usable canary capability; Nacos config gray only has beta-tag granularity. |
| 注入点为接口/方法注解，参数经表达式路由到规则 (`:106`) | **Preserved, strengthened.** This is the architectural justification against Anti-Pattern 6 (per-entity Sentinel Resources). R1 keeps expressions in v1. Annotation is a *thin wrapper* — the programmatic guard API is the enforcer, because Spring AOP self-invocation silently bypasses the advice (FEATURES A6, RL-1). |
| 表达式求值必须沙箱化 + 预编译缓存 (`:107`) | **Preserved and given a mechanism** by R1 (grammar-level DSL + step budget) and ARCHITECTURE Pattern 2 (`ArgumentSource` first line of defense). |
| 管理 API 契约在本阶段冻结 (`:108`) | **Preserved.** ARCHITECTURE's contract section is the shape: `/api/v1`, `tenantId` **never in the path**, DTOs never entities, every mutation idempotency-keyed, `ETag`/`If-Match`, RFC 7807 errors, every response carries the current `ruleSetVersion`, additive-only evolution. |
| 缓存 v1 只做通用层 (`:109`) | **Preserved.** Confirmed by FEATURES A4 and the three-business-lines-are-still-being-mapped context. |
| 控制台界面拆为独立子应用 (`:110`) | **Preserved.** FEATURES A1: never a feature of this SDK. Nacos is the distribution store and a per-blob history backstop; the domain version, atomic publish, and decision correlation live **above** it. The Sentinel Dashboard is rejected as a management surface (memory-only, unaudited, no history). |
| 可观测性作为独立模块 (`:111`) | **Preserved, with a correction.** Independent module is right; "therefore later" is wrong. `DecisionRecord` + ring buffer land in P1 (PITFALLS P15, ARCHITECTURE Pattern 5). |
| 集群令牌服务实现方式待定 (`:112`) | **Resolved by R5** → Redis + Lua. Delivery-standard wording needs PROJECT.md confirmation. |
| 缓存两级一致性协议待定 (`:113`) | **Still open** — correctly so. P4 must fix it before code: the decision is *which convergent protocol*, not Redis vs MQ. Recommended shape is already designed: monotonic sequence on every invalidation, L1 TTL cap as a hard staleness bound, TTL jitter, per-key single-flight, distributed lock + double-check for L2, and a **periodic reconciliation task** — because Redis Pub/Sub is at-most-once and a lost message is permanent staleness otherwise. MQ would need an ADR and an explicit argument that PROJECT.md:87 (no second *config center*) does not forbid it. |
| 表达式引擎选型待定 (`:114`) | **Resolved by R1** → restricted self-built DSL, Aviator 5.4.4 as ADR-gated alternate, SpEL banned. |
| 技术栈固定 (`:79`, `:86`) | **Preserved, un-moved** per R3. SCA/Boot skew contained and to be proven. |
| 必须复用已有 Nacos (`:87`, `:78`) | **Preserved.** `foundation-nacos` is the only module that imports `nacos-client`; Nacos holds only the materialized current set and is a distribution target, not the system of record. |
| 故障策略：限流 fail-close / 灰度 fail-open，逐条覆写 (`:93`) | **Preserved as mechanism, corrected as default** by R6 (data-line parameterized; clearing-inbound hard-coded fail-open; fail-open needs a static local fallback quota). |
| 交付标准：SDK + 集群令牌服务 + 示例服务 + 接入与运维文档，≥2 业务服务真实接入 (`:95`) | **Carried, with the token-service rewording from R5** and the P6 ordering note (governed publish must exist before canary/rate-limit rules reach production). |

**One disambiguation the roadmapper should not get wrong:** PROJECT.md:70 puts circuit breaking / bulkheads out of scope. The local failure counter in STACK's Redis-Lua client pattern is the `ClusterGate` degradation mechanism (ARCHITECTURE Pattern 3), not a Sentinel circuit-breaker capability — it does not violate the exclusion, and no breaker work belongs in the roadmap.

---

## Requirements corrections to make before scoping

The synthesis found six places where PROJECT.md's requirement text and the research disagree. These are **corrections to the requirement wording, not new scope**; the user owns them at requirements-definition time.

1. **Cluster rate limiting (PROJECT.md:33, :92)** — restate per R2. As written it implies a global limit that the Sentinel cluster path does not provide. The mechanism is unchanged (shared counter + auto-fallback); the wording must gain "SDK-owned shared counter, bounded timeout, explicit reason-classified degradation state."
2. **Automatic fallback (PROJECT.md:34)** — keep, but add "the degradation state is queryable, metered, and alerted by reason" and "the SDK does not rely on library defaults for any degradation-related setting."
3. **fail-close default (PROJECT.md:35, :93)** — keep the per-rule override, but require data-line parameterization and the hard-coded clearing-inbound fail-open exception, plus a static local fallback quota for every fail-open rule (R6).
4. **PCI out-of-scope justification (PROJECT.md:71)** — keep "real certification process" out of scope, but restate the reason as a *verified invariant* with the four enforcement mechanisms and the P6 sensitive-data path inventory (R4).
5. **Delivery standard (PROJECT.md:95)** — "cluster token service" becomes "Redis + SDK-owned versioned Lua token layer" (optionally behind a thin gateway); PROJECT.md must confirm (R5).
6. **Performance budget (PROJECT.md:96)** — the value is TBD. *Recommendation (synthesis):* adopt **p99 SDK overhead < 10 µs excluding the cluster RTT** as the sign-off number, with the cluster RTT reported as a separate budget line, because conflating them is exactly how this gets argued away later. This is ARCHITECTURE's cost-budget proposal and ARCHITECTURE open question 5 says it needs sign-off before P2 benchmarks can be judged pass/fail.

---

## Key Findings

### Recommended Stack

Verified against Maven Central POMs and shipped bytecode, so the version pins are HIGH confidence; the latency figures are not (see UNVERIFIED register). The three BOMs — `spring-boot-dependencies:2.7.6`, `spring-cloud-dependencies:2021.0.5.0`, `spring-cloud-alibaba-dependencies:2021.0.5.0` — must be imported as `pom`/`import` scope in `dependencyManagement` **before** any Sentinel or Nacos declaration, because the SCA BOM is the single authority for the Sentinel and Nacos client versions. The one hard trap: `sentinel-datasource-nacos:1.8.6` declares `nacos-client:1.4.2` in its own POM; the version is silently correct *only* when the BOM is imported, and the failure mode is a runtime `NoSuchMethodError: com.alibaba.nacos.api.config.ConfigService.publishConfigCas(...)`, not a compile error. Enforce `dependencyConvergence` in CI from day one, and keep the explicit `<exclusion>` on `nacos-client` in that dependency declaration.

**Core technologies:**
- **JDK 1.8** — hard constraint. `javax.*` (not `jakarta.*`); all library bytecode must be major version ≤ 52; `requireJavaVersion [1.8,1.9)` prevents a newer compiler silently admitting a Java 11+ transitive such as Caffeine 3.x.
- **Spring Boot 2.7.6** — pinned. Supplies context, actuator, and the Nacos/Redis/metrics auto-configuration. Auto-config registration is `META-INF/spring.factories` (2.7 style, **not** `AutoConfiguration.imports`, which is 3.x); the `repackage` goal is for executables, not libraries.
- **Spring Cloud Alibaba 2021.0.5.0** — the BOM that resolves everything below.
- **Sentinel 1.8.6** (`sentinel-core` + `sentinel-parameter-flow-control` + `sentinel-datasource-nacos`, the last with the mandatory `nacos-client` exclusion) — sliding-window counting and `ParamFlowRule` for per-card limits, verified Java 8 bytecode. **`sentinel-cluster-client-default` / `-server-default` are excluded** (R5). `sentinel-datasource-redis` is on the never-use list — a second rule store creates the two-sources-of-truth conflict the project already rejected.
- **Nacos client 2.2.0** (from the SCA BOM) — reuses the deployed Nacos; no second config center. 1.4.x is the version the Sentinel datasource drags in and it cannot use gRPC against a Nacos 2.x server.
- **Expression engine** — **restricted self-built DSL, v1 default**; Aviator **5.4.4** as ADR-gated alternate (R1). Aviator's `enableSandboxMode()` (since 5.4.3) does disable `Module`, `NewInstance`, `InternalVars`, `StaticFields`, `StaticMethods` and reflection plus loop/eval limits, and it ships `validate()`, `useLRUExpressionCache(int)`, `invalidateCache(String)` — but its sandbox is a runtime switch, and its cited `CVE-2021-41862` affected range ends at 5.2.7, so the 5.4.4 pin sits outside that range and carries the 5.4.3→5.4.4 escape fix.
- **Caffeine 2.9.3** — L1 local cache; last Java-8 line (3.0.0+ requires Java 11), Boot-managed, verified major 52. Bounded LRU, so Nacos rule churn cannot grow the heap without bound.
- **Lettuce 6.1.10.RELEASE + Redis 5.0+** — Boot-managed, Netty-based, one shared connection on the token hot path instead of a per-call socket checkout; the same Redis serves L2 and the token layer under separate key prefixes. Lettuce over Jedis specifically because of the shared-connection model; do not put both on the classpath.
- **Micrometer 1.9.6 + `micrometer-registry-prometheus`** — metrics facade and scrape endpoint, Boot-managed; `simpleclient_common:0.15.0` is transitive, do not pin it. Cardinality discipline is a performance control, not hygiene (ARCHITECTURE Anti-Pattern 9).
- **Jackson 2.13.4.20221013 / SLF4J 1.7.36 + Logback 1.2.11 / JUnit Jupiter 5.8.2** — Boot-managed. Logback 1.2.x is the *only* line that binds to SLF4J 1.7.x; a mismatch produces zero log output with no error, which would hide exactly the rate-limit and fallback events this SDK must emit.

**Build-time tools that are requirements, not nice-to-haves:** `maven-enforcer-plugin` with `requireJavaVersion`, `dependencyConvergence`, and `bannedDependencies` (the `<includes>` block is the exemption list — omit it and `foundation-autoconfigure` cannot use Spring, failing the build for a reason that looks like a misconfiguration); **Revapi / japicmp** as a binary-compatibility gate, because the SDK is a library and a public-API break is a release incident; and **JMH** to replace every latency claim with a measurement.

**Also ruled out, with reasons:** embedded/in-memory Redis or any mock for the token path (it fakes the exact failure mode the token layer exists to handle, and passes tests that fail in production); Sentinel Dashboard as the rule-authoring UI in production; Micrometer/Per-entity metric labels; any framework version not in the pinned BOM (e.g. Jackson 2.14+ is still Java 8 but is not managed by Boot 2.7.6).

### Expected Features

The SDK's competitive surface is **not** algorithms. It is: (1) domain governance — atomic publish and rollback of a rule *set* with audit correlated to decision records, which neither Sentinel's Dashboard (no persistence at all) nor Nacos (versions an opaque blob per `namespace/group/dataId`, with no cross-module release unit and no link from a version to the decisions it produced) supplies; (2) honest degradation semantics — knowing *which* guarantee is in force, and refusing to silently allow-all on an empty rule set, where the ecosystem degrades silently; (3) one decision record across all capabilities; (4) library discipline — no leaked upstream types, no forced dependencies, no per-call surprises.

**v1 — must have (absence leaves a correctness, safety, or adoptability hole):**
- **Library foundation:** thread safety and reentrancy guarantees on all public APIs (an SDK is called from arbitrary application threads); dependency/classloader isolation with every integration genuinely `<optional>` and a working non-Spring host; Java 8; semver with deprecate-then-remove; fast deterministic startup that does not require a reachable config server; last-known-good rule snapshot on startup.
- **Rate limiting (the module that pays for the SDK):** annotation + programmatic guard API, token-bucket semantics with burst allowance, whitelist/bypass evaluated before quota consumption and audited separately, per-rule fail-close default with per-rule override, a central quota store with atomic compare-and-decrement, automatic local fallback that **exposes that the degradation happened**, and accept/reject-rate + limiter-latency metrics. The decision result is a value object carrying ruleId, ruleVersion, remaining quota, and a machine-readable reason code — not a boolean; the reason code must distinguish `LIMITED_BY_RULE` / `NO_RULE_MATCHED` / `DEGRADED_LOCAL` / `FAIL_CLOSE`.
- **Canary:** priority-ordered matching (whitelist > percentage > hash bucket) with the order part of the contract; hot reload without restart; per-rule fail-open default; stable documented bucketing; a **required** targeting key enforced at publish time; a decision record per evaluation.
- **Cache:** local tier with TTL and size bound (both count and weight); pluggable two-tier interface; per-key single-flight; TTL jitter; O(1) bulk invalidation; hit-rate and load-latency metrics.
- **Management API:** frozen versioned contract; CRUD + publish + rollback as **distinct** operations (an edit must not take effect until published); append-only change audit correlating audit ↔ rule version ↔ decision logs; rule version history (a version is never edited in place); authentication and authorization on the API (an unauthenticated endpoint that changes rate limits is a DoS button); health/readiness reporting *effective* config state.
- **Observability:** one low-cardinality Prometheus namespace; a **dedicated** decision-log channel with a different retention and redaction policy than the business logger; complete decision records; SDK health telemetry; **default masking of sensitive fields at the SDK layer** (R4).
- **Differentiators that must be in v1, not later:** the **unified decision record** (build it first or retrofit five lookalike schemas — OB-3 is the *output* of D1, not a sibling); **queryable degradation state** (a state enum + gauge; Sentinel offers nothing comparable, and Alibaba maintainers concede the cluster HA problem is unsolved); **rule validation before publish** distinguishing `NO_RULES_CONFIGURED` from `RULE_SOURCE_UNAVAILABLE` (both Sentinel's Nacos datasource — empty on parse failure, warn only — and the SCA yaml datasource have shipped this hole); **self-reported overhead telemetry** via `MeterBinder` (pre-empts the first adoption objection from any latency-sensitive adopter).

**v1.x — add after the first real adopters:** unified version history and one-click rollback **spanning** capabilities/services (see the boundary note below); the declared cache-coherence contract plus missed-invalidation recovery (sequence counter + periodic re-sync) — the honest disclosure that local TTL is the maximum duration of incorrectness is itself the maturity signal, and reconciliation is what turns at-most-once into bounded eventual correctness; refined last-known-good policy with per-module alerting; the test-support module (in-memory config source, in-memory quota store, deterministic replay harness) — triggers at the first external contributor or second consuming team; the full diagnostics endpoint; the bounded non-blocking decision-log writer with drop policy and **dropped counter** (if logging is synchronous in v1, this is the first thing to fix); and the restricted expression grammar *only if* an enumerated attribute set proves insufficient.

**v2+ — deferred:** business-semantic caches (each is a separate, individually-specified decision with its own invalidation contract and PII retention rules — never as a batch); distributed local-quota coordination as an alternative to a centralized token server (a research project: Alibaba point at it, and concede it is inaccurate under uneven traffic with deregistration lag); two-tier rate-limit strategies (local chunk + global store) to cut central-store traffic; change preview / dry-run of a candidate rule set against recorded decisions before publish; the console UI (a **separate application** consuming the frozen contract — never a feature of this SDK); hot-reloadable business-logic extension points (this is the "real-time everything" anti-feature in another costume — five narrow module APIs first, because each module has genuinely different failure semantics and one abstraction forces the weakest common denominator).

**Anti-features, recorded so scope creep is recognizable:** a bundled web console; exposing Sentinel/Nacos/Caffeine types in the public API (every internal swap becomes a breaking change for *our* users); the Sentinel Dashboard as the management UI; business-semantic caches in v1; a single global fail-open/fail-close switch; annotation-only enforcement; per-call SpEL/script evaluation; high-cardinality metric labels (user id, PAN, transaction id, raw expression text); synchronous decision logging on the hot path; a generic plugin/bus framework for all modules.

**Boundary note on version history (D4).** The versioned publish unit in the v1 envelope is `RATELIMIT | CANARY`, with `ruleSetVersion` **global-per-DataId rather than per-rule**, precisely so a set is published atomically. v1 therefore already delivers atomic publish + rollback *within* a capability's rule set, which satisfies PROJECT.md:63 and the canary one-click-rollback requirement. What remains in v1.x is a **cross-capability / cross-scope atomic release** spanning multiple services or capability types in one release unit. The cache module is not part of the published rule set at all — its invalidation is a runtime bus, not configuration — so "all five modules" over-counts the config-publishable surface.

### Architecture Approach

A 13-module Maven reactor where **the module split is the coupling-contract enforcement mechanism** — if two concerns share a jar, `bannedDependencies` cannot separate them. Control plane and data plane share **only the rule schema**; no shared jar, process, database connection, or network protocol; Nacos is the entire contract between them. Six patterns carry the design: **compile-once immutable snapshot** (one `AtomicReference` swap, zero allocation/parse/lock on the decision path, rollback is a pointer move, partial application is structurally impossible); **argument extraction budget** (narrow, size-capped, pre-projected `ArgumentSource` — never the object graph, and the one place PAN masking happens for all three capabilities); **cluster gate** (availability as an explicit two-state machine driven by an off-path probe, so a token-server blip costs one probe instead of one timeout per request); **versioned invalidation with reconciliation** (monotonic per-key sequence, out-of-order drops, periodic drift repair, L1 TTL as the hard bound); **immutable decision record on a dedicated channel** (bounded ring buffer, batch writer, channel that is not the host's business logger, drop counts exported so the loss is visible rather than silent); and **degradation as a rule, not a branch** (fail strategy is a required, versioned, auditable field — the API rejects a rule whose fail strategy is not explicitly stated, removing the worst bug class in this domain, a hardcoded fail-open on the authorization line).

**Components (C1–C13):** C1 `foundation-core` (schema, version envelope, `DecisionRecord`, all SPIs, **zero runtime dependencies**); C2 `foundation-expression` (sandboxed compile + evaluate); C3 `foundation-observer` (decision sink, ring buffer, batch writer, meters, health gauges); C4 `foundation-rule` (parse → validate → compile → immutable `RuleSetSnapshot` → last-known-good on disk; **no Nacos, no Spring, no Sentinel** — the highest-leverage isolation decision in the project, because it makes the whole rule engine testable as a plain JUnit test with a `String` snapshot); C5 `foundation-ratelimit`; C6 `foundation-canary`; C7 `foundation-cache`; C8 `foundation-nacos` (**the only module that imports `nacos-client`**); C9 `foundation-autoconfigure` (**the only data-plane module that knows Spring exists** — the Spring firewall); C10 `foundation-starter` (POM only, zero code); C11 `foundation-console-api` (deployable; **depends on C1 only**); C12 `foundation-token-server` (deployable — see R5's amendment); C13 `foundation-samples`; plus `sdk-compat-tests` as a separate consumer module set.

**Hard prohibitions, all build-enforceable:** any data-plane module → `spring-*`; C11 → C2–C9; C11/C12 → SDK instances by any RPC (**Nacos is the only distribution channel** — two distribution paths would have different ordering and delivery guarantees, making rollback ordering unprovable and traceability unprovable); C4 ↔ C7 in both directions (rule staleness and data staleness have different semantics — a shared generic cache would happily serve a stale or invalid rule behind a TTL); C5 ↔ C6 (peers, so canary never forces a rate-limit redeploy and a service wanting canary does not acquire Sentinel transitively); C2/C3 → C5/C6/C7 (leaves must not know their callers — C3 collects from all three paths without any of them depending on another); C3 → the host's business logger; C5 → Sentinel's **global** config (`sentinel.eager`, dashboard, transport settings) — the SDK is a library inside someone else's JVM; C12 → C7/C9.

**Failure semantics worth carrying into requirements:** Nacos failures F1–F9 are enumerated with detection, SDK behavior, business impact, and health signal — including F2 (cold start with no LKG → serve with empty snapshot under capability defaults, with `foundation.rule.loaded=false` and health **DOWN**, loud not silent), F4/F5 (malformed **or semantically wrong** payload → reject the whole payload atomically, keep the previous snapshot in its entirety, validation must cover relationships not just shape), F6 (out-of-order push → discard if `incoming.ruleSetVersion <= current`), F7 (namespace/group misconfiguration → assert at startup, fail loudly, never "start with no rules and look healthy"), and F8 (content-hash mismatch → reject as tamper/corruption detection). Three rules make it work: the hot path never talks to Nacos; last-known-good lives on disk in the SDK's own format (Nacos's client snapshot is an internal cache with no format guarantee, and a **user-maintained** failover file the client never creates) written temp-file + `ATOMIC_MOVE`; rejection is atomic and total. And a list of what the SDK must not do — notably **no TTL on rules** (expiring a rule removes a protection, and a temporary Nacos problem must not become permanent loss of protection) and no synchronous Nacos retry "just to be sure the config is fresh." A `foundation.rule.startup-policy` property offers RESILIENT (default) vs STRICT (fail fast) so the test suite can assert the failure without taking production down.

**Hot-path cost budget (design target, not a measurement):** 8 layers, p99 **< 10 µs total excluding the cluster RTT** — snapshot read ~0, `RoutePlan` lookup < 100 ns, argument projection < 1 µs, expression evaluate < 5 µs (the number that *must* be benchmarked), Sentinel rule lookup ~1 µs, token acquisition dominant in CLUSTER (reported as a separate budget, never conflated) / < 1 µs in LOCAL, decision assembly < 1 µs (do not hand-optimize), `DecisionRecord.emit()` < 1 µs (measured under contention *with a full buffer* — the pathological case is the full-buffer drop path, not the fast path). The three costs that actually bite: the cluster RTT; a full ring buffer under load (must drop and count, never block — a blocked producer cascades into the business thread pool); and expression evaluation under adversarial rules. Cost-control rules: no allocation-heavy work beyond `DecisionRecord` and its pre-sized `extra` map, **no locks anywhere in layers 1–8** (a `synchronized` block invalidates the whole budget under load), no unbounded structures (route-plan LRU, ring buffer, `appliedSeq` map, single-flight map — a Nacos-pushed change must never be able to grow the heap without limit, which is a remote-triggerable OOM), and bounded metric cardinality.

### Critical Pitfalls

1. **P1/P2 — SpEL is a false sandbox on this stack, and third-party sandboxes are opt-in.** Config-delivered expressions are RCE in the business JVM; Spring 5.3.x has no fixed version for the 2026 SpEL advisories and JDK 8 blocks the upgrade; QLExpress security is opt-in with a path to rewrite the security switch from expression context; MVEL has none. *Avoid:* R1 — ban SpEL, default to a grammar-level restricted DSL, run the fixed JDK 8 attack-case matrix under **default** configuration, and keep the SDK's own AST + field + step limits as the *first* line (the engine's sandbox is the second).
2. **P5 — Cluster quota silently amplified by N and drifting.** Default `thresholdType=AVG_LOCAL` computes `count × getConnectedCount(flowId)`; `fallbackToLocalWhenFail` defaults to true; `NO_RULE_EXISTS` from a router/token-server `flowId` mismatch degrades to local counting with no exception and only a `no rules in token server` log line; degradation and success return the same business result, so no business alert fires. *Avoid:* R2 — explicit settings, single-source `flowId`, a post-push rule-consistency probe, reason-classified degrade metrics and alerts, and the effective-quota endpoint. *(Verification: `sentinel_cluster_degrade_total{reason}` non-zero must alert; same rule must reject a visibly different volume after a scale-out if the setting was left at default.)*
3. **P11 — "Out of scope for PCI" is a claim the SDK itself can falsify.** A generic cache launders PAN into L2 and the heap; a decision log launders it into retained records; high-cardinality labels leak it into metrics nobody scrubs. *Avoid:* R4 — `sensitive` guardrail, field allow-lists, label allow-lists, and the P6 sensitive-data path inventory reviewed by adopter security. *(Verification: decision log contains only the allow-listed fields; `sensitive=true` writes nothing to L1; the path inventory exists as a delivery artifact.)*
4. **P15 — Observability deferred means history is un-backfillable.** If the `DecisionRecord` contract and ring buffer do not exist in P1, the P2/P3 decisions have no structured record, and no later phase can reconstruct which rule version was live. Worse, if `ruleVersion` is not an immutable field of the snapshot, P5 can only record "the current version" — and reverse-lookup from current config yields the *wrong* version. *Avoid:* P1 delivers the contract (including `ruleVersion` as an immutable snapshot field, `failStrategy`, `degraded`, `dataLine`) **and** the bounded ring buffer; P2/P3/P4 emit into it in their own acceptance criteria; P5 only delivers transport. *(Verification: no `if (log.isDebugEnabled())` gating on decision records; a drop-count metric exists; the decision path performs zero synchronous I/O.)*
5. **P9 — Version skew propagates to every adopter.** SCA 2021.0.5.0's baseline is Boot 2.6.13, not 2.7.6; in library form the SDK's transitive versions land in the adopter's tree, and the failure surfaces at compile or startup — blocking the ≥2-integration delivery standard. *Avoid:* R3 — Spring firewall + `optional` deps + `@ConditionalOnClass`/`@ConditionalOnMissingBean` + startup version assertion + the two-Boot-version compat tests.
6. **P4 — The custom router can reproduce the Resource explosion it was built to avoid.** If the compile cache or the rule map is keyed on raw expression text or on argument values, cardinality follows *entity* count again: 100k BINs ⇒ 100k compiled expressions, route-plan entries, and decision records, growing with uptime and reproducing only after hours of running. *Avoid:* normalized expression text as the compile-cache key with a bounded LRU (e.g. 1000) and an eviction metric; assert the **invariant** that route-table size equals the number of authored rules and does not grow with runtime or entity count; reference rules by `ruleId` + version in decision records, never inline the expression; differential replacement rather than full rebuild on push; a 100k-BIN cardinality load test. *(This is the single hardest failure to diagnose because it only appears after sustained running.)*
7. **P8 — A Nacos push is a "something changed" signal, not a delivery of payload.** Duplicate and out-of-order callbacks, re-fetch failure inside the callback, config **deletion** (a legal event that, ignored, keeps old rules forever), namespace/group/dataId mismatch (reads the wrong environment's rules **without erroring**), and silent parse-failure fallback all break traceability. *Avoid:* the callback only sets a dirty flag and triggers a debounced re-fetch (~200 ms merge window); validate the **entire** rule set (schema + expression compile) and swap atomically, or reject the whole payload and keep the previous version — never partial; handle deletion explicitly by falling back to built-in static safe defaults, not by keeping old values; assert the namespace/group/dataId allow-list at startup and fail fast; monitor the Nacos snapshot directory's writability (a full disk makes Nacos read its own snapshot and fail, compounding degradation).
8. **P7 — One fail-close default is wrong for the clearing-inbound line.** Covered in R6. *(Verification: the clearing-inbound line's fail policy is hard-coded fail-open; every fail-open rule has a static local fallback quota; the applied `failStrategy` is in the decision record.)*
9. **P10/P14 — Pub/Sub invalidation is at-most-once, and equal L1/L2 TTLs cause stampedes.** A disconnected, restarting, or GC-paused instance misses the invalidation **permanently** and serves stale until TTL — and a common "optimization" removes the L1 TTL entirely, making it permanent. Equal or near-equal L1/L2 TTLs concentrate expiry into a spike that penetrates to the backend in N nodes × the same millisecond. *Avoid:* monotonic sequence on every invalidation with out-of-order drops; enforce `l1Ttl < l2Ttl` with an explicit ratio; TTL jitter (≈ ±10%) seeded by the key hash; **per-key** single-flight (a global lock turns a stampede into a *sequential* one and is worse); distributed lock + double-check for cross-node protection with timeout and safe release; hot-key local residency via soft-TTL + background refresh; a periodic reconciliation task; a per-cache `l1Enabled` switch so a high-consistency caller can bypass L1. Note the symptom that gives it away: L1 hit rate unusually high while L2 QPS stays low, and data that becomes correct after a restart.
10. **P12 — Unstable canary hashing or a bucket-count change re-shuffles users into unverified code.** A missing targeting key falling back to random means the same user flips between branches on every request; changing 100 → 1000 buckets reshuffles nearly everyone, evicting validated users from the canary **and pulling unvalidated users into it**; a per-version salt does the same. *Avoid:* a declared targeting-key precedence with a **"no match, never random"** failure semantic (fail-open to "this rule does not match" plus a metric) — random is a defect, not a fallback; `bucketCount` and the hash salt are **immutable, versioned contract fields** in code, and a rebucket is rejected unless explicitly `allowRebucket`, which is itself audited; nothing but the targeting key enters the hash input; expose a management-API endpoint that computes the moved-in/moved-out proportions for a proposed rebucket so the number, not the intuition, drives the decision. *(FEATURES' strongest supporting evidence: flagd issue #1872 — cross-language bucket divergence where a `Math.abs(Integer.MIN_VALUE)` edge case left a user with **no variant at all**, silently.)*
11. **P13 — Thread-pool timeout as the expression bound poisons the host's pool.** `Future.get(timeout)` does not cancel the task; dozens of slow expressions fill a shared or SDK-internal pool and every evaluation — including healthy ones — then queues and times out. The "isolated" variant with an unbounded queue turns rejection into OOM. *Avoid:* step budget as the primary bound; if a timeout backstop is required, a separate **bounded** pool with `SynchronousQueue` or a small queue and an explicit reject policy (reject = budget exceeded), never an unbounded queue, with per-step interrupt checks; name every pool and export active/queue-depth/reject metrics into SDK health.
12. **Retrofit risks already folded into the phase seams:** cached **decisions** instead of cached data (N concurrent requests share one quota check, so the effective limit becomes N× the configured one and the limit stops being a limit); partial application of a rejected config (a rule set nobody authored, nobody tested, nobody can roll back to); rollback by re-pointing a version (instances already holding v9 diverge, and a decision record's version no longer identifies content — traceability, the core value, is destroyed; rollback must be a **forward write** as a new monotonic version, with `409 SCHEMA_INCOMPATIBLE` when the schema has moved on rather than a silent coercion); the SDK assuming it owns the application (no global Sentinel config, no global interceptor, no writes to the host's logging config, everything opt-in under `foundation.*`); the business logger as the decision channel; reporting healthy with no rules loaded.

---

## Implications for Roadmap (Build Order and Phase-Seam Map — carried forward from ARCHITECTURE.md; do not re-invent)

### Maven reactor order

Declaration order matters: Maven breaks ties among independent modules by declaration order, and a broken tie means flaky build ordering. This is the compile-time expression of the coupling contract; a cycle here is a design error, not something to work around with `<exclusions>`.

```
1  foundation-parent (BOM)           no code; pins every version; holds the enforcer rules
2  foundation-core          (C1)     nothing depends on it; it must have no dependencies of its own
3  foundation-expression    (C2)  ┐
4  foundation-observer      (C3)  ├─ depend only on core, mutually independent
   foundation-console-api   (C11) ┘  (console-api placed here proves the control plane compiles with
                                        nothing but the schema, and parallelizes in CI)
5  foundation-rule          (C4)     needs expression + record type; does NOT need Nacos (it declares
                                        the RuleSource interface itself)
6  foundation-ratelimit     (C5)  ┐
7  foundation-canary        (C6)  ├─ peers; each needs rule; cache takes a soft dependency on observer
8  foundation-cache         (C7)  ┘  (if ratelimit ever needs cache, the boundaries are wrong)
9  foundation-nacos         (C8)     needs rule's RuleSource SPI; placed after the capabilities so the
                                        Nacos dependency lands at the outside of the data plane
10 foundation-token-server  (C12)    needs the token SPI + token store; a deployable
11 foundation-autoconfigure (C9)     the only module that may see Spring; last among libraries
12 foundation-starter       (C10)    POM only, zero code
13 foundation-samples       (C13)  ┐ consumers — must build last, because they are the only modules
   sdk-compat-tests                  ┘ that can catch a broken public surface
```

### Phase seams (P1–P6, names adopted from PITFALLS.md and identical to ARCHITECTURE.md's map)

**P1 规则内核与表达式沙箱 — Rule kernel + expression sandbox**
- **Rationale:** everything else consumes compiled artifacts from it, and the items it establishes are un-backfillable.
- **Delivers:** `core` schema + version envelope (two independent version numbers: `schemaVersion` bumped only on incompatible wire change, `ruleSetVersion` monotonic and rejected if it goes backwards) + the **`DecisionRecord` contract** + `expression` sandbox (R1) + `rule` compiler/validator/`RuleSetSnapshot`/last-known-good disk store + **the full 13-module reactor skeleton and the enforcer rules**.
- **Boundaries established:** the Spring firewall; `foundation-nacos` as an adapter; `rule` free of infrastructure.
- **Features:** D1 (unified decision record), D3 (validation), XX-1/2/3/4/5/6, MG-4's data model.
- **Pitfalls addressed:** P1, P2, P3, P4, P8, P9, P13, P15.
- **Research flag:** YES — the single most research-needing phase. Not because the architecture is unclear but because **P1 cannot sign off until the fixed attack-case matrix has been run on JDK 8 under default configuration and the step-budget behavior is demonstrated**; the DSL-first decision reduces but does not remove this (the self-built parser/evaluator needs its own fuzzing).

**P2 限流执行与故障策略 — Rate-limit execution + failure policy**
- **Rationale:** the module that pays for the SDK, and the one carrying the two silent-quota risks.
- **Delivers:** the 6-layer hot path, Sentinel rule installer + `FlowRuleAssembler`, `ClusterGate` + probe, the token layer (Redis Lua per R5), fail-close default with per-rule override **and data-line parameterization (R6)**.
- **Boundaries established:** `ratelimit` ↔ `canary` separation; the token layer as a separate concern from the cache.
- **Features:** RL-1..RL-8, D2, D8, the three separate reject/degrade counters.
- **Pitfalls addressed:** P5, P6, P7 (ratelimit half), P4 (real-traffic regression of the cardinality invariant).
- **Research flag:** YES — R5 removes the "which token implementation" question, so the research is now narrower and concrete: **measure** the Lua script's p50/p99 on the actual Redis topology (STACK's numbers are LOW-confidence community figures), establish ≥3× headroom over measured peak, and benchmark layer 4 (expression) and the ring buffer under a full buffer. The **JMH harness lands here** because it must measure the hot path while it is being built; the production-shaped report is a P6 deliverable.

**P3 灰度评估器 — Canary evaluator**
- **Rationale:** peers with rate limiting; makes "one governed control plane" true rather than aspirational.
- **Delivers:** whitelist > percentage > stable hash, immutable `bucketCount`, declared targeting-key precedence, fail-open default with per-rule override, selected version recorded in the decision, and a management-API endpoint computing rebucket impact.
- **Pitfalls addressed:** P7 (canary half), P12.
- **Research flag:** MODERATE — not a research gap but a **decide-before-coding** item: targeting-key resolution order and the rebucket-rejection rule must be fixed first, and the three data lines may need different orders.

**P4 两级缓存 — Two-level cache**
- **Rationale:** cheap, high-frequency, and the module where the coherence contract is easiest to state honestly at v1.
- **Delivers:** L1/L2, three protections (penetration / stampede / avalanche), multi-dimension keys, key-only pub/sub with sequence guard, reconciliation task, `sensitive` flag blocking L1.
- **Boundaries established:** `cache` ↔ `rule` separation; the cache never sees rule or rate-limit types.
- **Pitfalls addressed:** P10, P14, P11 (guardrail half), P12-adjacent key-stability.
- **Research flag:** MODERATE — the two-level consistency protocol (PROJECT.md:113) is still genuinely open and must be settled before code; the v1 sensitive-data guardrail design needs a sign-off decision on the `sensitive` default (R4).

**P5 决策可观测性 — Decision observability**
- **Rationale:** the transport, not the contract.
- **Delivers:** sink wiring, batch writer, meters, health contributors, offline sample consumer.
- **Boundaries established:** the observer collects from all three paths without any capability depending on another.
- **Pitfalls addressed:** P15 (transport half), P11 (field allow-list half), P9 (version assertion surface).
- **Research flag:** NO — standard pattern, low research need. **The risk is retrofit, not design:** the `DecisionRecord` field set and the ring buffer were fixed in P1, so P5 wires transport only. If P1 drifted, P5 cannot recover. Its one open question is ARCHITECTURE open question 6 — whether the offline decision consumer is a sample service or a new component that arguably needs its own sub-application.

**P6 管理 API 与接入验证 — Management API + integration validation**
- **Rationale:** the contract is frozen here for a separate sub-application to build against, so it is the last thing that can still be changed cheaply internally and the first thing that becomes externally expensive.
- **Delivers:** `console-api` CRUD + release/publish + canary rollout + rollback + audit; the frozen `/api/v1` contract with tenant/permission placeholders; `sdk-compat-tests` on both Boot versions; ≥2 real service integrations; the latency report; the sensitive-data path inventory (R4).
- **Pitfalls addressed:** P9 (compat verification), P11 (security review), P7 (real data-line validation), P8 (environment cross-talk drill).
- **Research flag:** MODERATE — needs a version-compatibility matrix and a security-path review before delivery, and the v1 tenancy/permission placeholders need an **explicit risk statement, not silence**: the console sub-application will build a UI for multi-tenant and role-based access against an API that enforces neither, and that gap belongs in the P6 handoff documentation, not a code comment.

### Phase Ordering Rationale

**Sequencing consequence the roadmap must respect (synthesis, from the dependency graph):** P3 and P4 deliver capabilities whose rules are *written* by P6's publish path. Before P6 exists, the only way to put canary or rate-limit rules into an adopter is a direct Nacos write — which bypasses validation, versioning, audit, and rollback, exactly as PITFALLS notes for expression validation ("API-layer validation is not a security boundary — a direct Nacos write reaches the same code"). Therefore: P1–P5 may be developed and exercised against direct Nacos writes, but **canary and rate-limit rules must not reach a production adopter before P6's governed publish path exists.** This is consistent with the delivery standard (≥2 integrations happen in P6).

**Optional decomposition (roadmapper's call, seam preserved):** P1 as mapped is large — full reactor + enforcer + core + expression + rule + the `DecisionRecord` contract and ring buffer. Splitting it into P1a (reactor + enforcer + core + expression + rule) and P1b (observer contract + ring buffer + meter contract) is legitimate **only if** every item on the cross-phase-invariants list still lands in P1 as a whole. The invariant list, not the phase name, is the contract.

### Cross-phase invariants that must not be traded away later

| Invariant | Established in | Cost if broken later |
|---|---|---|
| `DecisionRecord` field set | P1 | Decision history becomes non-joinable; already-recorded data cannot be enriched, and history is un-backfillable |
| Version envelope + monotonic `ruleSetVersion` | P1 | Traceability and rollback both lose their foundation |
| Copy-forward rollback semantics | P1/P6 | Every historical version becomes ambiguous; post-incident analysis becomes impossible |
| Module boundaries + enforcer rules | P1 | Requires a multi-module split under time pressure, mid-milestone |
| Last-known-good on disk with atomic writes | P1 | A cold start after a Nacos outage becomes an outage |
| `ClusterGate` as a state, not try/catch | P2 | A token-server outage becomes a latency incident on the authorization line |
| Pre-registered bounded meters + bounded tag vocabulary | **P1** *(amended — see below)* | Cardinality is very hard to walk back once dashboards depend on the labels |
| `/api/v1` contract frozen | P6 | The console sub-application builds against a contract that then changes |

### Reconciliation of PITFALLS' phase numbering with ARCHITECTURE' build order

**They agree.** PITFALLS proposed P1–P6 and explicitly asked that they be adopted into the ROADMAP; ARCHITECTURE independently derived the same six seams and states that its phase names match PITFALLS'. Every pitfall-to-phase mapping in PITFALLS lands inside a seam that ARCHITECTURE also assigns (P1 → P1/P2/P4; P2 → P2; P4 → P1 + P2 regression; P7 → P2 + P3; P8 → P1 + P6; P9 → P1 + P6; P10 → P4; P11 → P4 + P5 + P6; P12 → P3; P13 → P1; P14 → P4; P15 → P1 + P5 + per-capability emit points). No conflict required adjudication. **Two amendments were needed:**

1. **Pre-registered bounded meters — P3 is too late.** ARCHITECTURE's own invariant table places this in "P3 (first capability that emits)", but the meter registry lives in C3 `foundation-observer`, and C3's *transport* work is a P5 deliverable. Two capability phases (P2 and P3) would therefore emit through an interface that does not exist yet. **Amended: the meter contract and the bounded tag vocabulary are a P1 deliverable** (they travel with the `DecisionRecord` contract and the ring buffer, which PITFALLS P15 already places in P1); **P5 delivers the sink transport, the batch writer, and the health contributors.** This also front-loads R4's metric-label allow-list, which is cheaper to enforce as a fixed vocabulary at contract time than to retrofit onto two capabilities' worth of existing meters.
2. **The data-line fail-policy parameterization (R6) is an amendment to P2/P3, not a new phase.** PROJECT.md's constraint is preserved as a per-rule mechanism; what the seams do not yet state is that the *defaults* must be parameterized per data line with a hard-coded exception for the clearing-inbound line. Add it to P2 and P3 acceptance criteria and verify it in P6 with real data lines.

Two smaller alignments worth carrying: the meter/cardinality rule and the "no synchronous IO on the decision path" rule are the *same* control seen from two directions (Anti-Pattern 9 and Pattern 5), and the sensitive-data work is explicitly a **three-phase** obligation (P4 cache guardrail + P5 log field allow-list, designed together, reviewed in P6) rather than a single deliverable.

### Research Flags

The per-phase `Research flag:` line under each seam above is authoritative. Consolidated here for the roadmapper:

**Phases needing `--research-phase` during planning:**
- **P1:** **YES — the highest research need in the roadmap.** Not architectural ambiguity but an unverifiable sign-off claim: P1 cannot pass until the fixed JDK 8 attack-case matrix has been run **under default configuration**, the self-built parser/evaluator has been fuzzed, and `EvaluationBudgetExceeded` is demonstrably reachable by test. Needs `--research-phase 1`. Secondary need: the Boot 2.6.13 compat smoke test.
- **P2:** **YES.** R5 removed the design question, so the research is measurement, not design — Lua p50/p99 on the actual Redis topology (STACK's numbers are LOW-confidence), ≥3× headroom over measured peak, and JMH baselines for expression evaluation and a full ring buffer. Needs `--research-phase 2`.
- **P4:** **MODERATE, at scoping rather than planning.** PROJECT.md:113 is genuinely open (which convergent coherence protocol, not Redis vs MQ). This is a decide-before-coding gate, not an API-research gap; a targeted research pass on at-most-once invalidation and reconciliation is warranted.

**Phases with decide-before-coding items but no research flag:**
- **P3:** **MODERATE** — targeting-key precedence order and the rebucket-rejection rule must be fixed before code, and the three data lines may need different orders. Research on flagd bucketing is already done and in PITFALLS P12.
- **P6:** **MODERATE** — needs a version-compatibility matrix and a security-path review before delivery. These are verification tasks, not unknown technology.

**Phases that should skip research-phase:**
- **P5:** **NO.** Standard batch-writer / bounded-queue / `MeterBinder` patterns, heavily documented. The risk is *retrofit*, not design: the contract it wires was fixed in P1, so if P1 drifted P5 cannot recover it. Judge this phase against P1's output, not against new research.

---

## UNVERIFIED Register

Everything the researchers flagged as unverified, unresolved, or explicitly deferred. **The roadmap must not inherit these as facts.**

| Item | Status | Where it must be closed |
|---|---|---|
| Every latency figure in STACK.md (Redis Lua p50 ≈ 0.3 ms / p99 ≈ 1.5 ms same-AZ; 53–274 µs in other setups) | **LOW / UNVERIFIED** — community benchmarks, mutually inconsistent, topology/payload/client sensitive. Treated as order-of-magnitude planning input only | P2, with JMH against the real Redis. No SLO may cite STACK.md |
| STACK.md overall: version pins and API surfaces | **HIGH** (Maven Central POMs + shipped bytecode); latency figures **MEDIUM** | Latency replaced by JMH |
| AviatorScript `CVE-2021-41862` (CVSS 9.8), historical advisory with no fix version noted; affected range ≤ 5.2.7 | **MEDIUM** (vendor advisory). 5.4.4 sits outside the stated range | Re-verify per-item before enabling Aviator (R1) |
| SpEL CVEs `CVE-2026-59283`, `CVE-2026-41849/41850/41851/41852`; OSV 5.3 branch `Last Affected: 5.3.39` with no fixed version | **MEDIUM** (vendor advisory) | Does not block v1 (SpEL banned), but must be re-checked before any framework upgrade |
| Sentinel namespace leaky-bucket ceiling, reported default **30,000 QPS** | Reported default, **must be measured in-environment** | Moot under R5 (Redis); still relevant to the ≥3× headroom argument for the Redis path |
| Sentinel cluster-mode timeout behavior (20 ms request / 10,000 ms connect, blocking on the calling thread) | **HIGH for structure** (verified in 1.8.6 bytecode), **MEDIUM for timing consequence** | Timing consequences need the JMH benchmark |
| Sentinel issue-based claims (#2457 silent local fallback, #1639 30k ceiling + averaged thresholds, #1670 `NO_RULE_EXISTS` semantics, #2616 token-server restart timeout spike) | Issue-tracker evidence; primary but not vendor-confirmed | Accepted as design drivers; verify in P2 by construction (the Redis path removes the mechanism) |
| Aviator metaspace behaviour under Nacos-push churn (thousands of distinct expressions incl. malformed) | **UNVERIFIED** — the `validate()`-then-`compile(..., cached)` design is the mitigation, not a measurement | P1 smoke test. Largely moot for the DSL default; mandatory if Aviator is ever enabled |
| The p99 < 10 µs SDK overhead budget | **Design proposal, not a finding**; ARCHITECTURE open question 5 says it needs sign-off | Needs a user decision before P2 benchmarks can be judged pass/fail |
| `ClusterGate` probe interval, L1/L2 TTL ratios, buffer sizes | **Design proposals, not findings** | Tuned in P2/P4; not roadmap inputs |
| The entire Management API contract shape | **Design judgment, not sourced**; MEDIUM | Frozen in P6; the *design rules* (versioned path, no `tenantId` in path, DTOs not entities, idempotency, ETag, RFC 7807) are the durable part |
| The two-level cache consistency protocol | **Open by design** (PROJECT.md:113); the recommended convergent shape is designed but unratified | P4, before code |
| Canary targeting-key precedence across the three data lines | **Open**; may differ per line | P3, before code |
| Latency budget value | **TBD in PROJECT.md:96** | User decision at requirements scoping (see corrections) |
| Where the offline decision consumer lives | **Open** — sample service, or a new component that arguably needs its own sub-application | P5/P6 |
| Whether the token server is deployed at all for the two target services | **Open** — if local-only initially, P2's cluster half is **deferred, not cancelled** | P2 scoping |
| `CacheSpec.sensitive` default (deny-by-default vs caller-declared) | **Open** — PROJECT.md is silent; PITFALLS prescribes caller-declared | P4 sign-off (R4) |
| The `Idempotency-Key` header pattern | **MEDIUM** — still an IETF draft; treat as a convention, not a standard | P6 contract |
| The expression-engine benchmark baseline (`howiefh/expression-engine-benchmark`) | **MEDIUM** — third-party, not independently reproduced | P1, if Aviator is ever enabled |

---

## Confidence Assessment

| Area | Confidence | Notes |
|---|---|---|
| Stack | **HIGH** for version pins and API surfaces; **MEDIUM** for latency | Every pin was read from Maven Central POMs or verified in shipped bytecode (Caffeine 2.9.3 major 52, Sentinel 1.8.6 major 52, `nacos-client` 2.2.0's only >52 entry being a multi-release `module-info.class` that JDK 8 ignores). The `nacos-client` 1.4.2-vs-2.2.0 trap is a **POM fact**, not a guess. Latency is unverified by admission |
| Features | **HIGH** for the feature landscape and the v1/v2 boundary; **MEDIUM-LOW** for third-party ecosystem behavior | The landscape derives from PROJECT.md's own requirements plus verified library behavior. The ecosystem claims (Nacos version semantics, flagd bucketing divergences, Sentinel datasource internals) rest on official docs and issue trackers, cross-checked but not vendor-confirmed. **One FEATURES recommendation is overridden** (expression deferral → v1, per R1) |
| Architecture | **HIGH** on component boundaries, coupling contract, Nacos failure semantics, versioning model, dependency isolation; **MEDIUM** on the control-plane API shape; **LOW** on the token-service implementation | The high-confidence claims are derived from verified source/POM/docs. The API shape is design judgment; the token implementation was deliberately left open and is now resolved by R5 |
| Pitfalls | **HIGH** for Sentinel / Nacos / Spring Boot behavior; **MEDIUM** for expression-engine and CVE conclusions | PITFALLS' own header states this split. The MEDIUM items are the engine choice and the CVE set; the R1 resolution routes around the weakest evidence by not depending on any third-party sandbox |

**Overall confidence: MEDIUM-HIGH.** The architecture, the boundaries, the failure semantics, the version pins, and the version-traceability mechanism are solid enough to build a roadmap on. The three things that were genuinely undecided — the expression engine, the cluster token layer, and the cluster-quota semantics — are now resolved decisively, each with its reversal condition stated. The remaining uncertainty is concentrated in **measurements** (all latency), **four decisions that need a human** (latency budget number, cache consistency protocol, canary targeting-key precedence, `sensitive` default), and **CVE/engine evidence that PITFALLS itself rates MEDIUM**.

### Gaps to Address

- **All latency numbers are unmeasured.** The performance budget is a delivery requirement (PROJECT.md:96) and the hot-path budget is a design proposal. Until JMH + an in-environment load test run, no SLO, no review sign-off, and no claim about the authorization line's P99 is defensible. → P2 builds the harness; P6 publishes the report with per-component p50/p95/p99/p999 splitting, including a **worst-case expression** case, and reporting the cluster RTT separately from SDK overhead.
- **The Boot 2.6.13 compatibility claim is untested.** R3 concludes the skew is tolerable; that conclusion is currently an argument from a version matrix, not a measurement. → P1: the 2.6.13 smoke test as an exit criterion. P6: the full matrix in the integration doc.
- **The expression engine's security argument is the weakest-evidence area, and it is also the highest-blast-radius area.** → P1 must run the fixed attack-case matrix on JDK 8 **under default configuration** and archive it, plus fuzz the self-built parser/evaluator, plus demonstrate `EvaluationBudgetExceeded` is reachable by test. Do not sign off P1 on a document review alone.
- **The "SDK does not handle PAN" invariant has no verification mechanism today.** → Add the sensitive-data path inventory as an explicit P6 deliverable with a named reviewer, and make the two allow-lists (log fields, metric labels) testable: a test that asserts no field outside the allow-list can reach a decision record or a meter tag.
- **The data-line taxonomy is not yet a first-class concept in the data model.** R6's fix requires the rule schema to carry a data-line dimension, and PROJECT.md:77 says the business lines are still being mapped. → Resolve at requirements scoping: is data line a rule field, a resource-prefix convention, or a `serviceScope` value? The rule schema and the version envelope are frozen in P1, so this cannot be deferred past P1.
- **Multi-tenancy is a reserved slot, not an implementation.** PROJECT.md:72 keeps isolation logic for the console sub-application, and ARCHITECTURE states the risk plainly: the console will build a UI for multi-tenant and role-based access against an API that enforces neither. → P6 handoff documentation must state this in words, with the v1 behavior list (single default tenant, single service account, audit still records a principal).
- **The decision-log retention story is incomplete.** PCI DSS 10.7 implies a one-year audit retention; FEATURES notes one managed Nacos offering documenting a 30-day config-history cap. The SDK's own decision channel is a separate artifact, but its default retention must be an explicit, documented property of the sink configuration. → P5, coordinated with R4.
- **Two open architectural questions remain genuinely open and are not resolvable by research:** the offline decision consumer's home (P5/P6) and whether a token service is deployed at all for the initial two services (P2 scoping). → Scope explicitly; do not let them default silently.
- **One requirement is effectively a candidate for removal, not just restatement:** "real PCI DSS certification process" is out of scope and stays out — but if R4's guardrails are implemented, the honest reason is "we provide safe defaults and a reviewable data-path inventory, and we do not certify." If the user wants the certification later, the guardrails are the prerequisite, not an alternative.

---

## Sources

Consolidated from the four research documents. Confidence tiers as labeled there.

### Primary (HIGH confidence)
- `spring-cloud-alibaba-dependencies:2021.0.5.0` POM — `sentinel.version=1.8.6`, `nacos.client.version=2.2.0`, managed `sentinel-*` artifact list
- `spring-boot-dependencies:2.7.6` POM — Caffeine 2.9.3, Micrometer 1.9.6, Lettuce 6.1.10.RELEASE, Jackson 2.13.4.20221013, SLF4J 1.7.36, Logback 1.2.11, JUnit 5.8.2
- `sentinel-datasource-nacos:1.8.6` POM — the `<nacos.version>1.4.2</nacos.version>` trap
- Shipped bytecode of `sentinel-core:1.8.6`, `sentinel-cluster-client-default:1.8.6`, `nacos-client:2.2.0`, `aviator:5.4.4` — `ChannelPromise.await(getRequestTimeout())`, `requestTimeout=20` / `connectTimeout=10000` / port 18730, `ParamFlowRule` has no expression field, Aviator's sandbox feature set
- Spring Boot 2.7.6 release notes (Spring Framework 5.3.24) and auto-configuration reference — `spring.factories` in 2.7; `repackage` is for executables
- Sentinel cluster flow control docs + `FlowRuleManager` source — `NO_RULE_EXISTS`, `fallbackToLocalOrPass`, `fallbackToLocalWhenFail` default, `calcGlobalThreshold` averaging, previous-version rules replaced not merged
- Sentinel `ParamFlowRule` docs — per-entity counting without per-entity resources
- Nacos publish/subscribe/listen docs and the Java client SDK spec — the callback signals change, content must be re-fetched; `namespaceId → groupName → dataId`; content versioned by md5
- Nacos config local-cache/redo spec — read priority and the user-maintained failover file the client never creates
- Nacos console manual / open-API guide — config version history, one-click rollback, publishing history with `srcUser`/`srcIp`; `srcUser` populated only when auth is enabled
- `maven-enforcer-plugin` `bannedDependencies` (including the `<includes>` exemption-list semantics) and `dependencyConvergence`
- AviatorScript repository — 5.4.4 sandbox mode feature set, `validate()`, LRU expression cache, and the 5.4.3 → 5.4.4 sandbox escape fix
- Spring Cloud Alibaba version matrix — the 2021.0.x ↔ Boot 2.6.x/2.7.x mapping; SCA 2021.0.5.0 release notes
- Cloudflare Flagship percentage rollouts — missing `targetingKey` ⇒ random bucket; why re-bucketing mid-rollout is unsafe
- RFC 7807 (`application/problem+json`) and RFC 7232 (`ETag` / `If-Match`)
- PCI DSS v4.0 / PCI DSS Quick Reference Guide — Req 3.4 (PAN unreadable anywhere stored, including logs), Req 10.2/10.3/10.5/10.6/10.7 (audit content, anti-tamper, review, one-year retention)
- Cloudflare `java-redos` query help — ReDoS / catastrophic backtracking
- Project-internal `docs/adr/ADR-003-telemetry-out-of-process.md` — zero network I/O on the business path, bounded buffering, lossy-but-counted
- LMAX Disruptor — bounded ring buffer, producers must not block
- Project-internal `PROJECT.md` — module scope, per-module fail defaults, masking requirement, reserved tenant/permission slots, Java 8, out-of-scope console

### Secondary (MEDIUM confidence)
- Sentinel issues #2457 (silent local fallback, `no rules in token server`), #1639 (~30k QPS namespace ceiling, averaged thresholds, bucket leakage), #1670 (`NO_RULE_EXISTS` semantics), #2616 (token-server restart timeout spike), #2267 (NacosDataSource empty-on-parse-failure, single-thread `DiscardOldestPolicy` dropping intermediate revisions)
- Sentinel PR #3407 — `windowIntervalMs > 1000ms` makes cluster counting inaccurate (keep the window at 1s)
- Nacos issues #8448 and Sentinel issue #1222 — production `NoSuchMethodError` from the `sentinel-datasource-nacos` → `nacos-client:1.4.x` conflict
- flagd `fractional` operation spec and the High-Precision Fractional Bucketing ADR (amended 2026-01-29) — 32-bit hash over `Int32.MAX_VALUE`, ~0.00000005% granularity, ~25–35% less reassignment on weight change
- flagd issue #1872 (closed) — cross-language bucket divergence: `2^31` vs `2^31-1` divisors, `float32` truncation, unsigned `u32::MAX`, and the `Math.abs(Integer.MIN_VALUE)` case that left a user with no variant
- flagd issue #1788 (open, unchecked P2) — "handle missing bucketing keys gracefully" still unresolved, which is why missing targeting keys are rejected at publish time
- Central quota store failure modes — atomicity via single-script execution, clock skew, fixed-window thundering herd, replica promotion with lost counters
- CoCache and hybrid two-level cache patterns — at-most-once pub/sub invalidation, sequence-counter re-sync, TTL jitter, stampede
- QLExpress README — 3.x default-insecure, QLExpress4 4.1.3 isolation defaults
- `spring-boot-starter-alibaba-sentinel` POM — transitively includes the cluster **server** artifact
- Redis Lua rate limiting — atomicity via a single script, one round trip
- `draft-ietf-httpapi-idempotency-key-header` — a convention, not a standard
- Spring Boot auto-configuration docs, Micrometer registry/filter docs, Eclipse API-evolution guidance, Revapi/japicmp, GitHub `spring-redis-hybrid-cache`, Lettuce and Caffeine repositories
- CVE-2026-59283 and CVE-2026-41849/41850/41851/41852 (SpEL) — **MEDIUM**, vendor advisories; AviatorScript `CVE-2021-41862` (affected ≤ 5.2.7); MVEL `CVE-2023-50571`

### Tertiary (LOW confidence — planning input only, must be measured)
- Redis latency figures (~0.3 ms p50 / ~1.5 ms p99 same-AZ; 53–274 µs in other setups) — community benchmarks, mutually inconsistent, topology/payload/client sensitive. **No SLO may cite these**; P2 replaces them with JMH against the real Redis (see UNVERIFIED register).
- `howiefh/expression-engine-benchmark` — third-party engine comparison, not independently reproduced. Only relevant if Aviator is ever enabled via ADR (R1).
- Design proposals, not findings: the p99 < 10 µs hot-path budget, the `ClusterGate` probe interval, the L1/L2 TTL ratios, the ring-buffer sizes, and the Management API contract shape. These are inputs to planning, not evidence.

---
*Research completed: 2026-09-29 · Synthesized from STACK.md, FEATURES.md, ARCHITECTURE.md, PITFALLS.md, and PROJECT.md*
*Ready for roadmap: **yes** — six phases (P1–P6) with boundaries, per-phase invariants, and research flags. Six requirement corrections and one added conflict (R6) need user confirmation before requirements are scoped.*

