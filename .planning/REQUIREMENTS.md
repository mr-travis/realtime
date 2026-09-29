# REQUIREMENTS — 信用卡实时数据系统 · 基础能力平台

> 派生自 `PROJECT.md` + `research/SUMMARY.md`（含 R1–R6 冲突收敛）。REQ-ID 格式 `[CATEGORY]-[NN]`。
> 括号内的「验收」为研究给出的可判定条件；无标注者由计划阶段补齐。

## v1 Requirements

### 库基础（FND）

- [ ] **FND-01** SDK 所有公开 API 在任意业务线程下线程安全且可重入
- [ ] **FND-02** SDK 所有集成依赖（Sentinel / Nacos / Micrometer / Redis / Spring）均为 `<optional>` 或 `provided`，接入方版本不被 SDK 接管
- [ ] **FND-03** SDK 可在非 Spring 宿主中工作，上述依赖全部不在 classpath 时仍可启动并提供能力
- [ ] **FND-04** 公共 API 不泄漏 Sentinel / Nacos / Caffeine 任何类型（内部替换不构成对使用方的破坏性变更）
- [ ] **FND-05** 冷启动需配置中心可达，无 last-known-good 且不可达时进程启动失败；提供 `foundation.rule.startup-policy` = STRICT（默认）/ RESILIENT（须显式 opt-in，降级为空快照 + `foundation.rule.loaded=false` + 健康 **DOWN**）
- [ ] **FND-06** 遵循 semver，废弃走 deprecate-then-remove
- [ ] **FND-07** 启动时断言并暴露实际解析到的 Sentinel / Nacos / Micrometer 版本，与编译基线不符时告警（验收：偏离基线在启动日志与健康端点可见）
- [ ] **FND-08** 父 POM 对除 `foundation-autoconfigure` / `foundation-console-api` 外的全部模块启用 Spring `bannedDependencies` 防火墙（验收：数据面模块依赖 `spring-*` 时构建失败）
- [ ] **FND-09** 规则无 TTL；不做"顺便确认一下配置是最新的"式同步重试
- [ ] **FND-10** 全部配置项以 `foundation.*` 前缀 opt-in，不写全局 Sentinel 配置、不装全局拦截器、不改宿主日志配置

### 规则治理与版本（GOV）

- [ ] **GOV-01** 规则 schema 版本按能力分列（限流/灰度/缓存各自独立版本线），SDK 以 `schemas/<capability>.support` 资源文件声明各能力支持上限 `maxSchemaVersion[N]`；规则集段内**不**声明 `schemaVersion`，由 SDK 按 `maxSchemaVersion[N]` 校验并将生效版本写入规则历史行；`ruleSetVersion` 按能力各自单调递增。schema 演进**只加不减**：新增可选字段与新增枚举值不 bump 版本号，删字段/改类型/改必填性即为不兼容；`deprecated` 字段只标记不改变行为，`removedIn` 恒为 `null`
- [ ] **GOV-02** `ruleSetVersion` 回退即拒绝（验收：推送更小版本号被丢弃且当前版本不变）
- [ ] **GOV-03** 每个能力段以独立不可变快照生效，运行时各自单次 `AtomicReference` 切换；决策路径零解析、零加锁、零分配
- [ ] **GOV-04** 部分应用在结构上不可能：某能力段校验通过才切换该段，否则该段整段拒绝并完整保留**该能力**的前一版本（跨能力互不连累）
- [ ] **GOV-05** last-known-good 快照以 SDK 自有格式落盘，temp-file + `ATOMIC_MOVE` 写入（验收：Nacos 故障后冷启动不降级）
- [ ] **GOV-06** 推送载荷格式错误或语义错误时按能力段原子拒绝该段，保留该能力前一版本；校验覆盖关系约束而非仅结构
- [ ] **GOV-07** Nacos 回调仅置脏标记并触发去抖重取（~200 ms 合并窗口），重复与乱序回调不导致重复应用
- [ ] **GOV-08** 配置删除是合法事件且被显式处理，退回内置静态安全默认，而非保留旧值
- [ ] **GOV-09** 启动时断言 namespace / group / dataId 白名单，不匹配即大声失败（验收：读错环境规则时不会"启动正常但无规则"）
- [ ] **GOV-10** 载荷内容哈希不匹配视为篡改/损坏并拒绝
- [ ] **GOV-11** 冷启动时无 last-known-good 且配置中心/规则历史不可达：**进程启动失败**（fail-fast），启动日志与健康端点须可区分「配置中心不可达」与「规则历史不可达」；运行期已有内存 last-known-good 时照常服务，决策路径不发起任何网络调用。启动断言须校验 `maxSchemaVersion[N]` ≥ 该能力 schema 定义文件中已存在的最高版本号
- [ ] **GOV-12** 监控 Nacos 快照目录可写性（磁盘满会使 Nacos 读自身快照失败并放大降级）
- [ ] **GOV-13** 规则集内路由表大小等于已编写规则数，不随运行时长或实体数增长（不变量；验收：10 万 BIN 基数压测后该不变量成立）
- [ ] **GOV-14** 编译缓存以归一化表达式文本为 key，容量有界（LRU，建议 1000）并导出淘汰指标
- [ ] **GOV-15** 推送采用差量替换而非全量重建

