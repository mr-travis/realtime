# Feature Research

**Domain:** Embedded in-process Java foundation SDK — rate limiting, traffic canary, cache, management API, and observability for card authorization / ledger / clearing services
**Researched:** 2026-09-29
**Confidence:** MEDIUM (feature landscape HIGH; third-party ecosystem behavior MEDIUM–LOW — see Sources)

> **Scope note.** This document covers the *feature* landscape for the SDK described in
> `.planning/PROJECT.md`. It is a greenfield SDK, so "competitor analysis" compares the
> upstream libraries the SDK wraps (Sentinel, Nacos, Caffeine, Micrometer, OpenFeature/flagd)
> and the patterns adopters assemble by hand today. The SDK's competitive claim is not new
> algorithms — it is **one governed control plane over five mechanisms with a single audit,
> version, and degradation model.**

---

## Feature Landscape

Features are grouped by module and prefixed so dependencies are unambiguous:

| Prefix | Module |
|--------|--------|
| `RL` | Rate limiting |
| `CN` | Canary / traffic management |
| `CA` | Cache |
| `MG` | Management API |
| `OB` | Observability |
| `XX` | Cross-cutting (library concerns) |

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = the SDK feels incomplete or unsafe to adopt.

#### Rate Limiting (RL)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| RL-1 Annotation injection point | Callers expect `@RateLimit("auth")` or equivalent on the method they are protecting; a programmatic-only API reads as unfinished. | MEDIUM | Annotation is a *convenience* surface only. Spring AOP is proxy-based, so **self-invocation silently bypasses the advice** — a limiter that only works through the proxy is a landmine. A programmatic guard API must exist and the annotation must be a thin delegation to it. `[HIGH]` |
| RL-2 Quota decision returning allow/reject + reason | A boolean hides the reason; card-auth callers must log why a request was refused. | LOW | Decision is a value object, not a `boolean`. Carries ruleId, ruleVersion, remaining quota, and a machine-readable reason code. |
| RL-3 Token-bucket semantics with burst allowance | Callers need both a sustained rate *and* a burst budget; a fixed window is visibly wrong to anyone who has been rate-limited by it. | MEDIUM | Fixed windows produce a same-instant reset **thundering herd**; continuous refill avoids it. |
| RL-4 Whitelist / bypass rules | Ops always needs an "internal callers are exempt" escape hatch. | LOW | Whitelist must be evaluated before quota consumption and must be audited separately (bypass is a security-relevant event). |
| RL-5 Per-rule fail-close default with per-rule override | The *stated project default* is fail-close; anything less is a correctness bug on the auth path. | LOW | Make the default a library invariant, not a config value that can be globally flipped to fail-open. |
| RL-6 Central (distributed) quota store | Horizontal scale means local counters over-admit; a shared counter is the whole point of rate limiting in a multi-instance deployment. | MEDIUM | Read-compare-decrement must be atomic — a single Redis-side script, not GET-then-INCR. |
| RL-7 Automatic local fallback when the central store is unavailable | A limiter that fails closed *and* takes the whole authorization path down with it is an outage amplifier. | MEDIUM | Degrade to local single-node limiting and **expose that the degradation happened**. See RL-D1. |
| RL-8 Metrics for accept rate, reject rate, and limiter latency | Any service owner will ask for these on day one. | LOW | Register with Micrometer behind a stable namespace prefix so hosts can filter. `[MEDIUM]` |

#### Canary / Traffic Management (CN)

| Feature | Why Expected | Comparison / routing tooling is table stakes; a canary that cannot explain itself is not deployable in payments. | MEDIUM | Priority whitelist, then percentage, then hash bucket. |
| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| CN-1 Priority-ordered rule matching (whitelist > percentage > hash) | Callers expect deterministic precedence. | MEDIUM | Order is part of the contract; changing it silently re-routes traffic. |
| CN-2 Hot rule reload without restart | Restarting a ledger service to change a 1% canary is unacceptable. | MEDIUM | Depends on a durable config source (Nacos) and a listener-based refresh. `[MEDIUM]` |
| CN-3 Default fail-open with per-rule override | The *stated project default* is fail-open. Canary must never become an unintended outage cause. | LOW | Per-rule override, never a global toggle. |
| CN-4 Stable, documented hash bucketing | Traffic split must be reproducible across instances and across languages. | MEDIUM | See CN-D1 — the algorithm is a **versioned contract field**, not an implementation detail. |
| CN-5 Required targeting key | Without a stable identity, a percentage rule degenerates to a coin flip. | LOW | Missing targeting key must be a **validation error at publish time**, not a silent random assignment. `[MEDIUM]` |
| CN-6 Decision log for every routing evaluation | Payments disputes require reconstructing "why did this transaction go to variant B". | MEDIUM | Same decision-record shape as RL. |

#### Cache (CA)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| CA-1 Local (in-process) cache with TTL and size bound | Every Java service already has a local cache; an SDK that doesn't provide one will be wrapped in one externally. | LOW | Bound both count and weight; unbounded local caches are a memory incident. |
| CA-2 Pluggable two-tier cache interface (local + distributed) | Multi-instance deployments need a shared tier; the abstraction must not force Redis. | MEDIUM | v1 ships the abstraction + local implementation. |
| CA-3 Per-key single-flight (cache stampede protection) | A popular key expiring under load is the classic cache failure. | MEDIUM | Per-key, not global — a global lock converts a simultaneous stampede into a *sequential* one and is worse. `[MEDIUM]` |
| CA-4 TTL jitter | Without jitter, identical TTLs written in one deploy batch expire in one spike. | LOW | This is the most commonly missed trigger — not the hot key, the bulk write. `[MEDIUM]` |
| CA-5 O(1) bulk invalidation | Callers invalidate a merchant, a ledger account, or a rule set; per-key delete loops are unusable at that scope. | MEDIUM | Generation-counter key prefix is the cheap implementation. |
| CA-6 Hit-rate and load-latency metrics | The only way to know a cache is working. | LOW | Micrometer counters + timers. |

