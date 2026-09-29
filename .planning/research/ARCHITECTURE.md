# Architecture Research

**Domain:** Embedded in-process foundation-capability SDK (rate limiting / canary / cache) + its control plane, for a credit-card real-time data system
**Stack under study:** JDK 8 · Spring Boot 2.7.6 · Spring Cloud 2021.0.5.0 · Spring Cloud Alibaba 2021.0.5.0 · Nacos (already deployed) · Sentinel 1.8.6
**Researched:** 2026-09-29
**Confidence:** HIGH on component boundaries, Sentinel/Nacos internals, and dependency isolation (verified against source/POM/official docs). MEDIUM on the control-plane API shape (design judgment, not sourced). LOW on the cluster token-service implementation choice (explicitly deferred by the project).

---

## Standard Architecture

### System Overview

This is **not** a microservice diagram. It is two physically separate systems that share exactly one artifact.

```
╔═══════════════════════════════ CONTROL PLANE (deployed, one instance) ═══════════════════════════╗
║                                                                                                ║
║   ┌────────────────────┐   writes    ┌──────────────┐   publish    ┌──────────────────────┐     ║
║   │  Console UI        │──────────▶  │ Management   │────────────▶ │ Nacos Config         │     ║
║   │ (FUTURE sub-app,   │   REST     │ API          │  (sole       │ (ALREADY DEPLOYED,   │     ║
║   │  NOT in this       │            │ (this sub-   │   writer)    │ reused — not a 2nd   │     ║
║   │  milestone)        │            │  app)        │              │ config center)       │     ║
║   └────────────────────┘            └──────┬───────┘              └───────────┬──────────┘     ║
║                                           │                                  │            ║
║                                  ┌────────▼─────────┐                        │            ║
║                                  │ Rule Store (DB)  │──── version history ───┘            ║
║                                  │ releases,audit,  │   (Nacos holds ONLY the                ║
║                                  │ tenant, perms    │    current materialized set)           ║
║                                  └──────────────────┘                                             ║
╚════════════════════════════════════════════════════════════════════════════════════════════════╝
                                              │
                    (DataId / Group / namespace — a PUSH-PULL boundary, not a call)
                                              │
┌══════════════════════════════════════ DATA PLANE (embedded library, N instances × M services) ═══════════════════════════════════════
║                                            ▼                                                                                                   ═
║  ┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐                        ═
║  │  Business Service JVM  (Sentinel "SDK" = this whole box)                                            │                        ═
║  │                                                                                                       │                        ═
║  │   @RateLimited / @CanaryRoute / @TwoLevelCache                                                       │                        ═
║  │        │ (annotations — the only thing a business service must write)                                │                        ═
║  │        ▼                                                                                             │                        ═
║  │   ┌──────────┐   ┌──────────┐   ┌───────────┐   ┌─────────────┐   ┌──────────────┐   ┌──────────┐     │                        ═
║  │   │ Injection│──▶│ Arg      │──▶│ Expression│──▶│ Rule Router │──▶│ Sentinel     │──▶│ Decision │     │                        ═
║  │   │ point    │   │ extract  │   │ (sandboxed│   │ (compiled   │   │ applier +    │   │ assembly │     │                        ═
║  │   │ (AOP)    │   │          │   │  precomp.)│   │  RoutePlan)│   │ cluster gate │   │          │     │                        ═
║  │   └──────────┘   └──────────┘   └───────────┘   └─────────────┘   └──────┬───────┘   └────┬─────┘     │                        ═
║  │                                                                       │ (counts)        │           │                        ═
║  │                                                            ┌──────────▼──────────┐        │           │                        ═
║  │                                                            │ Token Server        │        │           │                        ═
║  │                                                            │ (separate deployable│        │           │                        ═
║  │                                                            │  — or degraded to    │        │           │                        ═
║  │                                                            │  LOCAL, no network)  │        │           │                        ═
║  │                                                            └─────────────────────┘        │           │                        ═
║  │                                                                                             │           │                        ═
║  │   ┌──────────┐        ┌──────────────────────────────────────────────────────────────────┐   │           │                        ═
║  │   │ Canary   │        │ Observer: bounded ring buffer → batch writer → dedicated sink      │◀──┘           │                        ═
║  │   │ evaluator│        │ (never touches the business logger, never blocks the business thread)│               │                        ═
║  │   └────┬─────┘        └──────────────────────────────────────────────────────────────────┘               │                        ═
║  │        │                                                                                          │                        ═
║  │   ┌────▼─────────┐   L1 Caffeine            ┌──────────────────────┐                                                    ═
║  │   │ Two-level    │──────────────┐         │ Rule Set Snapshot    │                                                    ═
║  │   │ cache        │              │ pub/sub │ (immutable,          │                                                    ═
║  │   └────┬─────────┘              └────────▶│  versioned, LKG on   │                                                    ═
║  │        │                            ┌───  │  disk)              │                                                    ═
║  │   ┌────▼─────────┐   ┌──────────┐    │    └──────────▲───────────┘                                                    ═
║  │   │ L2 Redis    │◀──│          │────┘               │                                                                ═
║  │   └─────────────┘   │ Nacos    │────────────────────┘                                                                ═
║  │                     │ listener │                                                                                     │                        ═
║  │                     └──────────┘                                                                                     │                        ═
║  └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘                        ═
╚═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
```

**The single most important structural fact:** the Control Plane and the Data Plane share **only the rule schema** (`foundation-core`). They do not share a jar, a process, a database connection, or a network protocol. Nacos is the entire contract between them.

### Component Responsibilities

| # | Component | Responsibility | Does NOT own |
|---|-----------|----------------|--------------|
| C1 | `foundation-core` | Rule schema, version envelope, `DecisionRecord`, all SPIs. Zero runtime deps. | Any behavior |
| C2 | `foundation-expression` | Sandboxed compile + evaluate of expressions. | Rules, limits, rate limiting |
| C3 | `foundation-observer` | Decision record sink, ring buffer, batch writer, Micrometer metrics, health gauges. | Decision logic, rules |
| C4 | `foundation-rule` | Rule parsing, validation, compilation into `RoutePlan`, immutable `RuleSet` snapshot, last-known-good disk. | Nacos, Spring, Sentinel |
| C5 | `foundation-ratelimit` | The 6-layer hot path + Sentinel rule installer + cluster availability gate. | Rule parsing, observability transport |
| C6 | `foundation-canary` | Multi-dimension canary evaluation (whitelist > percentage > hash bucket). | Rule parsing, observability transport |
| C7 | `foundation-cache` | Generic two-level cache: L1/L2, 3 protections, multi-dim keys, invalidation bus. | Nacos, Sentinel, rules |
| C8 | `foundation-nacos` | Nacos `RuleSource` impl, push-pull listener, `ClusterHealthProbe`. | Rule semantics |
| C9 | `foundation-autoconfigure` | Spring Boot auto-config; **the only module that knows Spring exists**. | Any hot-path logic |
| C10 | `foundation-starter` | POM-only aggregator that consumers import. | Everything |
| C11 | `foundation-console-api` | Management REST API. Deployable. **Depends on C1 only.** | The whole data plane |
| C12 | `foundation-token-server` | Cluster token service. Deployable. | Business code |
| C13 | `foundation-samples` | Demo/consumer-reference service. | — |

---

## The Coupling Contract (what must NOT talk to what)

This is the section that determines whether the SDK survives being imported by services the team does not control. Every rule below is a **build-enforceable** constraint, not a style guideline.

### Hard prohibitions

| Prohibited coupling | Why | Enforcement |
|---|---|---|
| **Any data-plane module → `spring-*`** | A Spring Boot version skew in a consuming service must never be able to reach the evaluation hot path. If the hot path compiles against Spring, the SDK's correctness becomes hostage to the host's Boot version. | `maven-enforcer-plugin` `bannedDependencies` on every module except C9 |
| **C11 (Console API) → C2–C9** | The control plane must be deployable without dragging the data plane's Sentinel/Netty/Redis baggage into it. It needs exactly one thing from the SDK: the schema. | C11's POM has exactly one internal dependency |
| **C11 (Console API) → C12/C13** | Different deployables, different lifecycles. | — |
| **C11/C12 → SDK instances (any RPC)** | Two distribution paths would have different ordering and delivery guarantees. Rollback ordering becomes unprovable, and traceability breaks. **Nacos is the only distribution channel.** | Architectural; review rule |
| **C4 (Rule) → C7 (Cache)** | Rule staleness has different semantics from data staleness: rules are version-stamped, swapped atomically, must never have a TTL, and must never serve a payload that failed validation. A shared generic cache would happily serve a stale or invalid rule behind a TTL. | `bannedDependencies` |
| **C7 (Cache) → C4/C5/C6** | The cache is a *generic* layer (v1 explicitly ships no business-semantic caches). If it could see rule or rate-limit types it would become domain-coupled and could not be reused. | `bannedDependencies` |
| **C5 → C6, C6 → C5** | Rate limiting and canary are independent capabilities sharing C1–C4. Coupling them means a change to one forces redeployment of both and makes the canary rule model hostage to the limit rule model. | `bannedDependencies` |
| **C2/C3 → C5/C6/C7** | The leaf utilities must not know their callers. C3 in particular must collect from all three paths without any of them depending on each other. | `bannedDependencies` |
| **C3 (Observer) → business logger (SLF4J/Logback of the host app)** | The whole point of a separate decision channel is that it is not the business channel. Writing decisions through the host's logger guarantees they get filtered, sampled, and rotated with app logs. | Code review rule; dedicated appender |
| **C5 (Rate limit) → Sentinel's global config** (`sentinel.eager`, `csp.sentinel.dashboard`, transport settings) | The SDK is a library inside someone else's JVM. Changing Sentinel's global config would silently alter the host's existing Sentinel behavior. | Code review rule; documented explicitly |
| **C12 (Token server) → C7/C9** | The token server is not a Spring app. It should not be able to acquire a `CacheManager`. | POM |

### Required couplings (the only ones allowed)

```
C11 ──▶ C1                        (schema only)
C9  ──▶ C1,C2,C3,C4,C5,C6,C7,C8   (wiring only)
C5  ──▶ C1,C2,C3,C4 + sentinel-core + sentinel-cluster-client-default
C6  ──▶ C1,C2,C3,C4
C7  ──▶ C1,C3(soft)
C8  ──▶ C4 + nacos-client
C4  ──▶ C1,C2
C3  ──▶ C1
C2  ──▶ C1
C12 ──▶ C1 + sentinel-cluster-server-default
```

**C8 is the only module that imports `nacos-client`.** That single fact is what makes the rule engine (`C4`) testable in a plain JUnit test with no Nacos server, and it is worth preserving even under schedule pressure.

---

## Recommended Project Structure

A multi-module Maven reactor. The module split is **not** a code-organization nicety — it is the mechanism that enforces the coupling contract above. If two concerns are in the same jar, `bannedDependencies` cannot separate them.