### 表达式沙箱（EXPR）

- [ ] **EXPR-01** 禁止使用 SpEL 评估配置下发的表达式，含 `SimpleEvaluationContext` 包裹（验收：依赖树断言 + code-review 检查项）
- [ ] **EXPR-02** 表达式引擎为 Aviator 5.4.4 沙箱模式，且启动时断言 `enableSandboxMode()` 未被关闭
- [ ] **EXPR-03** JDK 8 攻击用例矩阵在**默认配置**下全部通过：反射逃逸 / 构造器逃逸 / 静态方法 / `ClassLoader`-`Thread`-`Runtime` 链 / 文件与网络 IO / 死循环与深递归 / 超大字符串与数组 / ReDoS / 深嵌套
- [ ] **EXPR-04** JMH 实测表达式求值满足第 4 层预算（验收：P1 交付实测数据，非估算）
- [ ] **EXPR-05** 表达式求值有步数预算作为主约束，超限抛 `BudgetExceededException`；线程池超时不得作为主约束
- [ ] **EXPR-06** 若使用超时兜底，必须是有界队列 + 显式拒绝策略的独立线程池，命名并导出 active / 队列深度 / 拒绝数
- [ ] **EXPR-07** 表达式长度上限 512 字符、AST 节点上限 128、字段深度上限 5
- [ ] **EXPR-08** 表达式内禁止正则；业务匹配走 SDK 代码中注册并预编译的命名匹配器，以 ID 引用
- [ ] **EXPR-09** 编译前先 `validate()`，避免语法错误表达式在 Nacos 推送抖动下耗尽元空间
- [ ] **EXPR-10** 按 `ruleId` 导出慢求值计数，作为规则质量信号
- [ ] **EXPR-11** 表达式求值只能访问显式 `EvalContext` 白名单内的字段（语法层无方法调用能力）
- [ ] **EXPR-12** `ExpressionEngine` SPI 保留，可无下游改动替换引擎

### 限流（RL）

- [ ] **RL-01** 业务服务可在接口或方法上通过注解声明限流注入点
- [ ] **RL-02** 提供编程式 guard API 作为实际执行者（验收：Spring AOP 自调用不绕过 advice）
- [ ] **RL-03** 限流规则以接口 / 方法为粒度，通过表达式匹配方法入参路由到对应规则
- [ ] **RL-04** 表达式预编译并缓存，限流热路径上禁止现场解析
- [ ] **RL-05** 单节点模式本地计数，不依赖任何网络调用
- [ ] **RL-06** 集群模式由 SDK 自有共享计数器提供配额：Redis Lua 脚本经 `EVALSHA` 调用，算法为 `ZREMRANGEBYSCORE` 修剪窗口 + `ZCARD` 取用量 + 返回 `{allowed, remaining, retryAfterMs}`
- [ ] **RL-07** 集群计数 key 必设 `PEXPIRE`（否则有序集随见过的卡号无限增长）
- [ ] **RL-08** 集群模式 key 使用同一 slot（`{resource}` 哈希标签）以支持 Lua 多键操作
- [ ] **RL-09** 客户端超时 ~2 ms 且有界；超时或出错降级为本地计数并递增 `degraded_to_local`
- [ ] **RL-10** `ClusterGate` 为探针驱动的 `CLUSTER`/`LOCAL` 两态状态机，热路径只读状态；不得为 per-request try/catch
- [ ] **RL-11** 集群故障降级具备迟滞，避免状态抖动
- [ ] **RL-12** 提供运行时降级开关，可在不发版的情况下强制切到本地判定
- [ ] **RL-13** 降级为本地时阈值按实例数折算，配额不被静默放大
- [ ] **RL-14** 检测 Redis 复制回退（切换后旧主重新成为主）并相应处理配额
- [ ] **RL-15** Sentinel 仅本地模式；构建排除 `sentinel-cluster-client-default` 与 `sentinel-cluster-server-default`
- [ ] **RL-16** 令牌层 Redis 与缓存层 Redis 逻辑隔离：不同 key 前缀，优先独立实例，确保令牌层 flush 不影响业务缓存
- [ ] **RL-17** 限流决策默认 fail-close（保护后端不被打穿），逐条规则可覆写
- [ ] **RL-18** 故障默认值按业务线参数化，由管理 API 显式设置而非隐式继承
- [ ] **RL-19** 卡组织清算报文入站业务线的 fail-open 在受版本管理的 dataLine 注册表中固定（注册项形如 `{name, defaultFailStrategy}`；SDK 代码内无任何业务线名），并由守护测试钉住——反转即测试失败——同时列入 code-review 检查项（验收：防止后续"统一默认值"重构改回去）
- [ ] **RL-20** 每条 fail-open 规则配独立静态本地回退配额（验收：配置不可用 + 无回退 ≠ 无限通行）
- [ ] **RL-21** 实际生效的 `failStrategy` 记入 `DecisionRecord`
- [ ] **RL-22** 令牌桶语义含突发额度
- [ ] **RL-23** 白名单 / 旁路在配额消费前求值，且单独审计
- [ ] **RL-24** 决策结果为值对象（携带 `ruleId`、`ruleVersion`、剩余配额、机器可读原因码），非 boolean
- [ ] **RL-25** 原因码区分 `LIMITED_BY_RULE` / `NO_RULE_MATCHED` / `DEGRADED_LOCAL` / `FAIL_CLOSE`
- [ ] **RL-26** 三个独立计数器：`rejected_by_sentinel_local`、`rejected_by_cluster_token`、`degraded_to_local{reason}`
- [ ] **RL-27** 降级方向按原因分类告警：放大配额（后端过载，监控显示"正常"）与缩小配额（授权量骤降）走不同告警与不同 on-call 手册
- [ ] **RL-28** 不缓存决策结果（N 个并发请求共享一次配额检查会使实际上限变成 N 倍）
- [ ] **RL-29** 不使用任何全局 fail-open / fail-close 总开关；降级状态必须可查询
- [ ] **RL-30** 两个目标接入服务均启用集群模式运行