#### Management API (MG)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| MG-1 Frozen REST contract (OpenAPI, versioned) | Consumers build against it; changing it silently is a breaking change. | MEDIUM | Freeze the contract, not the implementation. |
| MG-2 Rule CRUD + publish + rollback | Manual console editing is the status quo and is unaudited. | HIGH | Publish is a distinct operation from edit — edits must not take effect until published. |
| MG-3 Change audit (who, when, what diff, which release) | Required for any change to rate limits on the authorization path. | MEDIUM | Append-only. Correlate audit entry ↔ rule version ↔ decision logs. |
| MG-4 Rule version history | Rollback is impossible without it. | MEDIUM | Immutability is the point: a version is never edited in place. |
| MG-5 Management API authentication + authorization | An unauthenticated endpoint that changes rate limits is a DoS button. | MEDIUM | The API is a control plane; it holds more power than any business endpoint. |
| MG-6 Health / readiness endpoint for the management API | Orchestrators need it; ops needs it during rollout. | LOW | Report *effective* config state, not just process liveness. |

#### Observability (OB)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| OB-1 Prometheus metrics for all five modules | The project's stated telemetry target. | MEDIUM | One namespace, stable metric names, low-cardinality tags. |
| OB-2 Dedicated decision-log channel | "Separate decision-log channel" is a stated requirement, not a nice-to-have. | MEDIUM | Application logs and decision records have different retention, different redaction rules, and different consumers. Do not merge them. |
| OB-3 Complete decision records (ruleId, version, quota-at-decision, bucket, latency) | Explicitly required, and it is what makes RL/CN debuggable after the fact. | MEDIUM | "Quota at decision time" is the field people actually need and libraries rarely record. |
| OB-4 SDK health telemetry | Callers need to know the SDK is degraded, not just that requests are slow. | LOW | Config-source reachability, active rule-set version, degradation state. |
| OB-5 Default masking of sensitive fields | Cardholder data must not reach logs. | MEDIUM | Mask at the SDK layer, not at each call site. |

#### Cross-cutting (XX)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| XX-1 Thread safety and reentrancy guarantees on all public APIs | An SDK is called from arbitrary application threads. | MEDIUM | Must be stated, tested under concurrency, and documented. Non-negotiable for a library. |
| XX-2 Dependency / classloader isolation | An SDK that drags Sentinel + Nacos + Caffeine + Micrometer into every host app's classpath causes version conflicts in the *host*, not the SDK. | HIGH | Optional dependencies must be genuinely optional. The 250-line-per-class and modular-boundary discipline exists for this reason. |
| XX-3 Java 8 baseline | Explicit project constraint. | LOW | Non-negotiable. Rules out records, sealed types, and newer language features. |
| XX-4 Semantic versioning with backward-compatible SDK upgrades | Hosts upgrade on their own schedule. | MEDIUM | Deprecate-then-remove, never silently change behavior in a patch. |
| XX-5 Fast, deterministic startup that does not require a reachable config server | A library that blocks startup on a network call is unusable in some deployments. | MEDIUM | Start from the last-known-good snapshot; connect in the background. `[MEDIUM]` |
| XX-6 Last-known-good rule snapshot on startup | Fail-open/fail-close semantics need a *defined* answer when the config source is unreachable at boot. | MEDIUM | Nacos's own client provides a snapshot, but **it does not decide whether running the snapshot is safe** — that policy is the SDK's. `[MEDIUM]` |
| XX-7 Documented overhead budget | Card authorization is latency-sensitive; an SDK that adds unbounded per-call cost is rejected at review. | MEDIUM | Measure and publish p50/p99 overhead per module. |
| XX-8 Diagnostics endpoint (rule set, versions, degradation state, recent config-source errors) | The first support question is always "what config is this instance actually running?" | MEDIUM | This is the single highest-leverage debugging feature in the whole SDK. |

---

### Differentiators (Competitive Advantage)

