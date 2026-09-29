# Stack Research

**Domain:** Embedded real-time credit-card capability SDK (in-process library, JDK 8 / Spring Boot 2.x)
**Researched:** 2026-09-29
**Confidence:** HIGH for version pins and API surfaces (verified against Maven Central POMs + shipped bytecode); MEDIUM for latency figures (environment-dependent, requires in-environment benchmark)

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Java (JDK) | 1.8 | Runtime target | Fixed by project. No virtual threads, `javax.*` namespace (not `jakarta.*`), all library bytecode must be major version ≤ 52. |
| Spring Boot | 2.7.6 | Spring context, config, actuator | Fixed by project. Pairs with Spring Cloud 2021.0.5.0. Provides the auto-configuration for Nacos, Redis, and metrics used below. |
| Spring Cloud | 2021.0.5.0 | Release-train alignment | Fixed by project. 2021.0.x is the train that supports Boot 2.6.x/2.7.x, so 2.7.6 is in range. |
| Spring Cloud Alibaba | 2021.0.5.0 | Nacos/Sentinel integration + BOM | Fixed by project. The BOM is the **single authority** for Sentinel and Nacos client versions — do not hand-pin them. |
| Sentinel (`sentinel-core`) | 1.8.6 | Sliding-window rate limiting | Resolved from the SCA 2021.0.5.0 BOM (`sentinel.version=1.8.6`). Verified Java 8 bytecode (major 52). Provides `ParamFlowRule` for per-card counting; the SDK's custom layer resolves annotation/expression → Sentinel rule, and Sentinel performs the actual window accounting. |
| Nacos client | 2.2.0 | Config center | Resolved from the SCA BOM (`nacos.client.version=2.2.0`). Reuses the already-deployed Nacos; no second config center. See the dependency-conflict trap in Version Compatibility. |
| Aviator | 5.4.4 | Sandboxed expression evaluation for Nacos-pushed rules | Last Java-8 line. `enableSandboxMode()` (since 5.4.3) disables `Module`, `NewInstance`, `InternalVars`, `StaticFields`, `StaticMethods` and reflection, plus loop/evaluation limits — exactly the "no arbitrary method invocation" requirement. Ships `validate()`, `useLRUExpressionCache(int)`, `invalidateCache(String)`. |
| Caffeine | 2.9.3 | L1 local cache | Last Java-8 line (3.0.0+ requires Java 11). Managed by Boot 2.7.6. Verified major 52. Bounded LRU, so Nacos-pushed rule churn cannot grow the heap unbounded. |
| Lettuce | 6.1.10.RELEASE | L2 distributed cache + cluster token layer client | Boot 2.7.6's managed Redis client: Netty-based, shared connection, non-blocking, RESP3-aware. Chosen over Jedis because it shares one connection across the token hot path instead of checking out a socket per call. |
| Redis | 5.0+ (server) | L2 cache store + cluster token coordination | Single dependency serving both the distributed cache and the cluster token layer. Lua gives one atomic round trip for token acquisition. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `sentinel-datasource-nacos` | 1.8.6 | Persists Sentinel rules to Nacos | Required. Managed by the SCA BOM at `${sentinel.version}`. **Must** be declared with a `nacos-client` exclusion — see the trap below. |
| `sentinel-parameter-flow-control` | 1.8.6 | `ParamFlowRule` support | Required for per-card limits. |
| `sentinel-datasource-redis` | 1.8.6 | Nacos-independent Sentinel rule persistence | **Do not use.** Rules live in Nacos per the project decision; adding a second rule store creates a conflict the project explicitly rejected. |
| Micrometer | 1.9.6 | Metrics facade | Required. Managed by Boot 2.7.6. Exposes Sentinel pass/reject/block counts, canary evaluation counts, cache hit ratios, and token-layer latency histograms. |
| `micrometer-registry-prometheus` | 1.9.6 | Prometheus scrape endpoint | Required. Pulls `io.prometheus:simpleclient_common:0.15.0` (transitively managed). |
| `io.prometheus:simpleclient_common` | 0.15.0 | Prometheus client model | Transitive from the registry above. Do **not** pin independently — let the registry manage it. |
| Jackson | 2.13.4.20221013 | JSON for Nacos rule payloads | Managed by Boot 2.7.6 (Jackson BOM). Needed to deserialize Nacos-pushed rule documents. |
| SLF4J / Logback | 1.7.36 / 1.2.11 | Logging | Managed by Boot 2.7.6. Note: Logback 1.2.x is the correct pairing for SLF4J 1.7.x; Logback 1.3+/2.x require SLF4J 2.x and will not bind. |
| Lombok | 1.18.24 | Boilerplate | Managed by Boot 2.7.6. Optional — use only if the surrounding project already does. |
| JUnit Jupiter | 5.8.2 | Unit/integration tests | Managed by Boot 2.7.6. Pair with `spring-boot-starter-test`. |
| `spring-boot-starter-data-redis` | 2.7.6 | `RedisTemplate` + Lettuce wiring | Required. Brings Lettuce 6.1.10.RELEASE and commons-pool2. |
| `spring-boot-starter-actuator` | 2.7.6 | Health/metrics endpoints | Required — health indicator is how you detect cluster-mode degradation. |
| Netty | 4.1.x (transitive) | Redis client + Sentinel cluster transport | Transitive only. Required if any Sentinel cluster artifact is used; excluded in the recommended design. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Maven | Build / dependency resolution | Fixed. The SCA and Boot BOMs must be imported as `pom`/`import` scope in `dependencyManagement` **before** any sentinel or nacos-client declaration, or Maven will resolve the transitive `1.4.2` client (see trap). |
| `maven-enforcer-plugin` (`dependencyConvergence`) | Fail the build on version conflict | **Required.** The `nacos-client` 1.4.2-vs-2.2.0 conflict is silent at compile time and only surfaces as `NoSuchMethodError` at runtime. Enforce convergence in CI. |
| `maven-enforcer-plugin` (`requireJavaVersion`) | Guard the JDK 8 contract | Pin the build to Java 8 so a newer JDK cannot silently pull a Java 11+ transitive (e.g. Caffeine 3.x). |
| Revapi / japicmp | Binary-compatibility gate on the SDK | The SDK is a library consumed by other services; a public-API break is a release incident. |
| JMH | Hot-path latency benchmark | Required to replace the MEDIUM-confidence latency numbers in this document with measured values. Benchmark `Aviator.execute` on a precompiled `Expression` and the Redis Lua token call separately. |
| `-XX:+HeapDumpOnOutOfMemoryError` + Metaspace monitor | Catch the Aviator codegen leak | Symptom of a missing `validate()`-before-compile guard is `MetaspaceUsage` climbing with rule count. See the pattern in Stack Patterns by Variant. |