### 灰度（CAN）

- [ ] **CAN-01** 支持多维度组合判定，优先级：白名单 > 流量百分比 > hash 桶；该顺序是契约的一部分
- [ ] **CAN-02** 灰度规则经 Nacos 推送后本地生效，无需重启
- [ ] **CAN-03** 灰度决策默认可解释：命中哪条规则、落在哪个桶
- [ ] **CAN-04** 选中版本记入决策记录
- [ ] **CAN-05** 灰度规则支持一键回滚（依赖规则版本机制）
- [ ] **CAN-06** 灰度决策默认 fail-open（配置不可用时退回全量，不把未验证变更推上去），逐条规则可覆写
- [ ] **CAN-07** targeting-key 采用全局统一优先级：方法入参 → 路由头 → 主体标识
- [ ] **CAN-08** 发布时强制声明 targeting key，缺失即拒绝发布
- [ ] **CAN-09** targeting key 缺失时 fail-open 到"此规则不匹配"并计指标；**绝不随机**（随机是缺陷，不是回退）
- [ ] **CAN-10** 除 targeting key 外无其他输入进入哈希
- [ ] **CAN-11** `bucketCount` 与哈希盐为不可变、版本化的契约字段
- [ ] **CAN-12** 改桶须显式 `allowRebucket` 并被审计
- [ ] **CAN-13** 提供管理 API 端点计算改桶的 moved-in / moved-out 比例，使决策依据是数字而非直觉
- [ ] **CAN-14** 灰度评估器完全自研

### 缓存（CACHE）

- [ ] **CACHE-01** 提供本地二级缓存 + 分布式缓存的统一封装
- [ ] **CACHE-02** 本地层具备 TTL 与容量上界（条数与权重双限）
- [ ] **CACHE-03** 两级接口可插拔
- [ ] **CACHE-04** 提供防穿透、防击穿、防雪崩保护
- [ ] **CACHE-05** 击穿保护为 **per-key** single-flight；全局锁会把击穿变成串行击穿，比不加更糟
- [ ] **CACHE-06** 跨节点保护使用分布式锁 + 双检，带超时与安全释放
- [ ] **CACHE-07** 支持多维 key 生成与 O(1) 批量失效
- [ ] **CACHE-08** v1 只做通用层，不预置业务语义缓存
- [ ] **CACHE-09** 一致性协议：每次失效带单调序列号，乱序丢弃，周期对账任务修复漂移
- [ ] **CACHE-10** L1 TTL 作为不一致的硬上界，并显式强制 `l1Ttl < l2Ttl` 及比例
- [ ] **CACHE-11** TTL 抖动（≈ ±10%），以 key 哈希播种
- [ ] **CACHE-12** 热键本地常驻：soft-TTL + 后台刷新
- [ ] **CACHE-13** 每缓存 `l1Enabled` 开关，高一致性调用方可绕过 L1
- [ ] **CACHE-14** `sensitive` 默认 `true`（拒绝优先）：声明为敏感时不写 L1、key 只存哈希前缀
- [ ] **CACHE-15** 缓存模块与规则模块双向禁止依赖（规则过期与数据过期语义不同，共享通用缓存会按 TTL 提供过期或失效的规则）
- [ ] **CACHE-16** 决策路径无同步 IO