These are where the SDK earns its existence. They align with the project's core value: **one governed control plane, one audit trail, one degradation model.**

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **D1. Single unified decision record across all five modules** | Sentinel records flow control; OpenFeature records flags; caches record nothing. A caller today cannot answer "why was this transaction rate-limited, routed, or served stale?" from one place. One schema (`ruleId`, `version`, `bucket`, `quotaAtDecision`, `reason`, `latency`) across RL + CN + cache is a real consolidation win — and it is the thing that makes the audit trail possible. | MEDIUM | The highest-value differentiator. Make it the *shared* internal type, not five lookalike records. |
| **D2. Explicit, queryable degradation state** | Sentinel's cluster flow control is a known single point of failure; the client blocks up to the token timeout on every request during jitter, then silently drops to local limiting. Alibaba maintainers concede the HA problem is unsolved. **No first-class API exposes whether an instance is currently cluster-limited or degraded-local.** Surfacing "I am degraded and I am now enforcing a weaker guarantee" as a first-class, queryable, alertable state is something Sentinel does not offer and that a payments caller needs. | MEDIUM | Cheap to build (a state enum + a gauge), high operational value. Requires a bounded token-request timeout so degradation does not stall the call path. `[MEDIUM]` |
| **D3. Rule validation before publish (empty / unparseable rule set is a distinct state)** | A silently-empty rule set that falls through to "no rules ⇒ allow everything" is a live security hole. Both Sentinel's Nacos datasource (empty on parse failure, warns only) and Spring Cloud Alibaba's yaml datasource (namespace/group/dataId mismatches) have shipped exactly this. An SDK that **fails loud at publish time** and distinguishes `NO_RULES_CONFIGURED` from `RULE_SOURCE_UNAVAILABLE` is defensively better than the ecosystem. | MEDIUM | Pairs with the Management API: validation runs server-side, before the version is published. |
| **D4. Versioned rule history with one-click rollback across all modules** | Nacos is a **stronger starting point than expected** — it has config version history, one-click rollback, and a publishing-history API with `srcUser`/`srcIp`. Sentinel's Dashboard is the genuinely unsafe substitute: rules live in application memory only, lost on restart, with no history, approval, or audit. The remaining gap is *domain* versioning: Nacos versions an opaque config blob per `namespace/group/dataId`, with no concept of one atomic release spanning rate limits + canary + cache-invalidation policy, and no link from a version to the decision records it produced. | HIGH | This is the core of the "management API" investment. Requires MG-2/3/4 to land first. |
| **D5. High-precision, cross-language-stable, documented bucketing with the bucket number exposed** | Fixed 100/10000/100000 bucket tables give 1% granularity (too coarse for high-traffic canaries) and reshuffle every user when the bucket count changes. Real projects have shipped canaries that disagreed across SDK languages because of divisor, signedness, and precision differences, and users with *no* variant from an overflow edge case. Pinning one documented algorithm and exposing the bucket number makes canary decisions reproducible and disputable. | MEDIUM | Bucket algorithm + bucket count are **versioned contract fields** — changing them re-routes traffic and must be treated as a breaking change. |
| **D6. Declared cache-coherence contract** | A local cache tier behind a pub/sub invalidation has at-most-once, no-ack invalidation: a restarting, GC-paused, or disconnected instance misses the message **permanently** and serves stale until TTL. Most libraries leave this undefined. Publishing "local TTL is the **maximum duration of incorrectness**" as a contractual guarantee, plus a missed-invalidation recovery path (sequence counter + periodic re-sync), is a maturity signal. | MEDIUM–HIGH | Honest disclosure is the differentiator. The periodic re-sync is what turns "at-most-once, best effort" into "bounded eventual correctness". |
| **D7. Startup from last-known-good with a stated, per-module safety policy** | Nacos's failover file is *not* created automatically and the client does not judge whether a snapshot is safe to run; Sentinel's datasource starts empty on parse failure. The SDK owns the policy: for each module, what does the last-known-good snapshot mean (rate limits keep enforcing; canary keeps routing) and what triggers an operator alarm. | MEDIUM | Requires XX-5/XX-6 plus the D2 degradation state. |
| **D8. Self-reported overhead telemetry** | "How much does this SDK cost me per call?" is the first question from any latency-sensitive adopter, and no upstream library answers it. The SDK exposes its own p50/p99 per-module overhead as a first-class metric using the `MeterBinder` pattern. | LOW | Cheap, and it pre-empts the most common adoption objection. |
| **D9. Bounded, non-blocking decision-log writer** | Synchronous logging of every decision couples the call path to the logging subsystem. A bounded queue with an explicit drop policy *and a dropped-record counter* is what a payments caller needs: the hot path never blocks, and the loss is visible rather than silent. | MEDIUM | The dropped counter is the part most teams forget. |
| **D10. Test-support module (in-memory config source + in-memory quota store + replay harness)** | Adopters write these fixtures for every internal library anyway. Shipping them makes the SDK testable in a consumer's unit tests without a Redis or Nacos container — and makes the SDK's own tests fast. | MEDIUM | Strong adoption lever, and it forces the seams that make the core testable. |

---

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems. Documented here to prevent scope creep.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| **A1. Bundling a web console into the SDK** | "Users need a UI for rules." | Turns a library into a service. Security surface, auth model, and release cadence all become the SDK's problem. The project already scopes the console as a **separate application**. | Separate console application that consumes the frozen Management API (MG-1). The SDK ships OpenAPI only. |
| **A2. Exposing Sentinel / Nacos / Caffeine types in the public API** | "Free" — no need to write adapters. | Leaks implementation into the consumer's compile surface. Every internal swap (e.g. Sentinel 1.8 cluster flow control → local distributed limiting) becomes a breaking change for *our* users. | Internal adapters behind a narrow SDK-owned interface. Consumers see `RateLimitGuard`, not `FlowRule`. |
| **A3. Using Sentinel Dashboard as the rule management UI** | It exists and looks like a management UI. | Rules pushed from the Dashboard live in **application memory only** — lost on restart without an external datasource. No version history, no approval, no audit, no rollback. It is an unaudited editing surface. | MG-2/MG-3/MG-4 as the governed control plane. Nacos as the durable distribution store *and* a per-blob history backstop — but the domain version, atomic cross-module publish, and decision correlation must live **above** it. |
| **A4. Business-semantic caches in v1** | "Cache the user, cache the merchant, cache the permission set." | Each one is a business model decision with its own invalidation contract, staleness tolerance, and PII retention rules. Unbounded scope, and a correctness risk if a stale permission cache is treated as authoritative. | Generic two-tier cache abstraction (CA-2) now; business caches in v2 as separate, individually-specified decisions. |
| **A5. A single global "safety" switch for fail-open/fail-close** | Simpler config, one place to look. | A module-specific override is a *feature* (canary should fail open; rate limiting should fail closed). One global switch forces operators to weaken the module that matters most, or to change the switch during an incident under pressure. | Per-rule default with per-rule override (RL-5, CN-3). No global kill switch for the safety default. |
| **A6. Annotation-only enforcement** | Cleanest-looking API. | Spring AOP is proxy-based: **self-invocation bypasses the advice**, and self-invocation is everywhere in real service code. The limiter silently stops limiting, and the failure is invisible because there is no error. | Annotation as a thin wrapper over a programmatic guard API (RL-1). Document the self-invocation limitation explicitly. |
| **A7. Arbitrary SpEL / script expressions evaluated per call** | Maximum flexibility for rule arguments. | Untrusted-input exposure: a remotely-stored expression evaluated inside the host JVM is a remote-code-execution path. Per-call compilation also destroys the latency budget. | Sandboxed, **precompiled** expressions from a restricted grammar, compiled once at rule-version load, with a hard evaluation timeout. Excluded from v1 if the restricted grammar cannot cover the top use cases. |
| **A8. High-cardinality metric labels (user id, PAN, transaction id, raw expression text)** | "More detail in dashboards." | Metric backends are cardinality bombs; this is a self-inflicted availability incident, and PAN must never reach a label value. | Low-cardinality dimensions (module, rule id, outcome, reason code, degradation state). Per-request detail belongs in the **decision log**, which is sampled and redacted — not in metric labels. |
| **A9. Synchronous decision logging on the hot path** | "We need every decision recorded." | Couples latency to the logging subsystem; under log-backend failure the auth path fails with it. | Bounded queue + drop policy + dropped counter (D9). |
| **A10. "Real-time everything" — a generic plugin/bus framework for all five modules** | Extensibility without a roadmap. | Each module has genuinely different failure semantics (fail-close vs fail-open vs eventual consistency). One abstraction forces the weakest common denominator and destroys the per-module degradation guarantees that are the product's main differentiator. | Five narrow, explicit module APIs sharing one decision-record type (D1). Extend deliberately. |