## Installation

```xml
<!-- 1. BOMs FIRST — order matters. Any sentinel/nacos dep declared before these
     will resolve the wrong transitive versions. -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>2.7.6</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>2021.0.5.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>com.alibaba.cloud</groupId>
      <artifactId>spring-cloud-alibaba-dependencies</artifactId>
      <version>2021.0.5.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Rate limiting core -->
  <dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-core</artifactId>
  </dependency>
  <dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-parameter-flow-control</artifactId>
  </dependency>

  <!-- Rule persistence. The exclusion is MANDATORY: the 1.8.6 artifact
       hard-declares nacos-client 1.4.2 in its own POM. -->
  <dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
    <exclusions>
      <exclusion>
        <groupId>com.alibaba.nacos</groupId>
        <artifactId>nacos-client</artifactId>
      </exclusion>
    </exclusions>
  </dependency>
  <dependency>
    <groupId>com.alibaba.nacos</groupId>
    <artifactId>nacos-client</artifactId>  <!-- version comes from the SCA BOM: 2.2.0 -->
  </dependency>

  <!-- Expression evaluation -->
  <dependency>
    <groupId>com.googlecode.aviator</groupId>
    <artifactId>aviator</artifactId>
    <version>5.4.4</version>
  </dependency>

  <!-- Caching + metrics + Redis -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
  </dependency>

  <!-- Tests -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Aviator 5.4.4 | QLExpress4 4.1.3 (Java 8) | If sandbox-mode enforcement is judged insufficient after a security review, or if Aviator's ASM codegen is disallowed by an ops constraint (e.g. a managed JVM with a hard `MaxMetaspaceSize` and no ability to run the codegen smoke test). QLExpress4 4.1.3 defaults to isolation mode, supports compile caching, and does not generate classes, so it has no metaspace profile to tune. Trade-off: it is an interpreter, so hot-path throughput is expected to be lower — **must** be benchmarked with JMH before adoption. |
| Aviator 5.4.4 | Self-built restricted DSL (no third-party engine) | If the security requirement "prohibit arbitrary method invocation" is interpreted as "no general-purpose scripting engine in the process at all." Highest cost: you own parsing, error messages, operator documentation, and the entire test surface. Only worth it if a compliance review rejects embedding any script engine. |
| Sentinel 1.8.6 `sentinel-core` local rules + Redis-Lua coordination layer | Sentinel 1.8.6 built-in cluster mode (`sentinel-cluster-client-default` + `sentinel-cluster-server-default`) | Only if the team accepts one additional blocking network hop per request and a new always-on infrastructure service. See "What NOT to Use" for why the built-in cluster path is not the recommendation despite being the same version. |
| Lettuce 6.1.10.RELEASE | Jedis 3.8.0 (also Boot-managed) | If the host application already standardises on Jedis and adding a second Redis client is unacceptable. Otherwise Lettuce is preferred: one shared connection and a Netty event loop instead of a per-call socket checkout, which matters on a hot path. Do not put both on the classpath. |
| Nacos 2.2.0 client (from SCA BOM) | Nacos 1.4.x client | Never. 1.4.x is what `sentinel-datasource-nacos:1.8.6` drags in transitively; it cannot talk to a Nacos 2.x server over gRPC. See the trap in Version Compatibility. |
| Reuse the deployed Nacos | A second config centre dedicated to this SDK | Only if the existing Nacos namespace cannot be given a dedicated group/dataId for SDK rules. The project decision is to reuse it; a new dataId under a dedicated group is the isolation mechanism, not a new server. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `sentinel-cluster-client-default` + `sentinel-cluster-server-default` (Sentinel's built-in cluster mode) | Verified in the 1.8.6 bytecode: `NettyTransportClient.sendRequest` calls `ChannelPromise.await(getRequestTimeout())` — a **blocking wait on the calling thread** with `requestTimeout` defaulting to **20 ms** (and `connectTimeout` **10,000 ms**). So every rate-limited request in cluster mode can add up to a 20 ms stall on the caller's thread, with no way for the caller to bound it. It is one more deployed service with no failover of its own, and the token server holds all cluster state in memory. | Keep Sentinel in local mode and put the cross-node counter in a Lua script on Redis (see the pattern below). One network hop, script is atomic, Redis already has replication/sentinel, and the SDK owns the timeout so it can be capped well below 20 ms. |
| QLExpress 3.3.4 | Java 8 compatible but ships with sandboxing effectively off by default and has documented sandbox-escape concerns. It is not acceptable for expressions pushed from a config server. | QLExpress4 4.1.3 (isolation by default) or, preferably, Aviator 5.4.4 with `enableSandboxMode()`. |
| Aviator compiled **per request** (i.e. calling `execute(String, env)` on the hot path) | Aviator's ASM mode generates a class per compiled expression. A rule pushed to Nacos with a syntax error can generate bytecode and then fail parsing, which historically accumulated in metaspace and could OOM the JVM. The hot path must never see a string. | `validate(String)` → `compile(expr, true, cached)` → cache the resulting `Expression` keyed by the rule ID. Call `AviatorEvaluator.execute(expression, env)`. See the pattern below. |
| Spring Expression Language (SpEL) for the Nacos-pushed rules | SpEL is not a sandbox. It exposes method invocation, type references, and bean resolution; a pushed expression is effectively arbitrary code execution inside the Spring context. | Aviator 5.4.4 with `enableSandboxMode()`. |
| `Caffeine 3.x` | Requires Java 11. Boot 2.7.6 manages 2.9.3; a manual bump breaks the JDK 8 contract at class-load time (major 55). | Caffeine 2.9.3. If a Java 11 upgrade ever happens, revisit. |
| `Logback 1.3.x` / `2.x` | Requires SLF4J 2.x. Boot 2.7.6 pins SLF4J 1.7.36; a mismatch produces "no SLF4J providers were found" and **all logging silently disappears** — which would hide exactly the rate-limit and fallback events this SDK needs to emit. | Logback 1.2.11 + SLF4J 1.7.36, as managed by Boot 2.7.6. |
| `sentinel-datasource-redis` as a second rule store | Introduces two sources of truth for rules (Nacos and Redis) and a precedence question the project already decided against. | `sentinel-datasource-nacos` only. |
| Sentinel Dashboard as the rule-authoring UI in production | Dashboard writes are in-memory on the dashboard node and are not authoritative; rules edited there diverge from Nacos and are lost on restart. | Nacos as the single rule source; use the dashboard for inspection only. |
| MicroRedis / embedded Redis / in-JVM mock Redis for the cluster token path | Fakes the exact failure mode (network latency, failover) the token layer exists to handle, and will pass tests that fail in production. | Testcontainers Redis, or the real shared instance in staging. |
| `nacos-client` 1.4.x | This is the version `sentinel-datasource-nacos:1.8.6` hard-declares. It produces `NoSuchMethodError` against a Nacos 2.x server and cannot use gRPC. | `nacos-client` 2.2.0, with an explicit `<exclusion>` on the datasource. |

## Stack Patterns by Variant

**If the SDK is running in single-node mode (the default):**
- Use `sentinel-core` in local mode only. Do **not** initialise a token client, do **not** open a Redis connection, and do **not** register a Nacos listener until configuration is actually needed.
- Because the contract is "single-node mode makes zero network calls" — the only acceptable proof is an absent client bean, not a lazily-connecting one.
- Keep L1 (Caffeine) as the only cache tier in this mode so a degraded config-center path cannot stall a request.

**If cluster mode is enabled:**
- Do not use Sentinel's built-in cluster transport. Use the Lua-token pattern below.
- Set a client-side timeout an order of magnitude below Sentinel's 20 ms default — budget ~2–3 ms — and on timeout or error trip a local circuit breaker to degraded (local-only) counting.
- Because the requirement is "cluster mode must automatically fall back to local mode." A bounded timeout plus a breaker makes the fallback fast and automatic; Sentinel's 20 ms latch does the opposite (it blocks before it can fail).
- Emit a counter for every fallback-to-local transition. Silent degradation is the failure mode that matters here.

**The cluster token pattern (Redis Lua, the replacement for Sentinel's cluster transport):**

```lua
-- KEYS[1] = rate-limit key (rule id + card token hash)
-- ARGV[1] = window start millis   ARGV[2] = window length millis
-- ARGV[3] = limit                ARGV[4] = cost
-- Returns: { allowed(0|1), remaining, retryAfterMs }
local now   = tonumber(ARGV[1])
local win   = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local cost  = tonumber(ARGV[4])