### 可观测性（OBS）

- [ ] **OBS-01** 每次决策（放行 / 拒绝 / 灰度命中）可事后追溯：命中规则 ID、规则版本、当时剩余配额、灰度桶号、评估耗时
- [ ] **OBS-02** `DecisionRecord` 字段集在 P1 冻结；历史不可回填补齐
- [ ] **OBS-03** `ruleVersion` 为快照的不可变字段（否则 P5 只能记录"当前版本"，反查得到错误版本）
- [ ] **OBS-04** 决策日志走独立通道，不混入业务日志，保留期与脱敏策略独立配置
- [ ] **OBS-05** 使用有界 ring buffer + 批量写出
- [ ] **OBS-06** ring buffer 满时丢弃并计数，绝不阻塞（验收：满缓冲丢弃路径在压测中表现正常；阻塞生产者会级联到业务线程池）
- [ ] **OBS-07** 决策记录不得以 `log.isDebugEnabled()` 之类门控
- [ ] **OBS-08** 暴露 Prometheus 指标：QPS、拒绝率、命中率、规则版本
- [ ] **OBS-09** 单一低基数指标命名空间；meter 预注册、tag 词汇表有界（P1 交付）
- [ ] **OBS-10** 限流维度使用内部代理 ID，绝不使用卡 BIN 或交易号作为指标标签
- [ ] **OBS-11** SDK 健康度可监控：规则同步状态、降级状态、Redis 连通性
- [ ] **OBS-12** SDK 自身开销经 `MeterBinder` 自报（预判延迟敏感接入方的首个异议）
- [ ] **OBS-13** 离线决策样本消费者位于示例服务内，不新增部署件
- [ ] **OBS-14** 决策通道保留期为显式、文档化的 sink 配置属性

### 管理 API（API）

- [ ] **API-01** 提供规则增删改查、灰度发布、变更审计的 REST API
- [ ] **API-02** `/api/v1` 契约在本阶段冻结
- [ ] **API-03** `tenantId` 不出现在路径中
- [ ] **API-04** DTO 不暴露实体
- [ ] **API-05** 每个变更操作均带幂等键
- [ ] **API-06** 支持 `ETag` / `If-Match`
- [ ] **API-07** 错误遵循 RFC 7807
- [ ] **API-08** 每个响应携带当前 `ruleSetVersion`
- [ ] **API-09** 契约仅做增量演进
- [ ] **API-10** 增删改查、发布 / 灰度推送、回滚是**三个独立操作**；编辑不得在发布前生效
- [ ] **API-11** 变更审计 append-only，审计 ↔ 规则版本 ↔ 决策日志三方可关联
- [ ] **API-12** 规则版本永不就地编辑，版本历史保留
- [ ] **API-13** 回滚为前向写入新的单调版本，不重指既有版本（重指会使实例持有的版本与内容脱钩，决策记录中的版本不再标识内容）
- [ ] **API-14** schema 已推进时回滚返回 `409 SCHEMA_INCOMPATIBLE`，不静默强转
- [ ] **API-15** 管理 API 具备认证与授权（未认证且能改限流的端点即 DoS 按钮）
- [ ] **API-16** 健康 / 就绪端点上报**生效**配置状态
- [ ] **API-17** 通过 API 暴露有效全局配额（阈值 × 参与节点数 = 真实配额）
- [ ] **API-18** API 预留租户 / 权限模型位供后续控制台子应用使用；v1 不实现多租户隔离，该缺口须在 P6 交接文档中显式声明为风险
- [ ] **API-19** 发布前校验规则，区分 `NO_RULES_CONFIGURED` 与 `RULE_SOURCE_UNAVAILABLE`

### 敏感数据（SEC）

- [ ] **SEC-01** 决策日志字段白名单在 SDK 层强制：仅 `ruleId`、`ruleVersion`、`dataLine`、`decision`、`remaining`、`bucket`、`latencyUs`、`failStrategy`、`degraded`；禁止原始入参、原始 key、完整表达式文本
- [ ] **SEC-02** 调试使用 `EvalContext` 字段级脱敏 + 显式 debug 开关（默认关闭）
- [ ] **SEC-03** 敏感字段脱敏为 SDK 默认行为，而非各调用点自行遵守
- [ ] **SEC-04** 指标标签白名单：有界枚举（能力、决策类型、失败原因、生效模式）+ `ruleId`；高基数明细进决策记录
- [ ] **SEC-05** P6 交付敏感数据路径清单：逐个列出所有 Redis key 前缀、日志字段、指标标签，交由采纳方安全评审
- [ ] **SEC-06** SDK 明确不做 PAN 加密、不提供 PCI 认证，但必须提供阻止调用方犯错的默认值；"不处理 PAN"由技术不变量支撑，不作为免责声明
- [ ] **SEC-07** `ArgumentSource` 为三项能力统一的 PAN 掩码执行点：参数提取有界（≤64 个预投影字段）、扁平不可变、绝不暴露业务 DTO 或原始请求对象