---

## Feature Dependencies

```
[XX-2 Dependency/classloader isolation] ──blocks──> every optional integration
        (Sentinel, Nacos, Micrometer must all be <optional>true)

[XX-5 Fast startup] ──requires──> [XX-6 LKG snapshot] ──requires──> [MG-4 Version history]
        (a snapshot is only "known good" if there is a history to know it from)

[D3 Rule validation] ──requires──> [MG-2 Publish] ──requires──> [MG-4 Version history]
        (validate before a version can become live)

[MG-4 Version history] ──requires──> [MG-2 CRUD] ──requires──> [MG-1 Frozen contract]
        (no contract, no CRUD; no CRUD, no history; no history, no rollback)

[MG-3 Audit] ──enhances──> [MG-4 Version history] ──enhances──> [D4 Unified rollback]

[D1 Unified decision record] ──enhances──> [OB-2 Decision-log channel]
[D1 Unified decision record] ──enhances──> [OB-3 Complete decision records]
        (OB-3 is the *output* of D1, not a sibling of it)

[D2 Degradation state] ──enhances──> [OB-4 SDK health telemetry]
[D2 Degradation state] ──requires──> [RL-7 Local fallback] ──requires──> [RL-6 Central store]
        (you cannot expose a degraded state without a degradation path)

[CN-4 Stable bucketing] ──enhances──> [D5 Documented bucketing]
[CN-5 Targeting key required] ──requires──> [D3 Rule validation]
        (enforced at publish time, not at request time)

[CN-2 Hot reload] ──requires──> [MG-4 Version history] ──requires──> [MG-2 Publish]
        (reload of *what*? reload is meaningless without an immutable version)

[CA-3 Single-flight] ──requires──> [CA-1 Local cache]
[CA-4 TTL jitter] ──requires──> [CA-1 Local cache]
[CA-5 Bulk invalidation] ──requires──> [CA-2 Two-tier interface]
[D6 Coherence contract] ──requires──> [CA-5 Bulk invalidation]

[RL-1 Annotation] ──enhances──> [RL-2 Guard API]   (thin wrapper, never the enforcer)
[RL-7 Local fallback] ──enhances──> [D7 LKG safety policy]
[D7 LKG safety policy] ──requires──> [XX-6 LKG snapshot] ──requires──> [MG-4 Version history]

[D10 Test-support module] ──enhances──> every module (fixtures for the whole SDK)
[XX-8 Diagnostics] ──enhances──> [D2 Degradation state] ──enhances──> [D4 Rollback]

[A7 Per-call script evaluation] ──conflicts──> [XX-7 Overhead budget]
[A5 Global safety switch] ──conflicts──> [D2 Degradation state]
        (a global switch hides which module is in which mode)
[A3 Sentinel Dashboard as UI] ──conflicts──> [MG-3 Audit] and [D4 Unified rollback]
        (memory-only rules have neither)
[A8 High-cardinality labels] ──conflicts──> [OB-1 Prometheus metrics]
[A2 Leaking upstream types] ──conflicts──> [XX-2 Dependency isolation]
[A1 Console in the SDK] ──conflicts──> [A2 Leaking upstream types] and the library-not-service boundary
```

### Dependency Notes

- **XX-2 requires every optional integration:** If Sentinel, Nacos, or Micrometer become required dependencies, they land in every consuming application's classpath and version conflicts surface in the *host*. Every integration must be `<optional>true` with a conditional activation path, which is exactly why a non-Spring host must still get a working SDK.
- **MG-1 → MG-2 → MG-4 → D4 (the governance chain):** The frozen contract, CRUD, and version history are strictly ordered. **Rollback and unified version history cannot be retrofitted cheaply** — a schema that stores only "current content" is a schema that cannot answer "what was live at 03:14 last Tuesday", which is exactly the audit question MG-3 exists to answer. This is the strongest single argument for freezing the contract correctly the first time.
- **D3 requires MG-2:** Validation is a *publish-time* control. It cannot live in the data plane, because by the time a rule reaches the hot path an empty or unparseable set has already been served.
- **D1 enhances OB-2/OB-3:** Complete decision records are the serialization of the unified decision record. Writing OB-3 independently per module is how the five-module "unified" story quietly becomes five lookalike schemas.
- **D2 requires RL-7 requires RL-6:** A degradation state with no degradation path is theatre. Conversely, local fallback without a queryable state is the Sentinel failure mode — silent weaker enforcement.
- **CN-2 requires MG-4:** "Hot reload" is only meaningful if reload targets an *immutable version*. Reloading mutable current-content config is just an uncontrolled restart of the current state.
- **CA-3 requires CA-1:** Stampede protection is a property of the local tier. A distributed tier alone does not have it.
- **D6 requires CA-5:** A declared coherence contract with a recovery path is meaningless without a bulk invalidation primitive to declare the contract over.
- **D10 enhances everything:** Test fixtures are the mechanism that makes each module independently testable, and they are the forcing function for the seam that keeps upstream types out of the public API (A2).
- **A7 conflicts with XX-7:** Per-call expression compilation trades the latency budget for flexibility the payments use case does not need.
- **A5 conflicts with D2:** A single global safety switch makes the degradation state unqueryable — you cannot tell which module is in which mode.
- **A3 conflicts with MG-3 and D4:** Memory-only, unversioned, unaudited rules cannot satisfy a change-audit requirement.
- **A8 conflicts with OB-1:** Cardinality is a shared, finite resource; unbounded labels in a library are charged to every host that adopts it.

---

## MVP Definition

### Launch With (v1)

Minimum viable product — the smallest set that makes the SDK safe to put on a card-authorization path.