```
foundation-parent/                          # BOM + parent POM. No code. Pins every version.
│
├── foundation-core/                        # C1 — THE ONLY shared contract
│   └── src/main/java/io/foundation/core/
│       ├── rule/           # Rule, RuleSet, RuleVersion, RuleEnvelope, CapabilityType
│       ├── decision/       # Decision, DecisionRecord, DecisionType, FailStrategy
│       ├── spi/            # RuleSource, DecisionSink, TokenService, ClusterGate
│       └── error/          # FoundationException hierarchy (unchecked only)
│
├── foundation-expression/                  # C2 — sandboxed, precompiled expressions
│   └── .../expression/
│       ├── ExpressionEngine.java           # SPI-facing facade
│       ├── CompiledExpression.java         # opaque, holds Aviator Expression
│       ├── SandboxPolicy.java             # limits: maxLength, maxSteps, maxDepth, maxNesting
│       └── ValidationResult.java
│
├── foundation-observer/                    # C3 — decision channel + metrics
│   └── .../observer/
│       ├── DecisionSink.java              # enqueue(); NEVER blocks, NEVER throws
│       ├── ring/RingBufferSink.java        # Disruptor, bounded, DropHandler exposed
│       ├── sink/LogAppenderSink.java       # dedicated Logback appender
│       ├── sink/HttpBatchSink.java
│       ├── metrics/FoundationMeters.java   # ALL meters pre-registered here
│       └── health/FoundationHealthContributor.java
│
├── foundation-rule/                        # C4 — parse → validate → compile → snapshot
│   └── .../rule/
│       ├── compile/RuleCompiler.java       # Rule[] → CompiledRuleSet
│       ├── snapshot/RuleSetSnapshot.java   # IMMUTABLE, version-stamped
│       ├── snapshot/SnapshotStore.java     # interface only — no Nacos here
│       ├── snapshot/LastKnownGoodStore.java# atomic temp-file + rename
│       ├── route/RoutePlan.java            # injectionPointId → resolved rules
│       └── validate/RuleValidator.java
│
├── foundation-ratelimit/                   # C5 — the 6-layer hot path
│   └── .../ratelimit/
│       ├── annotation/RateLimited.java
│       ├── inject/RateLimitInterceptor.java
│       ├── arg/ArgumentSource.java         # bounded extraction, no reflection on whole object
│       ├── gate/ClusterGate.java           # LOCAL <-> CLUSTER effective-mode switch
│       ├── gate/NacosRuleSourceHealthProbe.java
│       ├── sentinel/SentinelRuleInstaller.java
│       ├── sentinel/FlowRuleAssembler.java
│       └── local/LocalSlidingWindow.java   # used when gate is LOCAL
│
├── foundation-canary/                      # C6 — self-built evaluator
│   └── .../canary/
│       ├── annotation/CanaryRoute.java
│       ├── CanaryEvaluator.java
│       ├── strategy/WhitelistStrategy.java
│       ├── strategy/PercentageBucketStrategy.java   # stable hash, no rebucket
│       ├── strategy/TargetingKeyResolver.java       # P3 must fix precedence
│       └── inject/CanaryInterceptor.java
│
├── foundation-cache/                       # C7 — generic two-level cache
│   └── .../cache/
│       ├── TwoLevelCache.java
│       ├── key/KeyBuilder.java             # multi-dimension, canonical string form
│       ├── guard/PenetrationGuard.java     # null-result + bloom/bucket
│       ├── guard/StampedeGuard.java        # single-flight (L1) + distributed lock (L2)
│       ├── guard/AvalancheGuard.java       # TTL jitter, forced < l2Ttl
│       ├── evict/LocalEvictor.java
│       ├── evict/PubSubInvalidator.java    # KEY ONLY, no values
│       └── evict/ReconciliationTask.java   # periodic drift repair
│
├── foundation-nacos/                       # C8 — the ONLY module that knows Nacos exists
│   └── .../nacos/
│       ├── NacosRuleSource.java            # C4 SPI impl
│       ├── NacosConfigKeys.java           # DataId / Group / namespace constants
│       ├── RuleChangeListener.java         # callback = "something changed", re-fetch
│       ├── NacosSnapshotLoader.java        # fetch → validate → atomic swap
│       └── health/ClusterHealthProbe.java
│
├── foundation-autoconfigure/               # C9 — the ONLY module that knows Spring exists
│   └── .../autoconfigure/
│       ├── FoundationAutoConfiguration.java
│       ├── FoundationProperties.java      # @ConfigurationProperties, all prefixed foundation.*
│       ├── RatelimitAutoConfiguration.java
│       ├── CanaryAutoConfiguration.java
│       ├── CacheAutoConfiguration.java
│       ├── ObserverAutoConfiguration.java
│       ├── HealthContributorAutoConfiguration.java
│       └── META-INF/spring.factories      # Boot 2.7 style (NOT AutoConfiguration.imports)
│
├── foundation-starter/                     # C10 — POM only, zero code
│   └── pom.xml                             # convenience aggregate for consumers
│
├── foundation-console-api/                 # C11 — deployable control plane
│   └── .../console/api/
│       ├── domain/          # JPA entities: Rule, RuleVersion, Release, AuditLog
│       ├── repo/            # Spring Data repositories
│       ├── service/         # RuleService, ReleaseService, RollbackService, CanaryService
│       ├── api/             # @RestController — the FROZEN contract
│       ├── api/dto/         # request/response DTOs — never expose entities
│       ├── api/error/       # RFC 7807 ProblemDetail-style error shape
│       └── version/         # version assignment, copy-forward, content hash
│
├── foundation-token-server/                # C12 — deployable cluster token service
│   └── .../token/server/
│       ├── TokenServerBootstrap.java       # NOT Spring
│       ├── store/RedisTokenStore.java      # Lua, single atomic round trip
│       └── handler/                         # Sentinel ClusterTokenServer handlers
│
├── foundation-samples/                     # C13 — consumer reference + integration tests
│   ├── sample-ratelimit-service/
│   ├── sample-canary-service/
│   ├── sample-cache-service/
│   └── sample-offline-decision-consumer/  # proves the decision channel is drainable
│
└── sdk-compat-tests/                       # separate module set — consumer-side smoke tests
    ├── boot-2.6.13-smoke/                  # proves the skew trap is contained
    └── boot-2.7.6-smoke/
```

### Structure Rationale

- **`foundation-core` is a separate module because it is the entire inter-plane contract.** The control plane compiles against it and nothing else from the SDK. If the control plane ever needs a second class from the data plane, that is the signal the schema is wrong.
- **`foundation-expression` and `foundation-observer` are leaves below `foundation-rule`.** Both are genuinely reusable utilities with no opinion about rules, and both are the modules most likely to be audited (sandbox escape matrix, log redaction). Isolating them means an audit is a module-scoped review, not a codebase review.
- **`foundation-rule` owns parsing, validation, compilation, and snapshot — and nothing else.** Notably it has no Nacos, no Spring, no Sentinel. This is what makes the entire rule engine testable as a plain JUnit test with a `String` snapshot and no infrastructure. It is the single highest-leverage isolation decision in the project.
- **`foundation-ratelimit`, `foundation-canary`, `foundation-cache` are peers, not a hierarchy.** Rate limiting and canary are independent capabilities that happen to share a rule substrate. A business service that wants canary but not rate limiting should not acquire Sentinel transitively; splitting them is what makes that possible, and it also means a canary release never forces a rate-limit redeploy.
- **`foundation-nacos` exists solely as an adapter.** If it were merged into `foundation-rule`, the rule engine would inherit a gRPC client and a config-server dependency, and unit tests would need a Nacos stub. One small module buys a fully plain-JUnit testable core.
- **`foundation-autoconfigure` is the Spring firewall.** Every `@Configuration`, `@Bean`, `spring.factories` entry, and Spring type lives here. A consuming service that uses the SDK without Spring (or with a different Boot) still gets a usable core, because `foundation-core` … `foundation-cache` are plain Java with hand-written construction paths. The auto-config module delegates to those same constructors.
- **`foundation-starter` is a POM with no source directory.** Consumers who want the whole thing import it; consumers who want one capability import that module. Making the starter carry the full transitive set is fine — what matters is that it does not *prevent* narrower imports.
- **`foundation-console-api` and `foundation-token-server` are separate deployables in the same reactor.** They are in the reactor (not a separate repo) because both need `foundation-core` and the version must never drift. They are not in the SDK's dependency closure because the SDK must never need them.
- **`sdk-compat-tests` is its own module set rather than tests inside the SDK.** Testing "a consuming service with Boot 2.6.13 can use this jar" from inside the jar is circular — the test must be a genuine external consumer with its own dependency tree.

---

## Architectural Patterns

### Pattern 1: Compile-Once Immutable Snapshot

**What:** The only representation of rules on the hot path is a fully-compiled, immutable `RuleSetSnapshot` object. A single `AtomicReference` swap installs a new one. Nothing mutates a snapshot after construction; nothing reads the raw Nacos document at decision time.

**When to use:** Always. This is not optional for a Nacos-driven engine — it is the only way to satisfy both "expressions must be precompiled and cached" and "an invalid payload must not take effect partially."

**Trade-offs:**
- *Pro:* zero allocation, zero parsing, zero locking on the decision path. Rollback is a pointer move, not a data migration. A bad payload is detectable before it is ever visible.
- *Pro:* the compiler can be tested in isolation and fuzzed; the hot path has no way to reach it.
- *Con:* compiling a large rule set is not free, so it must happen off the decision path (it does — it happens on the Nacos listener thread).
- *Con:* a rule author cannot see "partial application" — either the whole new version is live or the old one is. This is correct, but it must be documented, because a team used to half-applied config will read it as a bug.
- *Con:* memory grows with distinct route plans. Mitigated by keying the cache on `ruleSetVersion` so old versions are collectable, and by bounding the LRU.

**Example:**
```java
// foundation-rule — compiled, immutable, published atomically
public final class RuleSetSnapshot {
    private final long version;
    private final long compiledAtEpochMillis;
    private final Map<String, RoutePlan> routePlans;      // unmodifiable
    private final byte[] contentHash;

    RuleSetSnapshot(long version, Map<String, RoutePlan> plans, byte[] hash) {
        this.version = version;
        this.compiledAtEpochMillis = System.currentTimeMillis();
        this.routePlans = Collections.unmodifiableMap(plans);
        this.contentHash = hash.clone();
    }
    public long version()      { return version; }
    public RoutePlan planFor(String injectionPointId) { return routePlans.get(injectionPointId); }
}

// foundation-ratelimit — the whole read on the hot path
final RuleSetSnapshot snap = holder.current();          // one volatile read
final RoutePlan plan = snap.planFor(pointId);
if (plan == null) { /* no rule matched -> fail-close default */ }
```

### Pattern 2: Argument Extraction Budget

**What:** Method arguments are exposed to expressions through a narrow, explicitly budgeted `ArgumentSource` — positional and named access only, over a small set of pre-projected primitives. Never the whole object graph.

**When to use:** Any expression that reads request data. This is the boundary that makes "no arbitrary method invocation" true *and* keeps expression evaluation off the reflection path.