### 性能（PERF）

- [ ] **PERF-01** SDK 自身引入的 P99 延迟开销 < 10 µs（不含集群 RTT）
- [ ] **PERF-02** 集群 RTT 作为独立预算线报告，不与 SDK 开销混算
- [ ] **PERF-03** 热路径 1–8 层无锁（任一 `synchronized` 在负载下使整个预算失效）
- [ ] **PERF-04** 无无界结构：路由 LRU、ring buffer、`appliedSeq` map、single-flight map 均需有界——Nacos 推送不得能无限制增长堆内存（远程可触发 OOM）
- [ ] **PERF-05** JMH 基准测试台在 P2 落地（须在热路径构建过程中测量）
- [ ] **PERF-06** 生产形态压测报告为 P6 交付物
- [ ] **PERF-07** 压测作为架构决策闸门：须给出集群令牌层在真实拓扑下满足峰值并留出 ≥3× 余量的实测证据，据此决定是否从「Redis 为判定点」改为「本地快路径 + Redis 粗粒度对账」
- [ ] **PERF-08** 任何 SLO 不得引用社区基准数字（研究中的延迟数字一律为 LOW 置信度）

### 交付与接入（DEL）

- [ ] **DEL-01** 交付 SDK + 集群令牌层（Redis + SDK 自有版本化 Lua 脚本，v1 不新增独立部署件）
- [ ] **DEL-02** 交付示例服务
- [ ] **DEL-03** 交付接入与运维文档
- [ ] **DEL-04** 至少 2 个业务服务完成真实接入，且均启用集群限流模式
- [ ] **DEL-05** 交付双 Boot 版本兼容矩阵（Boot 2.6.x / 2.7.x × SCA 2021.0.5.0）集成文档
- [ ] **DEL-06** 治理发布通道先于灰度 / 限流规则到达生产采纳方；P6 之前可对直接 Nacos 写入开发验证，但规则不得进入生产
- [ ] **DEL-07** 令牌层与缓存层使用同一 Redis 时，key 命名空间分离并纳入运维文档

## v2 Requirements（延后）

- [ ] **V2-01** 业务语义缓存（额度 / 账单 / 卡产品）——每个都是独立立项，各有自己的失效契约与 PII 保留规则，绝不批量做
- [ ] **V2-02** 分布式本地配额协调（作为中心化令牌存储的替代）——属研究项目
- [ ] **V2-03** 两级限流策略（本地分块 + 全局存储）以降低中心存储压力
- [ ] **V2-04** 发布前对候选规则集做变更预览 / 干跑
- [ ] **V2-05** 跨能力 / 跨范围的原子发布与统一版本历史（v1 已在单能力规则集内提供原子发布与回滚）
- [ ] **V2-06** 细化的 last-known-good 策略与按模块告警
- [ ] **V2-07** 测试支持模块（内存配置源、内存配额存储、确定性重放台）——触发条件为首位外部贡献者或第二个消费团队
- [ ] **V2-08** 完整诊断端点
- [ ] **V2-09** 热路径可插拔业务逻辑扩展点（先做五个窄模块 API，再考虑统一抽象）
- [ ] **V2-10** 表达式引擎 JMH 横向对比基线（若启用 Aviator 需在 P1 补齐）

## Out of Scope（明确排除）