- [ ] **XX-1/XX-2/XX-3/XX-4** — Thread safety, dependency isolation, Java 8, semver. *Non-negotiable foundation; an SDK that breaks its host's classpath is not adoptable regardless of features.*
- [ ] **XX-5/XX-6** — Fast startup + last-known-good snapshot with a stated per-module safety policy. *Without this, every downstream "what happens when Nacos is down?" question is unanswered.*
- [ ] **D1** — Unified decision record type. *The consolidation thesis; building it last would mean retrofitting five schemas.*
- [ ] **RL-1..RL-8** — Rate limiting: annotation + guard API, token bucket, whitelist, per-rule fail-close default, central store, local fallback, metrics. *The module that pays for the SDK; fail-close semantics are why payments adopt it.*
- [ ] **CN-1..CN-6** — Canary: precedence, hot reload, per-rule fail-open default, stable bucketing, required targeting key, decision log. *Pairs with RL to make "one governed control plane" true rather than aspirational.*
- [ ] **CA-1, CA-2, CA-3, CA-4, CA-5, CA-6** — Cache: local tier, two-tier interface, single-flight, TTL jitter, O(1) invalidation, metrics. *Cheap, high-frequency, and the module where the coherence contract is easiest to state honestly at v1.*
- [ ] **MG-1, MG-2, MG-3, MG-4, MG-5, MG-6** — Management API: frozen contract, CRUD/publish/rollback, audit, version history, auth, health. *The governance story is the product; a version history that is retrofitted is a schema migration for every consumer.*
- [ ] **OB-1..OB-5** — Observability: metrics, dedicated decision-log channel, complete decision records, health telemetry, default masking. *Masking in particular is a payments-compliance gate, not a nicety.*
- [ ] **D3** — Rule validation before publish, distinguishing *no rules configured* from *rule source unavailable*. *Directly prevents the documented "empty rule set ⇒ allow everything" hole.*
- [ ] **D2 + D8** — Queryable degradation state + self-reported overhead telemetry. *Both are small, both are high-signal, both differentiate against the upstream libraries.*

**Justification for this being the minimum:** the v1 set is exactly the set where removing any item leaves a *correctness or safety* hole rather than a *convenience* gap. A v1 without MG-4 cannot roll back; without D3 a bad rule can silently disable protection; without D2 a degraded instance lies about its own guarantees.

### Add After Validation (v1.x)

Add once core is working and adopters are exercising it.

- [ ] **D4** — Unified version history and one-click rollback **spanning all five modules**. *Trigger: first multi-module adopter, or first incident that needed a cross-module rollback.*
- [ ] **D6** — Declared cache-coherence contract + missed-invalidation recovery (sequence counter + periodic re-sync). *Trigger: first adopter with a local tier behind a distributed tier in production, or first stale-data report.*
- [ ] **D7** — Refined LKG safety policy with per-module alerting. *Trigger: first config-source outage exercise.*
- [ ] **D10** — Test-support module: in-memory config source, in-memory quota store, deterministic replay harness. *Trigger: first external contributor or second consuming team — fixtures become a bottleneck fast.*
- [ ] **A7 (restricted form)** — Sandboxed precompiled expression grammar for rule arguments. *Trigger: evidence that a fixed attribute set cannot express real rules. **Ship only if the restricted grammar covers the top use cases**; otherwise rule arguments stay an explicit, enumerated attribute set.*
- [ ] **XX-8** — Full diagnostics endpoint (config-source error history, per-instance effective state). *Trigger: first support escalation that could not be answered from metrics alone.*
- [ ] **D9** — Bounded non-blocking decision-log writer with drop policy and dropped counter. *Trigger: measured log-backend latency on the hot path. If decision logging is synchronous in v1, this is the first thing to fix.*

### Future Consideration (v2+)

Defer until product-market fit is established.