**Trade-offs:**
- *Pro:* the sandbox stays closed even if the engine's own sandbox has a gap — there is nothing to reach. Evaluation cost is bounded and predictable.
- *Pro:* the projection is where PAN/card-number masking is enforced exactly once, for all three capabilities.
- *Con:* a rule that needs a nested field requires an explicit projection declaration. Slightly more authoring work, vastly lower blast radius.
- *Con:* projection code is hand-written per service. Accept it — it is the same work a serializer would do anyway, but under your control.

**Example:**
```java
// foundation-ratelimit — bounded extraction, evaluated once per decision
public final class ArgumentSource {
    private final Map<String, Object> values;   // immutable, size-capped
    private final Map<String, String> masked;  // parallel view for sensitive fields

    public Object get(String name) {
        if (values.size() > 64) throw new ArgumentBudgetExceeded(name);
        return values.get(name);
    }
    public String masked(String name) { return masked.get(name); }  // *** 掩码 ***
}
```

### Pattern 3: Cluster Gate (Availability as a First-Class State)

**What:** Cluster rate limiting is modeled as an explicit two-state machine — `CLUSTER` or `LOCAL` — driven by an out-of-band health signal, not by per-request exception handling. A scheduled probe transitions the state; the decision path only *reads* it.

**When to use:** Whenever a hot path depends on a remote service and must degrade. Per-request try/catch is not degradation, because you pay the timeout on every single request during the outage.

**Trade-offs:**
- *Pro:* the timeout cost is paid once per probe interval instead of once per request. Failure mode becomes predictable and observable.
- *Pro:* the effective mode is a first-class metric and health signal, so "the cluster limit silently became N× local" is an alert, not a mystery.
- *Con:* there is a bounded detection window — requests in flight during the transition still use the old mode. Acceptable, and the window is the probe interval, not the timeout.
- *Con:* two extra moving parts (probe + state) versus the obvious `try/catch`. Worth it: the obvious version is the one that turns a token-server blip into a latency incident on the authorization line.

**Example:**
```java
// foundation-ratelimit — off hot path, owns the transition
@Scheduled(fixedDelayString = "${foundation.ratelimit.cluster-probe-interval-ms:2000}")
void probe() {
    boolean reachable = tokenService.ping(deadline);
    ClusterGate.Mode next = reachable ? CLUSTER : LOCAL;
    if (gate.mode() != next) {
        log.warn("effective rate-limit mode {} -> {}", gate.mode(), next);
        gate.set(next);                       // atomic; decision path reads without locking
        meters.recordModeSwitch(next, reachable);
    }
}

// hot path reads only
if (gate.mode() == CLUSTER) { entry = tokenService.acquire(...); }  // ClusterTokenServer handles fallback
else                        { entry = localWindow.acquire(...); }   // no network at all
```

### Pattern 4: Versioned Invalidation with Reconciliation

**What:** Cache invalidation messages carry a monotonically increasing per-key sequence number. Receivers drop out-of-order and already-applied messages. A periodic reconciliation task re-reads authoritative state to repair any drift that pub/sub lost.

**When to use:** Any multi-instance local cache. Pub/sub is at-most-once; a design that trusts it alone has a permanent-staleness bug that only appears in production.

**Trade-offs:**
- *Pro:* bounded staleness is a *guaranteed property* (TTL cap), not a hope. Out-of-order delivery cannot resurrect stale entries.
- *Pro:* reconciliation is the only thing that makes "eventually consistent" mean eventually.
- *Con:* two mechanisms (events + reconciliation) instead of one. Justified by the at-most-once semantics.
- *Con:* reconciliation costs periodic reads. Keep it coarse and off-peak-shaped; it repairs drift, it does not serve reads.

**Example:**
```java
// foundation-cache — key-only invalidation, sequence-guarded
public void onInvalidate(InvalidateEvent ev) {
    if (ev.getSeq() <= appliedSeqFor(ev.getKey())) return;   // stale or duplicate -> drop
    localCache.invalidate(ev.getKey());
    appliedSeqFor.put(ev.getKey(), ev.getSeq());
    meters.recordInvalidation(ev.getKeyPrefix());            // prefix, never the raw key
}
```

### Pattern 5: Immutable Decision Record, Dedicated Channel

**What:** Every decision produces an immutable `DecisionRecord` carrying the traceability tuple (rule ID, rule version, quota at decision time, bucket, evaluation duration). The record is handed to a bounded ring buffer and drained by a background writer on a channel that is not the business logger.

**When to use:** From the first commit that makes a decision — not in a later "observability" phase. Decision history cannot be backfilled.

**Trade-offs:**
- *Pro:* the business thread does zero I/O and cannot be slowed by telemetry. Loss is bounded and *counted*, not silent.
- *Pro:* the record is a value object, so the three capabilities emit identically-shaped data and the offline consumer needs no per-capability parsing.
- *Con:* under sustained overload, some records are dropped. That is the explicit trade: bounded memory and never-blocked decisions over completeness. The drop count is exported as a metric precisely so the trade is visible.
- *Con:* a dedicated appender is a second logging config to maintain. Non-negotiable — routing decisions through the host's logger is how they get filtered away.

**Example:**
```java
// foundation-core — the contract, fixed before any capability exists
public final class DecisionRecord {
    private final String capability;      // RATELIMIT | CANARY | CACHE
    private final DecisionType type;     // ALLOW | REJECT | CANARY_HIT
    private final String ruleId;
    private final long   ruleVersion;    // 决策 → 规则版本
    private final Long   quotaAtDecision;
    private final String bucket;
    private final long   evalNanos;      // 评估耗时
    private final FailStrategy appliedFailStrategy;
    private final Map<String, String> extra;   // whitelist, extensible
}

// decision path — allocate, enqueue, move on. No logging, no I/O.
decisionSink.emit(DecisionRecord.builder()...build());
```

### Pattern 6: Degradation Is a Rule, Not a Branch

**What:** fail-close / fail-open is a per-rule declared property that the engine reads from the snapshot, defaulting per capability. There is no `if (configBroken)` branch in the decision code.

**When to use:** Any capability that can fail while a request is in flight. The two capabilities need *opposite* defaults (rate limit fail-close, canary fail-open) and per-rule overrides on top.

**Trade-offs:**
- *Pro:* the behavior under failure is reviewable as data, auditable in history, and versioned like everything else.
- *Pro:* removes the single worst class of bug in this domain — a hardcoded fail-open on the authorization line.
- *Con:* someone must set it. Force it: the API rejects a rule whose fail strategy is not explicitly stated, rather than inheriting a silent default.

**Example:**
```java
// foundation-rule — failure policy is part of the versioned schema
public final class RateLimitRule {
    private final FailStrategy onEvaluationFailure;  // required, no implicit default
    private final FailStrategy onNoRuleMatched;       // required
}
// capability-level default applies only when the rule does not exist at all
```

---

## Data Flow

### Request Flow

**The decision hot path (every call, in order).** No I/O, no parsing, no reflection on the decision itself.

```
Business method call (annotated @RateLimited / @CanaryRoute)
    ↓  interceptor (C9-wired, C5/C6 implementation)
    ↓  1. read AtomicReference<RuleSetSnapshot>          — one volatile read
    ↓  2. RoutePlan lookup by injectionPointId            — HashMap.get, O(1)
    ↓  3. ArgumentSource projection                       — bounded, pre-declared fields only
    ↓  4. precompiled predicate evaluate (sandboxed)      — Aviator, no parse, no reflection
    ↓  5. matched rule -> Sentinel Resource + ParamFlowRule
    ↓  6a. gate == CLUSTER -> Sentinel entry (ClusterTokenServer)   [1 network RTT]
    ↓  6b. gate == LOCAL    -> local sliding window                  [0 network]
    ↓  7. Decision assembled (ALLOW / REJECT + quota + reason)
    ↓  8. DecisionRecord.emit()  — ring buffer put, non-blocking
    ↓
Business method proceeds (or throws the declared rejection exception)
```

**Rule change flow (control plane → data plane).** Fully off the decision path.

```
Console / API client
    ↓  POST /api/v1/rules/{id}/releases
Management API (C11)
    ↓  validate -> assign next version -> persist RuleVersion row (content hash, author, reason)
    ↓  materialize current version set -> serialize to canonical JSON
    ↓  Nacos Config: publishConfig (DataId=..., Group=FOUNDATION_RULES, namespace=...)
Nacos server
    ↓  gRPC push -> ConfigChangeNotify
NacosRuleSource (C8)
    ↓  re-fetch config (callback only signals "changed", it does not carry content)
    ↓  parse -> RuleValidator -> RuleCompiler (expressions compiled here, on this thread)
    ↓  ┌─ valid   -> LastKnownGoodStore.write (atomic temp+rename) -> AtomicReference.set(new)
    │              -> L1 cache for old (version,key) route plans is naturally orphaned
    └─ invalid -> keep current snapshot, increment ruleSyncError metric, log to SDK health
Hot path now reads the new version with zero restart, zero partial application
```

**Cache invalidation flow.** Key-only, sequence-guarded, reconciled.

```
Cache.delete(key) by any instance
    ↓  1. local L1 invalidate (this instance)
    ↓  2. L2 delete (Redis DEL)
    ↓  3. PUBLISH foundation:cache:invalidate  { key, seq }   — VALUE NEVER LEAVES THE CACHE MODULE
    ↓  4. every instance onInvalidate(seq):
    ↓       seq <= appliedSeq -> drop (out-of-order / duplicate)
    ↓       else L1 invalidate + record seq
    ↓  5. ReconciliationTask (scheduled, coarse): compare L1 keys vs. authoritative version
    ↓       -> repair drift left by lost pub/sub messages
```

### State Management

```
                    ┌──────────────────────────────────────────┐
   Nacos push ────▶ │ RuleSetSnapshot  (immutable, versioned) │  ◀── LastKnownGoodStore (disk, atomic)
                    │  · compiled expressions                  │        (survives restart; this is
                    │  · RoutePlan map                         │         what makes cold start safe)
                    │  · fail strategies per rule              │
                    └────────────────┬─────────────────────────┘
                                     │ AtomicReference.set (single writer, many readers)
                                     ▼
              ┌───────────────────────────────────────────────┐
              │ C5 ratelimit state  (per service instance)    │
              │  · installed Sentinel FlowRule/ParamFlowRule  │  (version-stamped set, replaced not merged)
              │  · local sliding windows (mode=LOCAL)         │
              │  · ClusterGate effective mode                │  (probe-driven, not exception-driven)
              └───────────────────────────────────────────────┘

              ┌───────────────────────────────────────────────┐
              │ C6 canary state    (per service instance)     │
              │  · weighted version set (canary payload)      │  (bucket count immutable)
              │  · whitelist entries                           │
              └───────────────────────────────────────────────┘

              ┌───────────────────────────────────────────────┐
              │ C7 cache state     (per service instance)     │
              │  · L1 Caffeine (bounded, l1Ttl << l2Ttl)      │
              │  · appliedSeq map (monotonic per key)         │
              │  · in-flight single-flight entries            │
              └───────────────────────────────────────────────┘

              ┌───────────────────────────────────────────────┐
              │ C3 observer state  (per service instance)     │
              │  · bounded ring buffer (lossy, counted)       │
              │  · pending batch (in-flight writer)            │
              └───────────────────────────────────────────────┘

   Control plane (C11, single deployable)
              Rule (logical) 1 ──1──▶ RuleVersion (immutable, monotonic) ──1──▶ Release (audit: who/when/why)
                    │                            │
                    └── materialized current set ┴──▶ Nacos (Nacos holds ONLY this; it is a
                                                        distribution target, not the system of record)
```