local windowStart = now - win
-- Drop entries that fell out of the sliding window, then append this request.
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', windowStart)
local used = redis.call('ZCARD', KEYS[1])
if used + cost > limit then
  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  local retryAfter = 0
  if oldest[2] then retryAfter = math.floor(tonumber(oldest[2]) + win - now) end
  redis.call('PEXPIRE', KEYS[1], win)
  return { 0, math.max(0, limit - used), retryAfter }
end
for i = 1, cost do
  redis.call('ZADD', KEYS[1], now, now .. ':' .. i)
end
redis.call('PEXPIRE', KEYS[1], win)
return { 1, limit - used - cost, 0 }
```

```java
// Client side: one hop, bounded, and it fails fast enough to fall back.
private static final Duration TOKEN_TIMEOUT = Duration.ofMillis(2);

TokenResult acquire(String ruleId, String cardTokenHash, int cost) {
    try {
        // Pre-built script -> EVALSHA, not EVAL; the body never crosses the wire.
        List<Long> r = redisTemplate.execute(TOKEN_SCRIPT, KEYS, args, TOKEN_TIMEOUT);
        return new TokenResult(r.get(0) == 1L, r.get(1), r.get(2));
    } catch (Exception ex) {
        breaker.recordFailure();                    // trips after N consecutive failures
        metrics.counter("degraded_to_local").increment();
        return LocalFallback.evaluate(ruleId, cardTokenHash, cost);  // automatic fallback
    }
}
```

- Because the script is atomic, the cross-node count is consistent without a distributed lock; because the SDK owns the timeout, it is capped at ~2 ms instead of Sentinel's fixed 20 ms; and because the catch path calls a local evaluator, "automatically fall back to local mode" is a code path rather than a hope.
- `PEXPIRE` on the key is mandatory — without it the sorted set grows forever for every card ever seen.
- **UNVERIFIED (must be measured):** the p50/p99 cost of this script against the production Redis topology. Treat the sub-millisecond figures in Sources as a planning input only.

**If rules are pushed from Nacos (always, but especially on change):**
- `validate(expr)` → on failure, log, emit a counter, and **keep the previous rule**; never compile a string you have not validated.
- `compile(expr, true)` with an LRU cache bounded by a configured size, and hold the `Expression` in an `AtomicReference` per rule ID so the hot path never touches a lock.
- Because a bad push must degrade to "keep serving with the last good rule", not "OOM the JVM" and not "fail open with no limit at all".

**If a rule's expression is evaluated on every request:**
- Pass an immutable environment map (card token hash, amount bucket, channel) — never the raw request object, and never a mutable map that another thread can modify mid-evaluation.
- Because sandbox mode limits what the expression can *do*; it does not stop an expression from reading whatever you hand it.

**If metrics are needed in cluster mode:**
- Distinguish `rejected_by_sentinel_local`, `rejected_by_cluster_token`, and `degraded_to_local` as three separate counters.
- Because "the cluster token layer said no" and "we could not reach the cluster token layer, so we fell back" are different operational events with different fixes.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| `spring-cloud-alibaba-dependencies:2021.0.5.0` | `spring-boot:2.7.6` | SCA 2021.0.x targets the Boot 2.6/2.7 line. In range. |
| `spring-cloud-alibaba-dependencies:2021.0.5.0` | `spring-cloud:2021.0.5.0` | Train must match exactly; SCA 2021.0.5.0 is cut for Spring Cloud 2021.0.5.0. |
| `sentinel-core:1.8.6` | JDK 8 | Verified: shipped bytecode is major 52. Later 1.8.x are also major 52, but do not bump — the SCA BOM is the contract. |
| `sentinel-parameter-flow-control:1.8.6` | `sentinel-core:1.8.6` | Must be the same version; `ParamFlowRule` is version-coupled to core's slot API. |
| `sentinel-datasource-nacos:1.8.6` | `nacos-client:2.2.0` | **THE TRAP.** The 1.8.6 artifact declares `<nacos.version>1.4.2</nacos.version>` in its own POM and depends on it directly. Maven only resolves 2.2.0 because the SCA BOM's `dependencyManagement` wins — so the correct version is silently correct *only* when the BOM is imported, and silently wrong (1.4.2) otherwise. Symptom of getting it wrong: `java.lang.NoSuchMethodError: com.alibaba.nacos.api.config.ConfigService.publishConfigCas(...)` at runtime, not at compile time. Enforce with `maven-enforcer-plugin` `dependencyConvergence`. |
| `nacos-client:2.2.0` | JDK 8 | Verified: the only class above major 52 is `META-INF/versions/9/module-info.class` (major 53), which is a multi-release JAR entry that JDK 8 ignores. Base classes are ≤ 52. Safe on JDK 8; some scanners and shading plugins will still flag it, so allowlist it if the build has an MR-jar check. |
| `aviator:5.4.4` | JDK 8 | Verified Java 8 line. Requires no transitive runtime deps. |
| `caffeine:2.9.3` | JDK 8 | Last Java-8 line; 3.0.0+ requires Java 11 (major 55). Managed by Boot 2.7.6. |
| `lettuce:6.1.10.RELEASE` | JDK 8, `spring-boot-starter-data-redis:2.7.6` | Boot-managed. Pulls Netty 4.1.x — major 52, fine on JDK 8. |
| `micrometer-registry-prometheus:1.9.6` | `micrometer-core:1.9.6` | Boot-managed pair. Transitive `simpleclient_common:0.15.0` — do not pin separately. |
| `jackson:2.13.4.20221013` | JDK 8 | Boot 2.7.6 Jackson BOM. Jackson 2.14+ is still Java 8 but not managed here; do not bump without re-checking the BOM. |
| `logback-classic:1.2.11` | `slf4j-api:1.7.36` | Logback 1.2.x is the **only** line that binds to SLF4J 1.7.x. Mismatch = zero log output, no error. |
| `spring-boot-starter-test:2.7.6` | JUnit Jupiter 5.8.2 | Boot-managed. Note Surefire needs a recent version to run 5.x tests. |

**Release-train note (context, not an action):** Spring Cloud 2021.0.x is end-of-life, and SCA 2021.0.6.x exists and still manages Sentinel 1.8.6 / Nacos 2.2.0. The project has fixed 2021.0.5.0, so this is recorded as a known long-term risk — a future upgrade to Boot 3 / Java 17 would require `jakarta.*` migration and a Sentinel major-version change — not as a recommendation to change anything now.

## Sources

- `https://repo1.maven.org/maven2/com/alibaba/cloud/spring-cloud-alibaba-dependencies/2021.0.5.0/spring-cloud-alibaba-dependencies-2021.0.5.0.pom` — **HIGH.** Source of truth for `sentinel.version=1.8.6`, `nacos.client.version=2.2.0`, and the managed `sentinel-*` artifact list. Read directly.
- `https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/2.7.6/spring-boot-dependencies-2.7.6.pom` — **HIGH.** Source of truth for Caffeine 2.9.3, Micrometer 1.9.6, Lettuce 6.1.10.RELEASE, Jedis 3.8.0, Spring 5.3.24, Jackson 2.13.4.20221013, SLF4J 1.7.36, Logback 1.2.11, JUnit 5.8.2, Lombok 1.18.24. Read directly.
- `https://repo1.maven.org/maven2/com/alibaba/csp/sentinel-datasource-nacos/1.8.6/sentinel-datasource-nacos-1.8.6.pom` — **HIGH.** The `<nacos.version>1.4.2</nacos.version>` trap. Read directly.
- `https://repo1.maven.org/maven2/com/alibaba/csp/sentinel-cluster-client-default/1.8.6/` and `.../sentinel-cluster-server-default/1.8.6/` — **HIGH.** Artifact existence (HTTP 200) and bytecode level (major 52) verified from the shipped JARs.
- Shipped bytecode inspection of `sentinel-core:1.8.6`, `sentinel-cluster-client-default:1.8.6`, `nacos-client:2.2.0`, `aviator:5.4.4` — **HIGH for structure, MEDIUM for behaviour.** Confirmed: `ChannelPromise.await(getRequestTimeout())` on the hot path; `requestTimeout=20`, `connectTimeout=10000`, `port=18730`; `DefaultClusterTokenClient.clientFail()` returns a `BAD_REQUEST` result; `ParamFlowRule` has no expression field; Aviator exposes `enableSandboxMode`/`useLRUExpressionCache`/`validate`/`invalidateCache` and the `Module`/`NewInstance`/`InternalVars`/`StaticFields`/`StaticMethods` features. Static analysis of control flow; the *timing* consequences are inferred and need the JMH benchmark.
- `https://github.com/ben-manes/caffeine/releases/tag/v3.0.0` — **HIGH.** Caffeine 3.0.0 requires Java 11, confirming 2.9.3 as the last Java-8 line.
- `https://github.com/killme2008/aviatorscript` (`AviatorEvaluatorInstance`) — **HIGH.** Sandbox-mode feature set and `validate()` availability in 5.4.4.
- `https://github.com/alibaba/QLExpress` (README) — **MEDIUM.** QLExpress 3.x default-insecure behaviour and QLExpress4 4.1.3 isolation defaults. Vendor-maintained README; not independently benchmarked here.
- `https://github.com/alibaba/nacos/issues/8448` and `https://github.com/alibaba/Sentinel/issues/1222` — **MEDIUM.** Production reports of `NoSuchMethodError` from the `sentinel-datasource-nacos` → `nacos-client:1.4.x` conflict. Community issue reports, but they match the POM evidence exactly, which is why the exclusion is treated as mandatory.
- `https://github.com/alibaba/Sentinel/wiki` (cluster flow control) and `https://spring.io/projects/spring-cloud` — **MEDIUM.** Conceptual cluster-flow-control behaviour and the Spring Cloud 2021.0.x → Boot 2.6/2.7 support matrix.
- Redis Lua latency figures (`~0.3 ms` p50 / `~1.5 ms` p99 same-AZ; `53–274 µs` in other setups) — **LOW / UNVERIFIED.** Community benchmarks only, mutually inconsistent, and sensitive to topology, payload, and client. Treated as an order-of-magnitude planning input. **Must** be re-measured with JMH against the actual Redis before this number is used in any SLO.

## Open Items / Required Follow-up

1. **Benchmark, do not assume.** Replace every latency claim above with JMH numbers measured against the real Redis and the real card-traffic shape. Until then, no SLO should reference this document.
2. **Metaspace smoke test.** Load Nacos-push churn (thousands of distinct rule expressions, including malformed ones) and confirm MetaspaceUsage is flat. This validates the `validate()`-then-`compile(..., cached)` design against the Aviator codegen issue.
3. **Enforcer gate.** Land `maven-enforcer-plugin` with `dependencyConvergence` in CI on day one, before any Nacos datasource code, so the `nacos-client` conflict cannot be reintroduced.
4. **Decide QLExpress4 vs Aviator definitively** if security review rejects Aviator's sandbox. The decision needs a JMH comparison, not a preference.

---
*Stack research for: embedded real-time credit-card capability SDK*
*Researched: 2026-09-29*