| 排除项 | 理由 |
|---|---|
| 控制台前端界面 | 拆为独立子应用；本子应用只交付管理 API，契约在 P6 冻结。界面工作量与 SDK 无共享技术路径 |
| Sentinel Dashboard 作为管理界面 | 纯内存存储、无审计、无历史；且 SDK 作为库不应改写宿主 JVM 的全局 Sentinel 配置 |
| 业务语义缓存 | 业务线仍在梳理，写死业务对象会返工 |
| 非 Java SDK（Go / Node / Python） | v1 仅 Java（JDK 8） |
| 熔断、隔离舱、舱壁降级 | Sentinel 自带熔断能力，本次范围只含限流、灰度、缓存。注：本地失败计数器属 `ClusterGate` 降级机制，不违反本排除项 |
| 真实 PCI DSS 认证流程 | 保留排除，但理由改为经 SEC-01..07 强化的可验证技术不变量，而非"SDK 不处理 PAN"的自述 |
| 多租户隔离的完整实现 | v1 只在 API 层预留模型位；隔离逻辑随控制台子应用落地。该缺口在 P6 交接文档中显式声明 |
| 规则 TTL | 规则过期等于移除一层保护；临时 Nacos 故障不得转为永久失去保护 |
| 业务日志作为决策通道 | 保留期与脱敏策略不同，且会污染业务日志 |
| 同步决策日志写在热路径 | 阻塞生产者会级联到业务线程池 |
| 高基数指标标签（用户 ID / PAN / 交易号 / 原始表达式文本） | 基数不可逆，且是敏感数据泄漏路径 |
| 仅注解式强制 | Spring AOP 自调用静默绕过 advice，编程式 API 才是执行者 |
| per-call SpEL / 脚本求值 | 无沙箱等同于开放代码执行 |
| 单一全局 fail-open / fail-close 开关 | 会使降级状态不可查询 |
| 统一泛化的插件 / 总线框架 | 五个模块的失败语义各不相同，统一抽象会强制最弱公分母 |
| 规则集内缓存决策结果 | N 个并发请求共享一次配额检查会使实际上限变成 N 倍 |

## Traceability

> 由 ROADMAP.md 阶段填充（Phase ↔ REQ-ID 双向映射）。阶段编号与 `research/SUMMARY.md` 的 P1–P6 一一对应。
> 覆盖校验：**152 / 152 条 v1 需求**，每条映射到恰好一个阶段，无重复、无孤儿。

### 方向一：REQ-ID → Phase