**State-management rule:** the system of record for a rule is the control-plane database, including its full version history. Nacos holds only the currently materialized set. A rule must be recoverable from history after a Nacos wipe, which is only true if Nacos is treated as derived state.

### Key Data Flows

1. **Rule publish → live effect:** console write → versioned DB row → Nacos publish → gRPC notify → re-fetch → validate → compile → atomic swap. Ordering guarantee: per DataId, Nacos delivers the latest content; the SDK's own ordering guarantee comes from the version number it validates on load, not from delivery order.

2. **Rollback:** console action → read historical `RuleVersion.content` → **write it forward as a NEW version** (never re-point the old one) → normal publish flow. This is why rollback uses the same code path as publish, and why it cannot leave instances disagreeing about which version is live.

3. **Decision → traceability:** business thread → `DecisionRecord` (ruleId, ruleVersion, quota, bucket, evalNanos, failStrategy) → ring buffer → batch writer → dedicated sink. Deliberately *not* a synchronous call chain, because the decision path must never do I/O.

4. **Degradation:** scheduled probe → `ClusterGate` state transition → hot-path branch change + metric + log. Failure of the token server is therefore visible as a *state*, not inferred from a spike in errors.

5. **Cache consistency:** write-through delete on the owning instance → L2 delete → pub/sub fan-out (key only) → per-instance L1 evict with sequence guard → periodic reconciliation as the backstop. Bounded staleness is guaranteed by `l1Ttl` regardless of whether any event arrived.

6. **Cold start:** process starts → load LKG snapshot from disk (if present) → install into `AtomicReference` → **start serving** → connect to Nacos in the background → first successful load supersedes. If no LKG exists, start serving with defaults and report `ruleLoaded=false` + a loud health signal. A missing config center degrades the instance; it does not prevent it from booting.

---

## Rule Versioning and Rollback

This is the mechanism the whole product rests on ("每一次决策事后都能追溯到具体规则和规则版本"), so the semantics are stated exactly.

### The version envelope

Every Nacos payload and every decision record carries the same envelope, defined in `foundation-core`:

```
RuleSetEnvelope
├── schemaVersion      : int     // wire format, bumped ONLY on incompatible change
├── capability         : RATELIMIT | CANARY
├── serviceScope       : String  // which consuming service this applies to
├── ruleSetVersion     : long    // monotonic, assigned by the control plane
├── contentHash        : String  // SHA-256 of the canonical payload
├── publishedAt        : long
├── publishedBy        : String
└── rules              : Rule[]  // the materialized set for this version
```

Two independent version numbers, deliberately:

| Field | Assigned by | Changes when | Consumer validates |
|-------|-------------|--------------|-------------------|
| `schemaVersion` | code author | The wire shape changes incompatibly | Reject if unknown |
| `ruleSetVersion` | control plane | Any rule in the set is edited/published | Reject if it goes **backwards** |

`ruleSetVersion` is global-per-DataId, not per-rule, because a *set* is what gets published atomically. Per-rule versions would let two rules in one snapshot disagree about ordering, which is exactly the partial-application bug the snapshot design exists to prevent.

### Lifecycle

```
DRAFT ──publish──▶ PUBLISHED ──canary rollout──▶ CANARY ──promote──▶ PUBLISHED
   │                   │                            │
   │                   │                            └──abort──▶ PUBLISHED (previous)
   │                   │
   │                   ├──rollback──▶ ROLLED_BACK  (content written forward as a NEW version)
   │                   │
   └──discard──▶ (deleted, never reachable from any published version)

DEPRECATED — a logical Rule no longer in the current set. Its version history is retained forever.
```

`DRAFT` and `PUBLISHED` are separate records. This is what makes "edit a live rule" safe: editing produces a new `DRAFT` row, and publishing is an explicit act. There is no path where saving a form in the console changes production behavior.

### Rollback semantics (the important part)

**Rollback is a forward write, not a rewind.**

```
Rollback(ruleId=R, toVersion=7, currentVersion=9):
   1. read RuleVersion[R, 7].content            (immutable historical bytes)
   2. validate it against the CURRENT schemaVersion   ← may fail; must be handled, not ignored
   3. assign ruleSetVersion = 10                (monotonic; 10 > 9)
   4. copy content forward into RuleVersion[R, 10]
   5. write Release{ action=ROLLBACK, newVersion=10, sourceVersion=7, reason=<required> }
   6. materialize + publish to Nacos            (identical code path as a normal publish)
```

Why not re-point version 9 to version 7's content? Three reasons, each of which has bitten real systems:

1. **Instance divergence.** Instances that already pulled v9 would keep serving it. "Which version is live?" would become "which version did *this* instance last load?" — and a decision record's `ruleSetVersion` would no longer identify the rule content. Traceability, the core value proposition, breaks.
2. **Immutable history.** Mutating v9 destroys the only record of what was actually deployed and served. Post-incident analysis of a bad canary becomes impossible.
3. **Cache orphans.** Route-plan caches keyed by `(injectionPointId, ruleSetVersion)` assume content is immutable per version. Re-pointing makes stale cached plans silently wrong.

The cost is that history grows by one row per rollback. That is the correct trade — history is the product.

**One special case:** if the *schema* has moved on since v7, step 2 fails and rollback cannot be a byte copy. The API must then return a structured `409 SCHEMA_INCOMPATIBLE` with the version pair, and the operator must migrate forward instead. Silently coercing an old payload into a new schema is how a rollback becomes a data-corruption incident.

### Canary versions

Canary does not get its own envelope type — it is a *variant* of the RATELIMIT/CANARY envelope:

```
CanarySet
├── canaryId            : String
├── targetInjectionPoint: String
├── strategies          : [ { type=WHITELIST, entries=[...] },
│                          { type=PERCENTAGE, bucketCount=1000, versionWeights={v10:5, v9:95} } ]
├── targetingKeyOrder   : [ "userId", "cardBin", "channel" ]   // precedence, fixed per rule
└── promoteTo / abortTo : version references
```

- **Precedence is fixed and declared:** whitelist > percentage > hash bucket. It is data in the envelope, not control flow, so it is reviewable and versioned.
- **`bucketCount` is immutable once canary starts.** Changing it re-buckets every user, so the same user flips versions mid-canary. The API rejects a rebucket unless `allowRebucket=true` is passed explicitly, and that flag is itself audited.
- **A missing targeting key means "no match" — never "random."** Falling back to a random bucket turns a misconfigured rule into a nondeterministic production incident. This is the single most important correctness rule in the canary module.
- **The selected version is recorded in the `DecisionRecord`.** Without that, a canary's blast radius cannot be reconstructed.

### Traceability chain (end to end)

```
Console "publish v10"  →  RuleVersion row (hash H10, author, timestamp)
                        →  Nacos DataId content hash = H10
                        →  SDK computes hash on load, compares to envelope, stores it in the snapshot
                        →  DecisionRecord{ ruleId, ruleSetVersion=10, contentHash=H10, ... }
                        →  offline consumer can join decision → version → exact bytes
```

The `contentHash` round trip is what makes this a *proof* rather than a claim: if a payload were ever mutated in flight or on disk, the hash comparison at load time fails the load, and the mismatch is reported instead of silently served.

---

## Nacos Failure Behavior

Nacos is a single point of failure for *distribution*, and the design must make that survivable. This section states exactly what happens in each failure mode — because "the config center is down" is not a valid reason for an authorization service to stop answering.

### Failure mode matrix

| # | Failure | Detection | SDK behavior | Business impact | Health signal |
|---|---------|-----------|--------------|-----------------|---------------|
| F1 | Nacos unreachable at **cold start, LKG exists** | gRPC connect fails | Load LKG snapshot from disk, serve immediately, retry Nacos in background | None — last known rules apply | `foundation.rule.sync.state=DISCONNECTED` (warn) |
| F2 | Nacos unreachable at **cold start, no LKG** | gRPC connect fails | Start with **empty** snapshot; capability defaults apply (ratelimit fail-close, canary fail-open, cache pass-through) | Ratelimited traffic is rejected beyond default quota; canary is off; cache cold | `foundation.rule.loaded=false` (health DOWN) — loud, not silent |
| F3 | Nacos dies **after** rules are loaded | gRPC disconnect / probe | Keep serving the **in-memory** snapshot indefinitely. No timeout, no expiry, no TTL on rules. Reconnect in background | None until a genuinely newer rule is needed | `DISCONNECTED` + `lastSuccessfulSyncEpoch` gauge |
| F4 | Nacos pushes a **malformed / invalid** payload | `RuleValidator` or `RuleCompiler` throws | **Reject the whole payload atomically.** Keep the previous snapshot. Never apply partially | None — old rules keep working | `foundation.rule.sync.error++` with reason enum; health WARN |
| F5 | Nacos pushes a **valid but semantically wrong** payload (e.g. bucket count changed, referential integrity broken) | Cross-field validation in `RuleValidator` | Reject, same as F4. Validation must cover *relationships*, not just shape | None | same as F4, with a distinct reason code |
| F6 | Nacos push is **out of order / older version arrives late** | `ruleSetVersion` comparison at load | Discard if `incoming.ruleSetVersion <= current` | None | metric `foundation.rule.stalePush.dropped` |
| F7 | Nacos **namespace / group misconfiguration** | Content hash or DataId lookup returns empty/error | Treat as F2/F4 — do not silently "start with no rules and look healthy." Assert namespace at startup in a `SmartLifecycle` and log the resolved namespace + group | Depends on F2 | health DOWN with the misconfiguration spelled out |
| F8 | Nacos serves a **payload that fails hash verification** | `contentHash` mismatch vs. envelope | Reject (this is tamper/corruption detection) | None | health WARN + ERROR log, reason `HASH_MISMATCH` |
| F9 | Nacos is slow / times out on re-fetch | Client timeout | Listener thread blocks only; **decision path is unaffected** because it never touches Nacos | None | `foundation.rule.fetch.latency` histogram |

### The three rules that make this work

**Rule 1 — The hot path never talks to Nacos.** Not for reads, not for version checks, not for "is my config current." Every one of those designs turns a config-center latency spike into a request-path latency spike. The only Nacos interaction is on the listener thread, which touches exactly one thing: an `AtomicReference`.

**Rule 2 — Last-known-good must survive a restart, so it must be on disk.** The Nacos client's own snapshot is an *internal cache file* with no format guarantee, no validation, and no cross-version contract. The SDK keeps its own:

```
~/.foundation/rules/
├── ratelimit-{service}.v{ruleSetVersion}.json      # content-addressed by hash
├── ratelimit-{service}.LKG                          # pointer file: { version, hash, path }
└── canary-{service}.LKG
```