- [ ] **A4** — Business-semantic caches (user, merchant, permission). *Defer: each is a business-model decision with its own invalidation contract and PII retention rules. Specify individually, never as a batch.*
- [ ] **Distributed local-quota coordination** — instance-count-weighted local limiting driven by registry membership, as an alternative to a centralized token server. *Defer: this is the direction Alibaba maintainers point toward for Sentinel cluster HA, but they note it is inaccurate under uneven traffic and has deregistration lag. It is a research project, not a v1.x feature.*
- [ ] **Two-tier rate-limit strategies** (local chunk + global store) to cut central-store traffic. *Defer: trades a bounded, documented inaccuracy for a large load reduction. Only worth it after central-store cost is a real constraint.*
- [ ] **Change preview / dry-run of a candidate rule set against recorded recent decisions before publish.** *Defer: genuinely valuable, but it depends on a mature decision log (OB-2) and enough production history to be meaningful.*
- [ ] **A1** — Console UI. *Explicitly a separate application consuming the frozen MG-1 contract. Never a feature of this SDK.*
- [ ] **Hot-reloadable business-logic extension points.** *Defer: this is the "real-time everything" anti-feature (A10) in another costume. Five narrow module APIs first.*

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| XX-2 Dependency/classloader isolation | HIGH | HIGH | **P1** |
| D1 Unified decision record | HIGH | MEDIUM | **P1** |
| RL-5 / CN-3 Per-rule fail-close / fail-open defaults | HIGH | LOW | **P1** |
| OB-3 Complete decision records | HIGH | MEDIUM | **P1** |
| MG-1 Frozen REST contract | HIGH | MEDIUM | **P1** |
| XX-6 Last-known-good snapshot | HIGH | MEDIUM | **P1** |
| RL-6 Central quota store | HIGH | MEDIUM | **P1** |
| D3 Rule validation before publish | HIGH | MEDIUM | **P1** |
| MG-3 Change audit | HIGH | MEDIUM | **P1** |
| OB-5 Sensitive-field masking | HIGH | MEDIUM | **P1** |
| D2 Queryable degradation state | HIGH | LOW | **P1** |
| MG-4 Rule version history | HIGH | MEDIUM | **P1** |
| CA-1 Local cache | MEDIUM | LOW | **P1** |
| XX-1 Thread safety / reentrancy | HIGH | MEDIUM | **P1** |
| CN-4 Stable documented bucketing | HIGH | MEDIUM | **P1** |
| XX-5 Fast deterministic startup | HIGH | MEDIUM | **P1** |
| RL-1 Annotation + guard API | MEDIUM | MEDIUM | **P1** |
| RL-7 Local fallback on store outage | HIGH | MEDIUM | **P1** |
| D8 Self-reported overhead telemetry | MEDIUM | LOW | **P1** |
| MG-2 CRUD + publish + rollback | HIGH | HIGH | **P1** |
| CA-3 Single-flight | MEDIUM | MEDIUM | **P2** |
| CN-2 Hot reload without restart | MEDIUM | MEDIUM | **P2** |
| OB-2 Dedicated decision-log channel | HIGH | MEDIUM | **P2** |
| MG-5 Management API auth | HIGH | MEDIUM | **P2** |
| D9 Bounded non-blocking decision-log writer | MEDIUM | MEDIUM | **P2** |
| CA-5 O(1) bulk invalidation | MEDIUM | MEDIUM | **P2** |
| XX-7 Overhead budget measurement | MEDIUM | MEDIUM | **P2** |
| D6 Declared cache-coherence contract | MEDIUM | MEDIUM | **P2** |
| D5 High-precision cross-language bucketing | MEDIUM | MEDIUM | **P2** |
| OB-4 SDK health telemetry | MEDIUM | LOW | **P2** |
| CN-6 Canary decision log | MEDIUM | LOW | **P2** |
| XX-8 Diagnostics endpoint | MEDIUM | MEDIUM | **P2** |
| D10 Test-support module | MEDIUM | MEDIUM | **P2** |
| CA-2 Pluggable two-tier interface | MEDIUM | MEDIUM | **P2** |
| CA-4 TTL jitter | MEDIUM | LOW | **P2** |
| CA-6 Cache metrics | MEDIUM | LOW | **P2** |
| RL-3 Token bucket with burst | MEDIUM | MEDIUM | **P2** |
| RL-4 Whitelist / bypass (audited) | MEDIUM | LOW | **P2** |
| CN-1 Priority-ordered matching | MEDIUM | MEDIUM | **P2** |
| CN-3 Canary fail-open default | HIGH | LOW | **P2** |
| CN-5 Required targeting key (enforced at publish) | MEDIUM | LOW | **P2** |
| MG-6 Management API health / effective state | MEDIUM | LOW | **P2** |
| D7 Refined LKG safety policy | MEDIUM | MEDIUM | **P2** |
| D4 Unified cross-module version history + rollback | HIGH | HIGH | **P3** |
| A7 Sandboxed precompiled expression grammar | MEDIUM | HIGH | **P3** |
| XX-4 Semantic versioning / deprecation discipline | HIGH | MEDIUM | **P3** |
| A4 Business-semantic caches | MEDIUM | HIGH | **P3** (v2) |
| A1 Console UI (separate app) | MEDIUM | HIGH | **P3** (separate project) |
| Distributed local-quota coordination | MEDIUM | HIGH | **P3** (v2 research) |
| Two-tier rate-limit strategies | LOW | HIGH | **P3** (v2) |
| Change preview / dry-run against recorded decisions | MEDIUM | HIGH | **P3** (v2) |

**Priority key:**
- **P1:** Must have for launch — absence leaves a correctness, safety, or adoptability hole.
- **P2:** Should have, add when possible — absence is a maturity or operational-experience gap.
- **P3:** Nice to have, future consideration — value depends on validation, on scale, or on a separate artifact.

*Note: XX-3 (Java 8 baseline) is not in the matrix because it is a constraint, not a feature — it is a permanent restriction on every implementation choice.*

---

## Competitor Feature Analysis

Comparisons are against the upstream libraries the SDK wraps and the pattern adopters assemble by hand. "Our approach" is the SDK's intended differentiation.