| Requirement | Phase | 状态 |
|-------------|-------|------|
| FND-01 | Phase 1 | Pending |
| FND-02 | Phase 1 | Pending |
| FND-03 | Phase 1 | Pending |
| FND-04 | Phase 1 | Pending |
| FND-05 | Phase 1 | Pending |
| FND-06 | Phase 1 | Pending |
| FND-07 | Phase 1 | Pending |
| FND-08 | Phase 1 | Pending |
| FND-09 | Phase 1 | Pending |
| FND-10 | Phase 1 | Pending |
| GOV-01 | Phase 1 | Pending |
| GOV-02 | Phase 1 | Pending |
| GOV-03 | Phase 1 | Pending |
| GOV-04 | Phase 1 | Pending |
| GOV-05 | Phase 1 | Pending |
| GOV-06 | Phase 1 | Pending |
| GOV-07 | Phase 1 | Pending |
| GOV-08 | Phase 1 | Pending |
| GOV-09 | Phase 1 | Pending |
| GOV-10 | Phase 1 | Pending |
| GOV-11 | Phase 1 | Pending |
| GOV-12 | Phase 1 | Pending |
| GOV-13 | Phase 1 | Pending |
| GOV-14 | Phase 1 | Pending |
| GOV-15 | Phase 1 | Pending |
| EXPR-01 | Phase 1 | Pending |
| EXPR-02 | Phase 1 | Pending |
| EXPR-03 | Phase 1 | Pending |
| EXPR-04 | Phase 1 | Pending |
| EXPR-05 | Phase 1 | Pending |
| EXPR-06 | Phase 1 | Pending |
| EXPR-07 | Phase 1 | Pending |
| EXPR-08 | Phase 1 | Pending |
| EXPR-09 | Phase 1 | Pending |
| EXPR-10 | Phase 1 | Pending |
| EXPR-11 | Phase 1 | Pending |
| EXPR-12 | Phase 1 | Pending |
| RL-01 | Phase 2 | Pending |
| RL-02 | Phase 2 | Pending |
| RL-03 | Phase 2 | Pending |
| RL-04 | Phase 2 | Pending |
| RL-05 | Phase 2 | Pending |
| RL-06 | Phase 2 | Pending |
| RL-07 | Phase 2 | Pending |
| RL-08 | Phase 2 | Pending |
| RL-09 | Phase 2 | Pending |
| RL-10 | Phase 2 | Pending |
| RL-11 | Phase 2 | Pending |
| RL-12 | Phase 2 | Pending |
| RL-13 | Phase 2 | Pending |
| RL-14 | Phase 2 | Pending |
| RL-15 | Phase 2 | Pending |
| RL-16 | Phase 2 | Pending |
| RL-17 | Phase 2 | Pending |
| RL-18 | Phase 2 | Pending |
| RL-19 | Phase 2 | Pending |
| RL-20 | Phase 2 | Pending |
| RL-21 | Phase 2 | Pending |
| RL-22 | Phase 2 | Pending |
| RL-23 | Phase 2 | Pending |
| RL-24 | Phase 2 | Pending |
| RL-25 | Phase 2 | Pending |
| RL-26 | Phase 2 | Pending |
| RL-27 | Phase 2 | Pending |
| RL-28 | Phase 2 | Pending |
| RL-29 | Phase 2 | Pending |
| RL-30 | Phase 6 | Pending |
| CAN-01 | Phase 3 | Pending |
| CAN-02 | Phase 3 | Pending |
| CAN-03 | Phase 3 | Pending |
| CAN-04 | Phase 3 | Pending |
| CAN-05 | Phase 3 | Pending |
| CAN-06 | Phase 3 | Pending |
| CAN-07 | Phase 3 | Pending |
| CAN-08 | Phase 3 | Pending |
| CAN-09 | Phase 3 | Pending |
| CAN-10 | Phase 3 | Pending |
| CAN-11 | Phase 3 | Pending |
| CAN-12 | Phase 3 | Pending |
| CAN-13 | Phase 3 | Pending |
| CAN-14 | Phase 3 | Pending |
| CACHE-01 | Phase 4 | Pending |
| CACHE-02 | Phase 4 | Pending |
| CACHE-03 | Phase 4 | Pending |
| CACHE-04 | Phase 4 | Pending |
| CACHE-05 | Phase 4 | Pending |
| CACHE-06 | Phase 4 | Pending |
| CACHE-07 | Phase 4 | Pending |
| CACHE-08 | Phase 4 | Pending |
| CACHE-09 | Phase 4 | Pending |
| CACHE-10 | Phase 4 | Pending |
| CACHE-11 | Phase 4 | Pending |
| CACHE-12 | Phase 4 | Pending |
| CACHE-13 | Phase 4 | Pending |
| CACHE-14 | Phase 4 | Pending |
| CACHE-15 | Phase 4 | Pending |
| CACHE-16 | Phase 4 | Pending |
| OBS-01 | Phase 5 | Pending |
| OBS-02 | Phase 1 | Pending |
| OBS-03 | Phase 1 | Pending |
| OBS-04 | Phase 5 | Pending |
| OBS-05 | Phase 1 | Pending |
| OBS-06 | Phase 1 | Pending |
| OBS-07 | Phase 1 | Pending |
| OBS-08 | Phase 5 | Pending |
| OBS-09 | Phase 1 | Pending |
| OBS-10 | Phase 2 | Pending |
| OBS-11 | Phase 5 | Pending |
| OBS-12 | Phase 5 | Pending |
| OBS-13 | Phase 5 | Pending |
| OBS-14 | Phase 5 | Pending |
| API-01 | Phase 6 | Pending |
| API-02 | Phase 6 | Pending |
| API-03 | Phase 6 | Pending |
| API-04 | Phase 6 | Pending |
| API-05 | Phase 6 | Pending |
| API-06 | Phase 6 | Pending |
| API-07 | Phase 6 | Pending |
| API-08 | Phase 6 | Pending |
| API-09 | Phase 6 | Pending |
| API-10 | Phase 6 | Pending |
| API-11 | Phase 6 | Pending |
| API-12 | Phase 6 | Pending |
| API-13 | Phase 6 | Pending |
| API-14 | Phase 6 | Pending |
| API-15 | Phase 6 | Pending |
| API-16 | Phase 6 | Pending |
| API-17 | Phase 6 | Pending |
| API-18 | Phase 6 | Pending |
| API-19 | Phase 6 | Pending |
| SEC-01 | Phase 5 | Pending |
| SEC-02 | Phase 1 | Pending |
| SEC-03 | Phase 1 | Pending |
| SEC-04 | Phase 1 | Pending |
| SEC-05 | Phase 6 | Pending |
| SEC-06 | Phase 6 | Pending |
| SEC-07 | Phase 1 | Pending |
| PERF-01 | Phase 2 | Pending |
| PERF-02 | Phase 2 | Pending |
| PERF-03 | Phase 2 | Pending |
| PERF-04 | Phase 1 | Pending |
| PERF-05 | Phase 2 | Pending |
| PERF-06 | Phase 6 | Pending |
| PERF-07 | Phase 2 | Pending |
| PERF-08 | Phase 2 | Pending |
| DEL-01 | Phase 6 | Pending |
| DEL-02 | Phase 6 | Pending |
| DEL-03 | Phase 6 | Pending |
| DEL-04 | Phase 6 | Pending |
| DEL-05 | Phase 6 | Pending |
| DEL-06 | Phase 6 | Pending |
| DEL-07 | Phase 6 | Pending |

### 方向二：Phase → REQ-ID