Written with temp-file + `ATOMIC_MOVE` rename, then the pointer is replaced. A crash mid-write leaves the previous pointer intact — a half-written payload can never become last-known-good. Retain the last N versions (small) so a rollback can also be served from disk if Nacos is down during an incident.

**Rule 3 — Rejection is atomic and total, never partial.** A payload is either fully valid and becomes the new snapshot, or the previous snapshot is retained in its entirety. This is enforced structurally: `RuleSetSnapshot` is immutable and swapped by reference, so there is no code path that can apply rules 1–50 of 51. This is a structural guarantee, not a discipline.

### What the SDK must NOT do

| Anti-behavior | Why it's wrong |
|---|---|
| Fail startup when Nacos is down and no LKG exists | Converts a config problem into a full outage. Better: start degraded, make the health signal unmissable, and let fail-close defaults protect the backend. |
| Apply rules field-by-field as they validate | Produces a snapshot that was never tested as a unit and that no one can roll back to. |
| Expire rules after some TTL and revert to defaults | A config center outage would then silently *remove* rate limits. Removing protection is worse than keeping stale protection. |
| Retry Nacos synchronously on the decision path "just to be sure the config is fresh" | Reintroduces the network call the design exists to remove. Freshness is a background concern. |
| Report healthy when running with no rules | The single most dangerous false-healthy state. `ruleLoaded` must be a first-class health contributor. |
| Trust the Nacos client's built-in snapshot file for LKG | It is unvalidated, unversioned, and its format changes between client releases. It is a cache, not a contract. |

### An optional strict mode

Two legitimate operating postures, selected by one property, defaulting to the resilient one:

| Mode | Behavior | When to use |
|---|---|---|
| `foundation.rule.startup-policy=RESILIENT` (default) | Start with LKG or defaults; health signals degrade loudly | Production; a service that must stay up |
| `foundation.rule.startup-policy=STRICT` | Fail fast if no valid snapshot is available at startup | Test/CI, and regulated flows where "running with no rate limits" is unacceptable |

STRICT exists so the *test suite* can assert the failure, and so an operator can opt into hard-fail without a code change. It is not the default because a config outage should not take down an authorization service.

---

## Hot-Path Cost Budget

The project must "quantify the latency overhead the SDK itself introduces." This section is the budget the benchmark must demonstrate. Numbers are **design targets, not measurements** — the actual figures come from the JMH harness in P2. The point of writing them down now is that a budget nobody states is a budget nobody meets.

### Per-layer cost target (p99, excluding the cluster RTT)

| # | Layer | What runs | Target | Notes |
|---|-------|-----------|--------|-------|
| 1 | Snapshot read | 1 volatile read | ~0 | Non-contended. Free. |
| 2 | `RoutePlan` lookup | 1 `HashMap.get` | < 100 ns | Key is `String`; intern injection point IDs at build time to make `equals` hit the identity fast path. |
| 3 | Argument projection | Field reads on pre-projected values | < 1 µs | Bounded by declared field count (cap 64). No reflection, no tree walk. If a rule needs nested access, the cost is paid at projection *build* time, not decision time. |
| 4 | Expression evaluation | 1 precompiled Aviator `execute` | < 5 µs | **This is the number that must be benchmarked, not estimated.** Includes the sandbox step budget. Must be measured with a JMH benchmark over a corpus of realistic rules, worst case included. |
| 5 | Sentinel rule lookup | Resource + ParamFlowRule map | ~1 µs | Sentinel's own overhead. Not ours to optimize, but ours to measure. |
| 6 | **Token acquisition (CLUSTER)** | 1 network RTT to token server | Dominant | Set separately as a network budget, not an SDK overhead budget. Reported as a distinct p99 so the two are never conflated. |
| 6' | **Token acquisition (LOCAL)** | In-memory sliding window | < 1 µs | The reason the local mode exists. |
| 7 | Decision assembly | Object allocation | < 1 µs | Allocate freely; these are short-lived and the JVM handles them. Do **not** hand-optimize this layer. |
| 8 | `DecisionRecord.emit()` | Ring buffer put | < 1 µs | Must be measured under *contention with a full buffer* — the pathological case is the full-buffer drop path, not the fast path. |

**Total SDK overhead target: p99 < 10 µs, excluding the cluster RTT.** For an authorization path in the millisecond range this is noise; for a high-throughput accounting line it is still acceptable. If the measured number exceeds this, the first suspect is always layer 4.

### The three costs that actually bite

Everything else is small. These are the ones that turn into incidents:

1. **The cluster RTT (layer 6).** One network round trip per limited request, at authorization-line QPS. This is the real budget item, and it is why `ClusterGate` exists — during an outage, the per-request timeout is multiplied by QPS, and the service degrades far worse than the token server did. The gate converts an N× timeout storm into a bounded probe. This is the single most important performance decision in the design.

2. **A full ring buffer under load.** If the sink's writer falls behind, the producer either blocks (destroys the latency budget and can cascade into the business thread pool) or drops. It must drop, and it must count the drops. Load testing must include a deliberately-slow sink to prove the business path stays flat.

3. **Expression evaluation under adversarial rules.** A rule that is expensive to evaluate (deep nesting, long chains) is a per-request cost, and rules arrive from config — so they are, in effect, untrusted input on the hot path. This is why the sandbox needs a *step budget*, not just a sandbox: a sandboxed expression can still be slow. A slow expression must be rejected at compile time or capped at evaluate time, and the evaluation must never run on a shared, unbounded thread pool.

### Cost-control rules (architectural, not tuning)

- **No allocation-heavy work on the decision path.** The `DecisionRecord` and its `extra` map are the only allocations. `extra` is capped and pre-sized. A `HashMap` built per decision from a variable-size list is the classic offender.
- **No locks on the decision path.** The snapshot read is a volatile read; `ClusterGate` is an `AtomicReference`; Caffeine handles its own concurrency. A `synchronized` block anywhere in layers 1–8 invalidates the whole budget under load.
- **No unbounded structures.** The route-plan LRU, the ring buffer, the `appliedSeq` map, and the single-flight map must all be bounded. A Nacos-pushed config change must never be able to grow heap without limit — that is a remote-triggerable OOM.
- **Metrics cardinality is a performance concern, not just a hygiene one.** An unbounded tag value on a hot-path counter will melt the metrics registry, which sits on the same JVM and the same threads. See PITFALLS.md P11.

---

## Build Order and Dependency DAG

The module graph below is not advisory. It is the compile-time expression of the coupling contract, and Maven's reactor order is derived from it. A cycle here is a design error, not a build problem to be worked around with `<exclusions>`.

```
                              foundation-parent (BOM)
                                     │
                                     ▼
                            foundation-core (C1)
                                     │
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                      ▼
  foundation-expression (C2)  foundation-observer (C3)  foundation-console-api (C11)
              │                                              │
              ▼                                              │
      foundation-rule (C4) ◀────────────────────────────────┘
        │        │        │
        │        │        └──────────────┐
        ▼        ▼                       ▼
  foundation-ratelimit (C5)  foundation-canary (C6)  foundation-nacos (C8)
        │  └──────────────┬──────────────┘
        │                 ▼
        │        foundation-cache (C7)
        │                 │
        ▼                 │
  foundation-token-server (C12)
        │
        ▼
  foundation-autoconfigure (C9)  ← the only module that may see Spring
        │
        ▼
  foundation-starter (C10, POM only)
        │
        ▼
  foundation-samples (C13)  →  sdk-compat-tests
```

### Maven reactor order

The `reactor` builds in topological order; the `<modules>` declaration in the parent POM must list them in this order, because Maven uses declaration order to break ties among independent modules (a broken tie means flaky build ordering, not a broken build):

```xml
<modules>
  <module>foundation-core</module>
  <module>foundation-expression</module>
  <module>foundation-observer</module>
  <module>foundation-console-api</module>   <!-- independent of data plane; early = parallel CI -->
  <module>foundation-rule</module>
  <module>foundation-ratelimit</module>
  <module>foundation-canary</module>
  <module>foundation-cache</module>
  <module>foundation-nacos</module>
  <module>foundation-token-server</module>
  <module>foundation-autoconfigure</module>
  <module>foundation-starter</module>
  <module>foundation-samples</module>
  <module>sdk-compat-tests</module>
</modules>
```

### Why this order specifically

| Position | Module | The dependency that forces it |
|---|---|---|
| 1 | `core` | Nothing. It must be first because everything depends on it, and it must have no dependencies of its own — a `core` with a dependency is a `core` that is not a contract. |
| 2–4 | `expression`, `observer`, `console-api` | Depend only on `core`, and are mutually independent. Placing `console-api` here is deliberate: it proves the control plane compiles with nothing but the schema, and it parallelizes in CI. |
| 5 | `rule` | Needs the expression compiler to produce compiled predicates, and the record type to attach. Does **not** need Nacos — the `RuleSource` is an interface it declares. |
| 6–8 | `ratelimit`, `canary`, `cache` | Peers. Each needs `rule`; `cache` additionally takes a soft dependency on `observer` for its hit/miss counters. None needs another. If a future change makes `ratelimit` need `cache`, that is the signal the boundaries are wrong. |
| 9 | `nacos` | Needs `rule`'s `RuleSource` SPI. Placed after the capabilities so that the Nacos dependency lands at the outside of the data plane rather than in the middle of it. |
| 10 | `token-server` | Needs the token SPI from `core` and the cluster server artifact. It is a deployable, so it sits after all libraries. |
| 11 | `autoconfigure` | Needs to reference every bean it wires. Last among libraries, by necessity. |
| 12 | `starter` | Aggregates. Pure ordering convenience. |
| 13–14 | `samples`, `compat-tests` | Consumers. They must build last because they are the only modules that can catch a broken public surface. |

### The enforcer rules that make the graph real

Without these, the graph is a diagram that drifts. These belong in the parent POM so they apply to every module:

```xml
<!-- 1. JDK 8 is a hard constraint. A newer compiler must not silently allow a
        Java 11+ transitive (e.g. Caffeine 3.x) into a JDK 8 runtime. -->
<plugin>maven-enforcer-plugin</plugin>
<executableGoals>
  <goal>enforce</goal>
</executableGoals>
<configuration>
  <rules>
    <requireJavaVersion><version>[1.8,1.9)</version></requireJavaVersion>
    <dependencyConvergence/>          <!-- catches nacos-client 1.4.2 vs 2.2.0 -->
    <bannedDependencies>
      <excludes>
        <!-- The Spring firewall: enforceable in every module except autoconfigure/console-api -->
        <exclude>org.springframework:*</exclude>
        <exclude>org.springframework.boot:*</exclude>
        <!-- A second config center / a second rule store, both explicitly rejected -->
        <exclude>io.etcd:*</exclude>
        <exclude>com.alibaba.csp:sentinel-datasource-redis</exclude>
      </excludes>
      <includes>org.springframework.boot:spring-boot-starter</includes>
    </bannedDependencies>
  </rules>
</configuration>
```

Two details that are easy to get wrong and expensive to discover late:

- **`bannedDependencies` `<includes>` are the exemption list.** A rule with both `<excludes>` and `<includes>` bans the excludes *except* in the listed modules. Omitting the `<includes>` block means `autoconfigure` cannot use Spring either, and the build fails for a reason that looks like a configuration bug.
- **`dependencyConvergence` is not optional.** The `nacos-client` version conflict between the SCA BOM and Sentinel's transitive dependency is silent at compile time and only surfaces as `NoSuchMethodError` at runtime in production. See PITFALLS.md P9.

---

## Anti-Patterns

These are specific to this domain — embedded SDK + external config distribution — and each one is a realistic outcome, not a strawman.

### Anti-Pattern 1: The Config Center on the Request Path

**What people do:** "The rule might have changed, so let me re-check Nacos" — or cache the config client and read a version stamp per request to "make sure we're fresh."

**Why it's wrong:** A config-center latency spike becomes a request-path latency spike, instantly and at full QPS. It also means a slow Nacos degrades the authorization line, which is the one thing the SDK exists to protect. Freshness is a background concern; the data plane's job is to serve from memory.

**Do this instead:** One `AtomicReference` to an immutable snapshot, swapped by a background listener. Add a staleness *metric* if you need to know — never a staleness *check*.

### Anti-Pattern 2: Partial Application of a Rejected Config

**What people do:** Parse the payload, apply the rules that validate, log a warning for the ones that don't, and keep going.

**Why it's wrong:** You have now created a rule set that nobody authored, nobody tested, and nobody can roll back to — because it never existed as a version. When it misbehaves, the operator's only recovery is to hand-edit Nacos. This is the single most expensive mistake available in a config-driven system.

**Do this instead:** Validate and compile the entire payload into a new immutable snapshot; swap it in whole or not at all. Surface the specific failing rule in the rejection reason so the fix is actionable.

### Anti-Pattern 3: TTL on Rules

**What people do:** Reuse the caching infrastructure and give the rule set a TTL, because "stale config is bad."

**Why it's wrong:** It conflates two completely different staleness semantics. Expiring a *rule* removes a *protection* — an authorization service that loses its rate limits on a timer is less safe, not more. Also, the rule set is the one thing the system cannot reconstruct from anywhere except Nacos, so a TTL converts a temporary Nacos problem into a permanent loss of protection.

**Do this instead:** Rules have no TTL. They are replaced by version, or they persist. Enforce `l1Ttl << l2Ttl` for the *data* cache instead, where bounded staleness is the actual requirement.

### Anti-Pattern 4: Rollback by Re-Pointing a Version

**What people do:** Update version 9's content to be what version 7 used to hold, or republish v7 as "current."

**Why it's wrong:** Instances that already loaded v9 keep serving it, so the system is now in a state where "the live version" is not a well-defined question — and a decision record's version no longer identifies the rule content. Traceability, the product's core value, is destroyed. It also mutates the historical record, so post-incident analysis becomes impossible.

**Do this instead:** Copy the old content forward as a new monotonic version. Rollback then uses exactly the same publish path as everything else, and history is append-only.

### Anti-Pattern 5: The Business Logger as the Decision Channel

**What people do:** `log.info("ratelimit decision ...")`, or log to an MDC-backed correlation id, and treat that as the decision record.

**Why it's wrong:** The host's log config owns that channel. Rules for level, sampling, and rotation are set for business readability, not for a high-volume, structured, never-dropped stream. In practice decisions get filtered out at INFO, sampled away under load, and rotated before anyone can trace an incident. Worse, putting a request id in MDC means the decision record inherits the *business* log's retention, which is the opposite of what an audit trail needs.

**Do this instead:** A dedicated sink with its own appender/handler, fed by a bounded ring buffer, with drop counts exported as metrics. Decisions become data, not text.

### Anti-Pattern 6: Per-Entity Sentinel Resources

**What people do:** Build a Sentinel resource name per merchant, per card BIN, or per account, so each entity gets its own limit.

**Why it's wrong:** Sentinel's resource map is in-memory and unbounded. Resource count then grows with the number of *entities* rather than the number of *rules*, so a system with 100k BINs allocates 100k flow rules, their windows, and their statistics nodes — permanently, and re-created on every restart. This is a remote-triggerable OOM triggered by a config push.

**Do this instead:** Keep Sentinel resources at the *injection point* level (bounded, authored by developers) and use `ParamFlowRule` keyed by the extracted argument to get per-entity counting. Cardinality is then bounded by the flow rule, not by the entity. This is the architectural justification for the custom router layer.

### Anti-Pattern 7: Per-Request try/catch as Degradation

**What people do:** Wrap the token call in try/catch and fall back to the local window on exception.

**Why it's wrong:** It works exactly once per request, which is the problem. During a token-server outage every request pays the full connect+timeout before falling back — so the "graceful degradation" is *slower* than either mode alone, multiplied by QPS. The service goes down for a reason that has nothing to do with rate limiting.

**Do this instead:** An out-of-band probe flips a state that the hot path reads. The outage costs one probe, not one timeout per request. Keep the try/catch as a backstop, but never as the mechanism.

### Anti-Pattern 8: SDK That Assumes It Owns the Application

**What people do:** The SDK auto-configures Sentinel globally, registers a `HealthIndicator` that the host didn't ask for, installs a global interceptor, or writes to the host's logger config — because the SDK is the "platform" library.

**Why it's wrong:** The SDK runs inside a JVM it does not own. Any global mutation is a version-skew incident waiting for a host that uses the same library differently. Two teams in one company, on different Boot versions, will get different behavior from nominally identical code.

**Do this instead:** Everything opt-in and namespaced under `foundation.*`. Never touch Sentinel's global config, never add a global interceptor, never write to the host's logging configuration. Add an explicit startup assertion that reports the resolved versions, so a skew is visible immediately rather than at 3 a.m.

### Anti-Pattern 9: Unbounded Tags on Hot-Path Metrics

**What people do:** Tag a decision counter with the rule id, the card BIN, or the merchant id "so it's more useful."

**Why it's wrong:** It is a performance bug, not a hygiene bug. Each distinct tag value creates a new time series in the registry, held in the same JVM, on threads shared with request handling. A high-cardinality tag on a hot counter exhausts heap and can stall the metrics registry — taking down the business service to improve its observability. It is also a compliance problem in this domain: a card BIN or account in a metric label is sensitive data in a place nobody scrubs.

**Do this instead:** Pre-register a fixed set of meters. Tag on bounded enums (capability, decision type, fail reason, effective mode). Per-entity detail belongs in the *decision record* (which has redaction), never in a metric label.

### Anti-Pattern 10: Caching the Decision, Not the Data

**What people do:** "The rate-limit decision for this card won't change in 100 ms, so cache it."

**Why it's wrong:** A cached *decision* is a hole in the protection: N concurrent requests share one quota check, so the effective limit becomes N× the configured one, and the quota consumed by the cache hit is invisible to the window accounting. The limit stops being a limit.

**Do this instead:** Cache *data* (with bounded staleness, which is acceptable and expected), never decisions. Decisions are cheap, side-effecting, and must be counted every time.

---

## Scaling Considerations

Scale here is not "millions of users." It is **number of consuming service instances**, **rule volume**, and **decision QPS** — the three axes that actually break this design.

| Scale | What changes | What does NOT change |
|-------|--------------|----------------------|
| **2 consuming services (the delivery target)** | Nothing. The whole SDK is 2–3 jars, one Nacos DataId pair, one token server. | Module boundaries, snapshot model, Nacos as sole channel, fail strategies. |
| **10–20 services / 200+ instances** | Namespace and Group discipline becomes mandatory (per-service DataIds, one namespace). Rule volume per service stays small. L1 caches and ring buffers must be size-capped per service, since 200 instances × unbounded LRU is a fleet-wide heap problem. | The architecture. This is the scale the design is actually validated at. |
| **High decision QPS (authorization peak)** | Cluster token RTT dominates. The token server needs capacity headroom (≥3× measured peak) and a documented horizontal-scaling path. `ClusterGate` becomes load-bearing rather than a nicety. | The hot path structure. The budget in the cost section is what must be defended. |
| **High-cardinality entities (100k+ BINs)** | Nothing may scale with entity count. Sentinel resources stay at injection-point level; per-entity counting goes through `ParamFlowRule`; cache keys are built from a bounded field set. | The routing model. If anything needs to scale with entity count, the router is wrong. |
| **Many tenants (future console sub-app)** | `tenantId` is already a reserved field, so this is a filter + an authorization check, not a refactor. Isolation logic lands with the console. | `/api/v1` versioning. Adding tenant path segments later would be a breaking change — see the contract section. |

### Scaling Priorities (in the order they will actually bite)

1. **First bottleneck: the cluster token RTT.** It is a network hop on the hot path and it is the only place the SDK's latency depends on something outside the JVM. It breaks first under authorization-peak QPS, and it breaks *worst* during a token-server outage (per-request timeout × QPS). **Fix path:** capacity headroom first (cheap), then horizontal scaling of the token server, then the `ClusterGate` tuning. The architecture is already correct for this; the work is operational.
2. **Second bottleneck: L1 cache memory across the fleet.** Each instance's Caffeine cache is bounded individually, but 200 instances × a too-large max size is a fleet-wide heap problem that appears as gradual memory pressure rather than a single failure. **Fix path:** per-service cache sizing as a documented default, `sensitive=true` refusing L1, and a `foundation.cache.l1.size` metric so the fleet's aggregate is visible.
3. **Third bottleneck: metrics cardinality and the decision sink.** A high-cardinality tag or a sink that cannot keep up both show up as shared-JVM pressure on the request threads. **Fix path:** pre-registered bounded meters (already mandated), and drop-count metrics on the ring buffer so backpressure is visible before it becomes an incident.
4. **Fourth bottleneck: rule compilation latency on a large rule set.** Compilation is off the hot path, so it degrades rule *freshness*, not request latency. Acceptable at this scale; revisit only if rule sets grow by orders of magnitude.

**Explicitly not a scaling concern here:** the number of tenants (v1 has no tenant isolation), the number of capabilities (three, fixed), and non-Java SDKs (explicitly out of scope). Designing for those now would add abstraction with no consumer.