| Feature | Sentinel (Alibaba) | Nacos (Alibaba) | OpenFeature / flagd | Caffeine | Micrometer | Our Approach |
|---------|---------------------|------------------|----------------------|----------|-----------|--------------|
| **Rate limiting** | Native — token bucket, cluster + single-node, annotations, block API. The strongest single library here. | Not a rate limiter (config distribution only) | No | No | No | Wrap Sentinel; add governance (versioning, audit, publish) and an explicit degradation state. Do not re-implement counting. |
| **Cluster HA** | **Known unsolved.** Token server is a single point of failure; client blocks for the request timeout on every request during jitter, then degrades to local. Maintainers concede it and point to registry-weighted local limiting — itself inaccurate under uneven traffic. | N/A | N/A | N/A | N/A | **D2** — expose the degraded state as first-class, alertable, and documented. Bound the token-request timeout. Do not pretend HA exists. |
| **Traffic canary** | None (flow control ≠ traffic routing) | Config distribution only | Yes — providers, targeting rules, variants, OpenFeature-standard evaluation API | No | No | Wrap an OpenFeature-shaped evaluation model; add required targeting key, publish-time validation, and versioned rollback. |
| **Bucketing algorithm** | N/A | N/A | Real, documented precision work — 32-bit hash over `Int32.MAX_VALUE` for ~0.00000005% granularity; documented cross-language divergence and a `Math.abs(Integer.MIN_VALUE)` edge case that leaves a user with **no** variant. | N/A | N/A | **D5** — pin one algorithm, expose the bucket number, and treat algorithm + bucket count as **versioned contract fields** so changing them is a breaking change. |
| **Config hot reload** | Yes via Nacos/Redis/etc. datasource; NacosDataSource uses a single-thread executor with `DiscardOldestPolicy`, so bursts **drop intermediate rule revisions** | Yes — listener + long polling; verified source quirk where changed configs are re-processed on the next loop iteration | Via provider | No | No | Use Nacos as the **distribution** store; own the version layer above it. Treat intermediate-revision drops as a real risk: re-sync, do not assume every revision is delivered. |
| **Local failover / snapshot** | Datasource starts **empty** on parse failure (warn only) — a silent "no rules ⇒ allow all" path | Yes — snapshot after successful query; a **user-maintained failover file** that the client never creates automatically; client does not judge whether a snapshot is safe | Provider-dependent | No | No | **D7 / XX-6** — own the policy: per-module statement of what the last-known-good means and when to alarm. Nacos's snapshot is storage, not a decision. |
| **Rule management UI** | Dashboard exists — but rules pushed from it live in **application memory only**, lost on restart, with no version history, no approval, no audit, no rollback | **Stronger than assumed.** The console provides config version history, one-click rollback, and a publishing-history API exposing `srcUser` and `srcIp`. But its unit of versioning is the **config item** (`namespace/group/dataId`) and content is stored opaquely — there is no notion of a rule *set* spanning rate limits + canary + cache-invalidation policy as one atomic release, and no link from a version to the decision records it produced. Retention is deployment-configurable (one managed offering documents a 30-day cap — short of a PCI DSS one-year requirement). | Console per provider | No | No | **A3** — reject the Sentinel Dashboard as the management surface. Use Nacos as the distribution store *and* a per-blob history backstop, but put MG-2/MG-3/MG-4 above it: domain-level versioned publishing with audit correlated to decisions. The console is a separate app. |
| **Caching** | No | No | No | Local cache; per-key `compute` atomicity; no cross-node coherence | No | Provide CA-1..CA-6 with an explicit **coherence contract** (local TTL = maximum duration of incorrectness) and a missed-invalidation recovery path. |
| **Cache invalidation** | No | No | No | Expiry + eviction only | No | **CA-5** — generation-counter prefix for O(1) bulk invalidation; **D6** — publish the staleness guarantee instead of leaving it implicit. |
| **Metrics** | Internal metrics only; no first-class API exposing current limiting mode | No | No | No | Yes — the vendor-neutral standard | **A8** — one low-cardinality namespace across all five modules, delegating to Micrometer. Per-request detail goes to the decision log, never to labels. |
| **Decision audit / explainability** | None — Dashboard makes it hard to even tell whether you are cluster- or local-limited | Publishing history only (`srcUser`, `srcIp`, content diff) — records *who edited config*, but not *why a request was decided*; no per-decision record, no bucket, no quota-at-decision, no correlation to a rule version | Some provider-side evaluation metadata, not a persisted audit trail | No | No | **D1 + MG-3** — one decision-record schema across all modules, append-only, correlated to rule version. Config-change audit and request-decision audit are different artifacts; only this links them. This is the core differentiator. |
| **Expression / rule arguments** | Java-only resource/param expressions; not a safe remote-eval surface | N/A | Targeting expression languages exist but are provider-scoped | N/A | N/A | **A7** — v1 uses an explicit enumerated attribute set. Only introduce a sandboxed, **precompiled**, restricted-grammar expression in v1.x, and only with evidence the enumerated set is insufficient. |
| **API stability guarantees** | Sentinel's own evolution, no semver contract for consumers | N/A | OpenFeature spec versioning is a genuine strength | Caffeine is careful but consumer-driven | Stable | **XX-4** — semver, deprecate-then-remove, and **A2** — no upstream types in the public API, so internal swaps are not breaking for our consumers. |
| **Embedding model** | Library (good) | Library + server (server is the dependency) | Library | Library | Library | **XX-2** — all integrations `<optional>true`; the SDK must work in a non-Spring host with no Sentinel, no Nacos, and no Micrometer on the classpath. |

### What the SDK is actually competing on

Not counting algorithms — Sentinel's rate limiter, flagd's bucketing, and Caffeine's local cache are each better than anything this SDK would re-implement. The competitive surface is:

1. **Domain governance** (MG-1..MG-5, D4) — atomic publish and rollback of a *rule set* spanning modules, with audit correlated to decision records and a retention policy you control. Nacos supplies per-blob config history and a publishing-history API, but it versions opaque content per `namespace/group/dataId` with no cross-module release unit; Sentinel's Dashboard supplies no persistence at all and is an actively unsafe substitute.
2. **Honest degradation semantics** (D2, D3, D7) — knowing *which* guarantee is currently in force, and refusing to silently allow-all on an empty rule set. Upstream libraries degrade silently.
3. **One decision record** (D1, OB-2/OB-3) — correlating "rate-limited, routed to B, served from cache" into one explainable story. No upstream library spans more than one of these.
4. **Library discipline** (XX-1, XX-2, XX-4) — no leaked upstream types, no forced dependencies, no per-call surprises.

---

## Sources

Confidence tags reflect the verification tier of the underlying evidence, obtained via the
`classify-confidence` seam (`context7` → MEDIUM; `exa` → LOW; issue-tracker and community
analysis was independently cross-checked but remains LOW-tier primary evidence).