| Phase | 名称 | 需求数 | Requirements |
|------|------|--------|--------------|
| Phase 1 | 规则内核与表达式沙箱 | 48 | EXPR-01, EXPR-02, EXPR-03, EXPR-04, EXPR-05, EXPR-06, EXPR-07, EXPR-08, EXPR-09, EXPR-10, EXPR-11, EXPR-12, FND-01, FND-02, FND-03, FND-04, FND-05, FND-06, FND-07, FND-08, FND-09, FND-10, GOV-01, GOV-02, GOV-03, GOV-04, GOV-05, GOV-06, GOV-07, GOV-08, GOV-09, GOV-10, GOV-11, GOV-12, GOV-13, GOV-14, GOV-15, OBS-02, OBS-03, OBS-05, OBS-06, OBS-07, OBS-09, PERF-04, SEC-02, SEC-03, SEC-04, SEC-07 |
| Phase 2 | 限流执行与故障策略 | 36 | OBS-10, PERF-01, PERF-02, PERF-03, PERF-05, PERF-07, PERF-08, RL-01, RL-02, RL-03, RL-04, RL-05, RL-06, RL-07, RL-08, RL-09, RL-10, RL-11, RL-12, RL-13, RL-14, RL-15, RL-16, RL-17, RL-18, RL-19, RL-20, RL-21, RL-22, RL-23, RL-24, RL-25, RL-26, RL-27, RL-28, RL-29 |
| Phase 3 | 灰度评估器 | 14 | CAN-01, CAN-02, CAN-03, CAN-04, CAN-05, CAN-06, CAN-07, CAN-08, CAN-09, CAN-10, CAN-11, CAN-12, CAN-13, CAN-14 |
| Phase 4 | 两级缓存 | 16 | CACHE-01, CACHE-02, CACHE-03, CACHE-04, CACHE-05, CACHE-06, CACHE-07, CACHE-08, CACHE-09, CACHE-10, CACHE-11, CACHE-12, CACHE-13, CACHE-14, CACHE-15, CACHE-16 |
| Phase 5 | 决策可观测性 | 8 | OBS-01, OBS-04, OBS-08, OBS-11, OBS-12, OBS-13, OBS-14, SEC-01 |
| Phase 6 | 管理 API 与接入验证 | 30 | API-01, API-02, API-03, API-04, API-05, API-06, API-07, API-08, API-09, API-10, API-11, API-12, API-13, API-14, API-15, API-16, API-17, API-18, API-19, DEL-01, DEL-02, DEL-03, DEL-04, DEL-05, DEL-06, DEL-07, PERF-06, RL-30, SEC-05, SEC-06 |
| **合计** | | **152** | v1 需求 152/152 覆盖 |

### 按分类的阶段分布

| 分类 | 总数 | 分布 |
|------|------|------|
| FND 库基础 | 10 | P1=10 |
| GOV 规则治理与版本 | 15 | P1=15 |
| EXPR 表达式沙箱 | 12 | P1=12 |
| RL 限流 | 30 | P2=29 · P6=1 |
| CAN 灰度 | 14 | P3=14 |
| CACHE 缓存 | 16 | P4=16 |
| OBS 可观测性 | 14 | P1=6 · P2=1 · P5=7 |
| API 管理 API | 19 | P6=19 |
| SEC 敏感数据 | 7 | P1=4 · P5=1 · P6=2 |
| PERF 性能 | 8 | P1=1 · P2=6 · P6=1 |
| DEL 交付与接入 | 7 | P6=7 |
| **v1 合计** | **152** | P1=48 · P2=36 · P3=14 · P4=16 · P5=8 · P6=30 |

### 未映射项（按设计）

- **v2 需求 10 条**（V2-01…V2-10）：不映射到任何阶段，见上方 v2 Requirements。
- **排除项 16 项**：不产生阶段。其中三项在阶段成功标准中体现为反向断言——控制台前端界面（独立子应用，P6 只交付 API 契约）、非 Java SDK（v1 仅 Java）、业务语义缓存（CACHE-08 显式断言 v1 不预置）。熔断 / 隔离舱不产生阶段；`ClusterGate` 的本地失败计数器是降级机制，不违反该排除项。

### 阶段顺序闸门（影响多条需求的交付时点）

- **DEL-06 + RL-30**：Phase 6 的治理发布通道就绪前，灰度与限流规则**不得到达生产采纳方**；Phase 1–5 仅可对直接 Nacos 写入做开发验证（该路径绕过校验、版本化、审计与回滚，API 层校验不是安全边界）。该闸门已写入 Phase 2 与 Phase 3 的成功标准。
- **OBS-01 → Phase 5**：决策记录契约在 Phase 1 冻结（OBS-02 / OBS-03），端到端「事后可追溯」的能力随 sink 传输与离线消费者在 Phase 5 闭合。
- **RL-30 → Phase 6**：两个目标接入服务同时以集群限流模式运行，发生在「≥2 个真实接入」时。