---

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| **Nacos Config** (already deployed) | Push-pull: gRPC long-poll push → callback → re-fetch → validate → compile → atomic swap. SDK-side only via `foundation-nacos`. | The callback signals *that* something changed, not *what* — a re-fetch is always required. Reuse the existing deployment; do not introduce a second config center. Namespace/group/dataId conventions are part of the contract (see Nacos Failure Behavior). Keep `nacos-client` at the SCA BOM's version and exclude Sentinel's transitive copy. |
| **Redis** (L2 cache + cluster token store) | Lettuce, shared connection, one Lua round trip for atomic token acquisition. | Same Redis can serve both roles, but they should be logically separated (key prefixes, ideally separate instances) so a token-server flush cannot take out business cache. RESP3-aware client; no per-call connection checkout. |
| **Sentinel Cluster Token Server** (`foundation-token-server`) | Sentinel's own `ClusterTokenServer` handler interface over the cluster transport. The SDK never talks to it directly — it goes through `ClusterTokenServerHelper` inside `sentinel-core`. | Known capacity ceiling and correctness caveats (namespace bucket leakage, averaged thresholds, `windowIntervalMs > 1000ms` inaccuracy) — see PITFALLS.md P6. The `ClusterGate` local-mode fallback is the mitigation that must exist regardless of which token implementation is chosen. |
| **Micrometer / Prometheus** | Pre-registered meters, scraped via Boot actuator. | Pull, not push. The SDK exposes meters; it does not own the scrape endpoint (the host does). Cardinality discipline is mandatory — see Anti-Pattern 9. |
| **Decision sink** (log / HTTP / Kafka-style consumer) | Ring buffer → batch writer → dedicated channel. | The sink is pluggable behind `DecisionSink`. The offline consumer is a *sample service*, not an SDK dependency — the SDK must not require a running consumer. |
| **Control-plane database** (C11 only) | Spring Data repositories over the versioned rule schema. | The system of record. The data plane never touches it — that is the entire point of the Nacos-only distribution rule. |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `core` ↔ everything | Compile-time types only | `core` has zero runtime dependencies. If a type moves here, ask whether the *control plane* needs it — if yes, it belongs here; if only the data plane needs it, it does not. |
| `rule` ↔ `nacos` | `RuleSource` SPI (interface in `rule`, impl in `nacos`) | The one boundary that keeps Nacos out of the rule engine. This is what makes the engine testable with a `String` snapshot. |
| `rule` ↔ `ratelimit`/`canary` | Compiled `RoutePlan` + `RuleSetSnapshot` | Capabilities consume *compiled* artifacts, never raw rules. Compilation is a build-time concept that capabilities never pay for. |
| `ratelimit`/`canary`/`cache` ↔ `observer` | `DecisionRecord` + `DecisionSink.enqueue()` | One-way, fire-and-forget. The observer never calls back into a capability. |
| `cache` ↔ pub/sub bus | `InvalidateEvent` (key + seq only) | Business code publishes nothing. Values never leave the cache module. |
| `autoconfigure` ↔ all capabilities | Spring `@Bean` wiring delegating to hand-written constructors | Spring is a wiring layer, not a design layer. Every capability must be constructible without it — otherwise the Spring firewall is decorative. |
| `console-api` ↔ `core` | Schema types only | Exactly one internal dependency. The control plane is deployable with the schema and nothing else. |

---

## Phase-Seam Map (architecture → roadmap)

Where these architecture decisions must land, so the roadmap inherits the boundaries instead of rediscovering them. Phase names match PITFALLS.md's phase mapping.

| Phase | Architecture work that must exist at its end | Boundary established | Research flag |
|-------|------------------------------------------------|---------------------|----------------|
| **P1 规则内核与表达式沙箱** | `core` schema + version envelope + `DecisionRecord` contract + `expression` sandbox + `rule` compiler/validator/`RuleSetSnapshot`/LKG store. Module skeleton with the full reactor + enforcer. | Spring firewall, `nacos` as adapter, `rule` free of infra | **Needs research.** Expression engine selection (`PROJECT.md:114`) needs a reproducible JDK 8 attack-case matrix *and* a benchmark before it can be defensible. The `DecisionRecord` contract and ring buffer must land here, not in P5 — history cannot be backfilled (P15). |
| **P2 限流执行与故障策略** | `ratelimit` 6-layer hot path, Sentinel rule installer, `ClusterGate` + probe, `token-server`, fail-close default with per-rule override. | `ratelimit` ↔ `canary` separation; token server as separate deployable | **Needs research.** Token service implementation (`PROJECT.md:112`) needs a quantified Redis+Lua vs. Sentinel `ClusterTokenServer` comparison (throughput, quota correctness, horizontal scaling, ops cost) and a measured capacity ceiling. |
| **P3 灰度评估器** | `canary` evaluator: whitelist > percentage > stable hash, immutable bucket count, targeting-key precedence, fail-open default, version recorded in the decision. | `canary` as a peer of `ratelimit` (not a sub-feature) | Targeting-key resolution order and the rebucket-rejection rule must be fixed before coding. |
| **P4 两级缓存** | `cache` L1/L2, three guards, multi-dim keys, key-only pub/sub with sequence guard, reconciliation task, `sensitive` flag blocking L1. | `cache` ↔ `rule` separation; cache never sees rule types | Two-level consistency protocol (`PROJECT.md:113`) and the v1 sensitive-data guardrails need a decision before code. |
| **P5 决策可观测性** | `observer` sink wiring, batch writer, meters, health contributors, offline sample consumer. | Observer collects from all three without any capability depending on another | Standard pattern — low research need. The risk is *retrofit*: the `DecisionRecord` contract was fixed in P1, so P5 wires transport only. If P1 drifted, P5 cannot recover. |
| **P6 管理 API 与接入验证** | `console-api` CRUD + release/publish + canary rollout + rollback + audit, frozen contract with tenant/permission placeholders, `sdk-compat-tests` on two Boot versions, ≥2 real service integrations, latency report. | Control plane depends on `core` only; `/api/v1` frozen | Version-compatibility matrix and security-path review before delivery. The tenant/permission placeholders need an explicit v1 risk statement, not silence. |

**Cross-phase invariants that must not be traded away later** (each is cheap now and expensive to retrofit):

| Invariant | Established in | Cost if broken later |
|---|---|---|
| `DecisionRecord` field set | P1 | Decision history becomes non-joinable; already-recorded data can't be enriched. |
| Version envelope + monotonic `ruleSetVersion` | P1 | Traceability and rollback both lose their foundation. |
| Copy-forward rollback semantics | P1/P6 | Every historical version becomes ambiguous. |
| Module boundaries + enforcer rules | P1 | Requires a multi-module split under time pressure, mid-milestone. |
| LKG on disk with atomic writes | P1 | Cold start after a Nacos outage becomes an outage. |
| `ClusterGate` as a state, not try/catch | P2 | Token-server outage becomes a latency incident on the authorization line. |
| Pre-registered bounded meters | P3 (first capability that emits) | Metrics cardinality is very hard to walk back once dashboards depend on the labels. |
| `/api/v1` contract frozen | P6 | Console sub-app (a separate project) builds against a contract that then changes. |

---

## Risks and Open Questions

### Architectural risks

| # | Risk | Severity | Mitigation / where it is handled |
|---|------|----------|-------------------------------|
| A1 | **Version skew reaches the host.** SCA 2021.0.5.0 officially maps to Boot 2.6.13, not 2.7.6; a consuming service on a different Boot could get different behavior. | High | `bannedDependencies` Spring firewall + `sdk-compat-tests` on both Boot versions + startup assertion that logs resolved versions. PITFALLS.md P9. |
| A2 | **A remote config push can exhaust host heap or threads.** Rules arrive from Nacos; unbounded structures or a slow expression are remotely triggerable. | High | Compile-time limits, step budgets, bounded LRU/ring/`appliedSeq`, and a high-cardinality load test in P1. Anti-Patterns 6, 9. |
| A3 | **Cluster mode silently becomes N× local.** If the token server has no rules, Sentinel falls back to local counting *without* an error, and the effective limit multiplies by instance count. | High | `ClusterGate` health probe as an explicit state + per-mode metrics + alert on the switch. Pattern 3; PITFALLS.md P5. |
| A4 | **Cache pub/sub is at-most-once.** A lost invalidation leaves one instance serving stale data indefinitely. | Medium | Monotonic sequence guard + short L1 TTL as a hard staleness bound + reconciliation task. Pattern 4; PITFALLS.md P10. |
| A5 | **Sensitive data lands in the wrong place.** A generic cache or an unbounded metric tag can retain a card number. | High | `sensitive=true` blocks L1; `DecisionRecord` has a redaction policy; meter tags bounded to enums. Anti-Patterns 9; PITFALLS.md P11. |
| A6 | **The SDK's Spring-free promise is decorative** if any capability is only constructible via auto-config. | Medium | Hand-written constructors in every capability; a test that boots the core with no Spring context at all. |

### Open questions (must be answered in a phase, not assumed)

1. **Token service implementation** — Sentinel `ClusterTokenServer` vs. Redis+Lua custom. Blocks P2 sizing. The architecture is deliberately agnostic: both plug in behind `TokenService`/`ClusterGate`, and the *gate* is required either way.
2. **Expression engine** — Aviator 5.4.4 is the STACK.md recommendation with a real sandbox; the attack-case matrix and benchmark are still required before it is defensible. Blocks P1 sign-off.
3. **Two-level cache consistency protocol** — read-through vs. write-around, and the invalidation-vs-update trade. Blocks P4.
4. **Canary targeting-key precedence** — the three data lines may need different orders. Blocks P3.
5. **Latency budget number** — `PROJECT.md` states the overhead must be quantified but the budget value is TBD. The p99 < 10 µs target in the cost section is a *proposal* and needs sign-off before P2 benchmarks are judged pass/fail.
6. **Where the offline decision consumer lives** — a sample service is proposed; if the decision stream must reach a durable audit store, that is a new component and arguably a new sub-application.
7. **Whether the token server is deployed at all** for the two target services, or whether local-only mode is acceptable initially. If so, P2's cluster half is deferred, not cancelled — the requirements still stand.

---

## Management API Contract (frozen this milestone)

The console UI is a **separate sub-application** built by another team against this contract. That makes this the one interface in the project where a change is an external break, not an internal refactor. It gets a different standard of care than every other boundary in this document.

### Design rules

| Rule | Rationale |
|------|-----------|
| Versioned from day one: `/api/v1` | The console ships separately and later. Without a version segment, "add tenant scoping" becomes a breaking change instead of `/api/v2`. |
| **Never put `tenantId` in the path.** It is a field/header/filter, not a path segment | A path segment cannot be added later without breaking every existing client. A field or header can. This is the single most consequential contract decision. |
| DTOs, never JPA entities | Entity shape changes with the schema; the wire shape must not. Exposing entities freezes the database, which is the opposite of what the console needs. |
| Every mutating call is **idempotent by key** | Console retries, double-clicks, and network timeouts are normal. A retried "publish" that creates a second version is a production incident. `Idempotency-Key` header, replay-safe by contract. |
| Concurrency via `ETag` / `If-Match` | Two operators editing one rule must not silently overwrite. `412 Precondition Failed` on a stale ETag makes the conflict visible instead of losing an edit. |
| Structured errors, one shape, always | `application/problem+json` (RFC 7807) with a stable `type` code. A console cannot build good error UX against prose strings, and prose strings are not a contract. |
| Every response carries the current `ruleSetVersion` | Lets the console show "you are editing a stale rule" without a second call, and gives support a version to quote. |
| Additive-only evolution within `/api/v1` | New optional fields only. Never repurpose an existing field's meaning, never change a type, never make an optional field required. |
| Mutations are audited, reads are not | The audit log is the rollback mechanism's evidence. Auditing every read produces noise and costs storage for no safety benefit. |

### Reserved extension points (v1 ships the slot, not the logic)

```
/api/v1/rules                  CRUD on logical rules
/api/v1/rules/{id}/versions     immutable version history (read-only)
/api/v1/rules/{id}/releases     publish / rollback / canary — audited, idempotent
/api/v1/rules/{id}/canary       canary start / promote / abort
/api/v1/services/{svc}/rulesets current materialized set per service (read-only, mirrors Nacos)
/api/v1/audit                   release and rollback audit trail
```