**Official documentation (MEDIUM)**
- [Spring Boot 2.7.18 — Developing Auto-Configuration](https://github.com/spring-projects/spring-boot/blob/v2.7.18/spring-boot-project/spring-boot-docs/src/docs/asciidoc/features/developing-auto-configuration.adoc) — `AutoConfiguration.imports`, `@ConditionalOnClass` / `@ConditionalOnMissingBean`, optional `spring-boot-autoconfigure-processor`. *Supports RL-1, XX-2, A2.*
- [Spring Framework — Proxy-based AOP, self-invocation limitation](https://docs.spring.io/spring-framework/docs/4.3.15.RELEASE/spring-framework-reference/html/aop.html) — *Supports RL-1, A6.*
- [Micrometer — Registry, Global Registry, Meter Filters, Long Task Timers, JVM metrics](https://github.com/micrometer-metrics/micrometer/blob/main/docs/modules/ROOT/pages/concepts/registry.adoc) — composite registry accepts `addRegistry()` after meters exist; store `Meter` instances in fields; `MeterFilter.forMeters`; `MeterBinder`; `@Timed(longTask = true)`. *Supports OB-1, D8, A8.*
- [Nacos — Config Local Recovery spec](https://github.com/alibaba/nacos/blob/develop/specs/en/client/client-local-cache-redo-spec.md) — read priority (failover file → server → snapshot); the client does not create the failover file and does not decide safety. *Supports XX-6, D7, CN-2.*
- [Nacos — Java Client SDK spec](https://github.com/alibaba/nacos/blob/develop/specs/en/sdk/sdk-java-impl-spec.md) — `serverAddr`, `namespace`, `configRequestTimeout`, credential properties. *Supports XX-2, CN-2.*
- [Nacos — Configuration Overview](https://nacos.io/en/docs/latest/manual/user/config/overview/) — the config resource model is `namespaceId → groupName → dataId`, and "config content is handled as a whole… it does not understand individual business fields inside the file." Content is versioned by **md5**, and a change notification explicitly "is not the full config content." *Supports D4: Nacos versions an opaque blob, not a domain rule set — the basis for the gap as stated above.*
- [Nacos — Console Manual](https://nacos.io/en/docs/latest/manual/admin/console/) and [Open API Guide](https://nacos.io/en-us/docs/open-api.html) — config version history, one-click rollback, and history query endpoints (list history, history detail, previous version). *Supports the corrected D4 claim: Nacos **does** provide per-blob history and rollback, so the gap is domain-level atomicity, not a total absence of versioning.*
- [Nacos — Console API: Query Configuration Publishing History](https://nacos.io/en/docs/next/manual/admin/console-api/) — returns `srcIp` and `srcUser` per publish event; `srcUser` is populated **only when authentication is enabled**. *Supports the "Decision audit" row: Nacos records who edited config, but the `srcUser` gap under auth-disabled operation is exactly why config-change audit is not a substitute for a decision audit with enforced identity.*
- [Eclipse — Evolving Java-based APIs](https://github.com/eclipse-platform/eclipse.platform/blob/master/docs/Evolving-Java-based-APIs.md) — API compatibility and deprecation discipline. *Supports XX-4, A2.*
- [Cloudflare — Flagship: Percentage Rollouts](https://developers.cloudflare.com/flagship/targeting/percentage-rollouts/) — missing `targetingKey` ⇒ random bucket per evaluation. *Supports CN-5, D5.*
- [EPC — Audit Trails in Security Systems v1.0](https://www.europeanpaymentscouncil.eu/sites/default/files/KB/files/EPC153-10-Audit-Trails-in-Security-Systems-v1.0-Approved.pdf) — payments-sector audit-trail integrity, signing, timestamping, WORM archival. *Supports MG-3, OB-2.*

**Standards (MEDIUM–LOW; verified against the standard text, applicability to this SDK is a judgment call)**
- [PCI DSS v4.0](https://www.middlebury.edu/sites/default/files/2025-01/PCI-DSS-v4%5F0%5F1.pdf) — Req. 10.2/10.3 audit-event content, 10.5 anti-tamper, 10.6 daily review, 10.7 retention; Req. 3.4 PAN unreadable anywhere stored including logs. *Supports OB-5, MG-3. Note: the SDK itself does not handle PAN, so it sits outside the CDE — the finding is that decision-log fields which could carry a PAN must be masked at the SDK layer by default.*

**Source-code and issue-tracker analysis (LOW — primary evidence, independently cross-checked, not vendor-confirmed)**
- [Sentinel issue #2466 — cluster HA / single point of failure](https://github.com/alibaba/Sentinel/issues/2466) and [#2616 — token server restart timeout spike](https://github.com/alibaba/Sentinel/issues/2616) — degraded-state ambiguity, timeout-on-jitter behavior. *Supports D2, RL-7, XX-7.*
- [Sentinel `NacosDataSource` source](https://github.com/alibaba/Sentinel/blob/master/sentinel-extension/sentinel-datasource-nacos/src/main/java/com/alibaba/csp/sentinel/datasource/nacos/NacosDataSource.java) and [issue #2267](https://github.com/alibaba/Sentinel/issues/2267) — empty-on-parse-failure, single-thread `DiscardOldestPolicy` executor dropping intermediate revisions, namespace/group/dataId mismatch. *Supports D3, CN-2.*
- [flagd — `fractional` operation specification](https://flagd.dev/reference/custom-operations/fractional-operation/) and [High-Precision Fractional Bucketing ADR](https://github.com/open-feature/flagd/blob/main/docs/architecture-decisions/high-precision-fractional-bucketing.md) — the legacy evaluator mapped MurmurHash3 into `[0,100]` with float math, capping resolution at 1% and a max variant ratio of 1:99; the ADR (amended 2026-01-29) replaces this with pure integer arithmetic over a `MaxInt32` weight sum for ~0.00000005% granularity and ~25–35% less user reassignment when weights change. *Supports D5, CN-4.*
- [flagd issue #1872 — cross-language bucket divergence](https://github.com/open-feature/flagd/issues/1872) (opened 2026-02-23, **closed/fixed**) — the same user and `targetingKey` received different variants per SDK: JS/TS divided by `2^31` instead of `2^31-1`; C# used `float32` (≈23 mantissa bits, losing 9 bits of hash) plus `(int)` truncation, and `Encoding.ASCII` mangled non-ASCII keys; Rust used an unsigned `u32::MAX` divisor, sending a hash of `0x80000001` to bucket 50 instead of 100. A shared 1-in-2^32 `Integer.MIN_VALUE` edge case made `Math.abs` return a negative value, so the user fell through every weight range and received **no variant at all**. *This is the single strongest piece of evidence for D5: the failure is silent, produces wrong traffic splits in production, and only a pinned algorithm plus a contract-versioned bucket count prevents it.*
- [flagd issue #1788 — bucketing improvement tracker](https://github.com/open-feature/flagd/issues/1788) — "handle missing bucketing keys gracefully" is still an **open, unchecked** P2. *Supports CN-5: do not inherit a missing-targeting-key fallback; reject it at publish time.*
- [CoCache](https://cocache.ahoo.me/) and [two-level cache patterns](https://scalemind.dev/java/backend/caching-patterns-java-caffeine-redis-hybrid/) — at-most-once pub/sub invalidation, sequence-counter re-sync, TTL jitter, stampede. *Supports CA-3/4/5, D6.*
- [Central quota store failure modes](https://hld.handbook.academy/curriculum/case-studies/rate-limiter/) — atomicity via single-script execution, clock skew, fixed-window thundering herd, replica promotion with lost counters, idempotency-keyed decisions. *Supports RL-3, RL-6, D1.*

**Project-internal (HIGH)**
- `.planning/PROJECT.md` — module scope, per-module fail-open/fail-close defaults, masking requirement, reserved tenant/permission slots, Java 8, out-of-scope console. This document's *feature requirements* are HIGH confidence; the *third-party ecosystem behavior* they depend on is MEDIUM–LOW as tagged above.

---

*Feature research for: embedded in-process Java foundation SDK (rate limiting, canary, cache, management API, observability)*
*Researched: 2026-09-29*