Reserved in the schema, unimplemented in v1, so the console can be designed against the real shape:

| Field | v1 behavior | Reserved for |
|-------|-------------|--------------|
| `tenantId` | Echoed, always a single default tenant; **no isolation enforced** | The console sub-app's multi-tenant requirement |
| `principal` / `permissions` | Derived from a single service account; recorded in audit | Per-operator roles — audit must be attributable to a *person* eventually |
| Opaque IDs (`rul_01H...`) | Time-sortable opaque strings | Keeping internal PK strategy invisible to the console |
| `meta` (extensible map) | Round-tripped, never interpreted | Capability-specific fields without a contract change |
| `schemaVersion` on payloads | Validated, monotonic, bump-only | Wire-format evolution |
| Pagination | Cursor-based, stable sort | The audit log is append-only; offset pagination would be wrong under concurrent writes |

**The v1 risk that must be stated out loud, not left implicit:** the reserved tenancy and permission slots mean the console sub-app will build a *UI* for multi-tenant and role-based access against an API that enforces neither. If the console's expectations are set from the reserved fields rather than the v1 behavior list, the integration fails at the seam between two sub-applications. This belongs in the P6 handoff documentation, not in a code comment.

### Why the API has no business endpoints

There is no `POST /rules/{id}/check`, no `GET /decisions`, no "test this expression" endpoint. Three reasons, each of which closes a tempting door:

1. **A decision endpoint would be a second evaluation engine.** The data plane's evaluator is the one that is sandboxed, step-budgeted, and benchmarked. A copy in the control plane would drift from it, and the console's answer would not be the answer production gives.
2. **Testing an expression in the control plane moves untrusted code into the control plane.** The sandbox lives with the compiler. Evaluating a candidate expression at publish time must happen in a quarantined context, not in the request thread of the API that holds the rule database.
3. **Decision history is a data-stream problem, not an API problem.** Decision records go to the dedicated sink (Pattern 5) and are queried by whatever consumes that stream. Adding a query API to the control plane would couple the audit data plane to the rule control plane for no benefit.

---

## Verification Summary

How each of the eight architectural questions is answered, and how a reviewer can check the answer.

| # | Question | Answered in | How to verify |
|---|----------|-------------|---------------|
| 1 | What are the component boundaries? | Component Responsibilities + The Coupling Contract | Run `mvn dependency:tree` per module; assert no `spring-*` outside C9/C11, and exactly one internal dep for C11. |
| 2 | What are the control flow and data flow? | System Overview + Data Flow (6 key flows) | Trace one publish end-to-end against a running Nacos; trace one decision end-to-end and confirm zero I/O. |
| 3 | How does rule versioning and rollback work? | Rule Versioning and Rollback | Publish v10, roll back, assert v11 exists with v7's content hash and that v9 is byte-identical to what was served. |
| 4 | What happens when Nacos fails? | Nacos Failure Behavior (F1–F9) | Kill Nacos in a test: cold start with/without LKG, mid-flight disconnect, malformed push, out-of-order push. |
| 5 | What does the hot path cost? | Hot-Path Cost Budget | JMH benchmark per layer + an in-environment load test; report p99 with the cluster RTT separated out. |
| 6 | How is dependency isolation enforced? | Coupling Contract + Build Order (enforcer config) | `mvn dependency:analyze`, `mvn enforcer:enforce`, and the two-Boot-version compat tests. |
| 7 | What is the build order? | Build Order and Dependency DAG | `mvn -T 1C clean install` from scratch on a clean clone; reactor order must match the declared module order. |
| 8 | What are the hints for future phases? | Phase-Seam Map + Risks and Open Questions | Each phase's entry lists the invariant it must establish and the one that must not be traded away. |

**Honest limits of this document.** The component boundaries, the coupling prohibitions, the Nacos failure semantics, the versioning model, and the dependency-isolation rules are all derived from verified source/POM/documentation and are HIGH confidence. The following are **design proposals, not findings**, and are labeled as such above: the p99 < 10 µs budget (needs a signed-off number and a benchmark), the `ClusterGate` probe interval, the L1/L2 TTL ratios, the buffer sizes, and the whole Management API contract shape. The token-service implementation is deliberately left open behind an interface.

---

## Sources

**Nacos — distribution and failure semantics**
- Nacos config publish / subscribe / listen semantics (callback signals change; content must be re-fetched) — https://nacos.io/en/docs/latest/manual/user/config/publish-query-listen/ (HIGH)
- Spring Cloud Alibaba 2021.0.5.0 release notes and Nacos client version (2.2.0) — https://github.com/alibaba/spring-cloud-alibaba/releases/tag/2021.0.5.0 (HIGH)
- Spring Cloud Alibaba ↔ Spring Boot version matrix (2021.0.x maps to Boot 2.6.x/2.7.x) — https://sca.aliyun.com/en/docs/2021/overview/version-explain/ (HIGH)
- Nacos client snapshot files are internal caches with no cross-version format guarantee — nacos-client source, `LocalConfigInfoProcessor` / `ClientWorker` (HIGH)

**Sentinel — cluster rate limiting**
- Sentinel cluster flow control: per-request token fetch, `fallbackToLocalWhenFail`, and the token-server-availability model — https://sentinelguard.io/en-us/docs/cluster-flow-control.html (HIGH)
- Silent fallback to local counting when the token server has no rules — https://github.com/alibaba/Sentinel/issues/2457 (HIGH)
- Cluster flow control known issues: ~30k QPS namespace ceiling, averaged thresholds, bucket leakage — https://github.com/alibaba/Sentinel/issues/1639 (HIGH)
- `windowIntervalMs > 1000ms` cluster inaccuracy — https://github.com/alibaba/Sentinel/pull/3407 (HIGH)
- `FlowRuleManager` load semantics — previous-version rules are **replaced**, not merged (source) — https://github.com/alibaba/Sentinel/blob/master/sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/block/flow/FlowRuleManager.java (HIGH)
- `ParamFlowRule` per-parameter counting (the basis for per-entity limits without per-entity resources) — https://sentinelguard.io/en-us/docs/param-flow.html (HIGH)

**Expression sandboxing**
- AviatorScript 5.4.4 sandbox mode (`enableSandboxMode`, disabled features, loop/eval limits) and the 5.4.3 → 5.4.4 sandbox escape fix — https://github.com/killme2008/aviator (HIGH)
- SpEL sandbox is not a security boundary (the reason SpEL is excluded outright) — CVE-2026-59283 and Groovy AST injection analysis (HIGH)
- ReDoS / catastrophic-backtracking expression risk — https://codeql.github.io/codeql-query-help/java/java-redos/ (HIGH)
- Expression engine comparison baseline (Aviator / QLExpress / SpEL) — https://github.com/howiefh/expression-engine-benchmark (MEDIUM)

**Spring Boot / dependency isolation**
- Spring Boot 2.7.6 auto-configuration and the `spring.factories` mechanism (Boot 2.7 style; `AutoConfiguration.imports` is 3.x) — https://docs.spring.io/spring-boot/docs/2.7.6/reference/html/using.html#using.auto-configuration (HIGH)
- Spring Boot 2.7.6 managed dependency versions (Caffeine 2.9.3, Lettuce 6.1.10, Micrometer 1.9.6, Jackson 2.13.4) — https://docs.spring.io/spring-boot/docs/2.7.6/reference/html/dependency-versions.html (HIGH)
- `maven-enforcer-plugin` `bannedDependencies` semantics, including the `<includes>` exemption list — https://maven.apache.org/enforcer/maven-enforcer-plugin/banned-dependencies-mojo.html (HIGH)
- `maven-enforcer-plugin` `dependencyConvergence` — https://maven.apache.org/enforcer/maven-enforcer-plugin/dependency-convergence-mojo.html (HIGH)
- Boot plugin `repackage` is for executables, not libraries — https://docs.spring.io/spring-boot/docs/2.7.6/reference/html/maven-plugins.html (HIGH)
- `sentinel-datasource-nacos` transitively pulls `nacos-client`; requires exclusion (source POM, 1.8.6) — https://github.com/alibaba/Sentinel (HIGH)
- `spring-cloud-starter-alibaba-sentinel` transitively includes the cluster **server** artifact (source POM, 2021.0.5.0) — https://github.com/alibaba/spring-cloud-alibaba (HIGH)
- Revapi / japicmp for library binary-compatibility gates — https://revapi.org/ , https://github.com/siom79/japicmp (HIGH)

**Caching**
- Hybrid L1/L2 cache with pub/sub invalidation (implementation reference) — https://github.com/nwwarm/spring-redis-hybrid-cache (MEDIUM)
- Redis rate limiting via Lua, single atomic round trip — https://redis.io/docs/latest/develop/use-atomicity/ (HIGH)
- Lettuce shared-connection model (vs. Jedis per-call checkout) — https://github.com/redis/lettuce (HIGH)
- Caffeine 2.9.3 is the last Java 8 line (3.x requires Java 11) — https://github.com/ben-manes/caffeine (HIGH)

**Observability**
- ADR-003 telemetry out-of-process: zero network I/O on the business path, bounded buffering, lossy-but-counted — `/Users/travis/IdeaProjects/realtime/docs/adr/ADR-003-telemetry-out-of-process.md` (HIGH — project-internal decision)
- Disruptor bounded ring buffer, `BlockingWaitStrategy` avoided for producers — https://github.com/LMAX-Exchange/disruptor (HIGH)
- Micrometer / Prometheus naming and cardinality discipline — https://docs.spring.io/spring-boot/docs/2.7.6/reference/html/actuator.html#actuator.metrics (HIGH)

**Canary / progressive delivery**
- Stable-hash percentage rollout semantics, and why re-bucketing mid-rollout is unsafe — https://developers.cloudflare.com/flagship/targeting/percentage-rollouts/ (HIGH)
- RFC 7807 `application/problem+json` error format for the frozen API contract — https://datatracker.ietf.org/doc/html/rfc7807 (HIGH)
- ETag / `If-Match` conditional requests for optimistic concurrency — https://datatracker.ietf.org/doc/html/rfc7232 (HIGH)
- `Idempotency-Key` draft pattern for retry-safe mutations — https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header (MEDIUM — still a draft; treat as a convention, not a standard)

**Domain constraints**
- PCI DSS Quick Reference Guide (sensitive authentication data retention, logging, masking) — https://listings.pcisecuritystandards.org/documents/PCI%5FDSS-QRG-v3%5F2%5F1.pdf (HIGH)

---

*Architecture research for: 信用卡实时数据系统 · 基础能力平台 (embedded foundation-capability SDK + control plane)*
*Researched: 2026-09-29 · Companion documents: STACK.md (versions), FEATURES.md (scope), PITFALLS.md (failure modes)*
*Highest-value sections for roadmap construction: **The Coupling Contract**, **Phase-Seam Map**, and **Cross-phase invariants** — the first two are cheap to establish in P1 and expensive to retrofit later.*
