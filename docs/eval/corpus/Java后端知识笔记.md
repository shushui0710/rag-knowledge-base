# Java 后端知识笔记

> 本文是 RAG 知识库的**评估语料之一**。它与 `docs/eval/questions.json` 的 20 道题配套：
> 题目的 `expectedKeyword` 必须能在本文或《项目技术架构说明》中逐字检索到，
> 这样 Top-K 命中率才是一个有意义的指标（而不是靠碰运气）。
> 修改 questions.json 时，请同步确认关键词在语料中存在。

## 一、Java 基础与集合

### 1.1 HashMap 底层原理

HashMap 的底层结构是「数组 + 链表 + 红黑树」。数组又叫哈希桶（table），初始容量 16，负载因子 0.75，
阈值 = 容量 × 负载因子 = 12，超过阈值触发扩容，容量翻倍。

put 的流程分三步：先由 `hash(key)` 计算哈希值（高 16 位与低 16 位异或，让高位参与运算以减少碰撞），
再用 `(n - 1) & hash` 定位桶下标（容量是 2 的幂，位运算等价于取模但更快）；
若桶为空则直接放置，否则遍历链表比较 hash 与 equals，命中则覆盖，未命中则尾插。
当链表长度达到 8 且数组容量达到 64 时，链表转红黑树（TREEIFY_THRESHOLD=8、MIN_TREEIFY_CAPACITY=64），
以把最坏查找复杂度从 O(n) 降到 O(log n)。红黑树节点数退到 6 以下时退回链表。

JDK 8 相对 JDK 7 的关键变化是**头插改尾插**：JDK 7 在并发扩容时头插会形成环形链表导致死循环，
尾插避免了这一点（但 HashMap 本身仍非线程安全，并发场景应改用 ConcurrentHashMap）。

扩容时元素的迁移利用了「容量是 2 的幂」这一性质：元素要么留在原下标，要么移动到「原下标 + 旧容量」，
因此不需要重新计算哈希，这就是 JDK 8 的「高低位链表拆分」优化。

面试高频追问：为什么容量必须是 2 的幂？——为了让 `(n-1) & hash` 等价于取模，且保证扩容后下标可预测。

### 1.2 ThreadLocal 与内存泄漏

ThreadLocal 提供线程本地变量：每个 Thread 内部维护一个 `ThreadLocalMap`，
key 是 ThreadLocal 实例（弱引用），value 是具体值（强引用）。因此数据实际存在**线程对象**上，
而不是 ThreadLocal 对象上。

为什么要用完 remove？因为 ThreadLocalMap 的 key 是弱引用而 value 是强引用：
当外部 ThreadLocal 引用被回收后，key 变成 null，但 value 仍被线程强引用无法回收，
形成「key 为 null 但 value 存活」的 Entry，即内存泄漏。
在线程池场景下更严重——线程会被复用而不销毁，泄漏的 value 会一直挂在线程上，
并且下一个任务可能读到上一个任务的脏数据（数据串号）。

所以正确用法是 try/finally 中调用 `remove()`。本项目的 `UserContext` 就遵循这个模式：
`JwtInterceptor.preHandle` 里 `setUserId`，`afterCompletion` 里 `remove()`——
放在 afterCompletion 而不是 postHandle，是因为 postHandle 在 handler 抛异常时不会执行，
而 afterCompletion 无论成功失败都会执行。

### 1.3 线程池核心参数

ThreadPoolExecutor 的七个核心参数：

| 参数 | 含义 |
|------|------|
| corePoolSize | 核心线程数，默认即使空闲也保留（除非设置 allowCoreThreadTimeOut） |
| maximumPoolSize | 最大线程数 |
| keepAliveTime | 非核心线程空闲存活时间 |
| unit | keepAliveTime 的时间单位 |
| workQueue | 任务队列，常用的有 ArrayBlockingQueue、LinkedBlockingQueue、SynchronousQueue |
| threadFactory | 线程工厂，用于自定义线程名、是否为守护线程 |
| handler | 拒绝策略 |

执行顺序是：核心线程未满 → 建核心线程执行；核心线程满 → 任务入队；
队列满且线程数 < maximumPoolSize → 建非核心线程；再满则触发拒绝策略。

四种拒绝策略：AbortPolicy（默认，抛 RejectedExecutionException）、
CallerRunsPolicy（由提交任务的线程自己执行，起到反压作用）、
DiscardPolicy（静默丢弃）、DiscardOldestPolicy（丢弃队列最老的任务再重试提交）。

易错点：使用无界队列（如 LinkedBlockingQueue 不传容量）时 maximumPoolSize 形同虚设，
因为队列永远不会满，线程数永远停在 corePoolSize，任务会无限堆积导致 OOM。

### 1.4 密码存储与 BCrypt

密码**绝不能**明文入库，也不能用 MD5 或 SHA-1 这类快速哈希。原因有两点：
一是快速哈希算力成本低，配合 GPU 每秒可尝试数十亿次，容易被暴力破解；
二是相同密码的 MD5 结果固定，攻击者可用彩虹表（预先算好的哈希-明文对照表）反查。

正确方案是加盐的慢哈希，BCrypt 是代表：密文形如 `$2a$10$...`，
其中 `$2a$` 是版本、`10` 是成本因子（cost，即 2^10 = 1024 轮迭代）、
后面 22 字符是随机盐、其余是摘要。因为它把**随机盐内嵌在密文里**，
所以同一个密码每次加密结果都不同（这正好证明了盐在起作用），
也就不存在彩虹表问题——攻击者必须为每条记录单独暴力枚举。

校验时用 `matches(明文, 密文)`：从密文中提取盐，用同样的成本因子重新加密明文再比较。
成本因子是可调的，10 ≈ 100ms/次，可以随硬件升级提高以保持攻击成本。

第三个设计点是**登录失败提示必须统一**：无论「用户不存在」还是「密码错误」都返回同一句话，
否则攻击者可以用枚举法先确定哪些用户名真实存在（这叫用户名枚举漏洞）。

## 二、数据库

### 2.1 MySQL 索引为什么用 B+ 树

数据库索引选 B+ 树（B+树是 B 树的变体，把数据全部下沉到叶子层）而不是红黑树、哈希表或跳表，
核心原因是**磁盘 IO 次数**：

- 红黑树是二叉结构，百万级数据树高约 20，最坏要 20 次随机磁盘 IO，慢到不可接受。
- B+ 树是多路平衡树，一个节点（页）默认 16KB，非叶子节点只存键不存数据，
  所以扇出（每个节点的子节点数）可达上千，树高能压到 3～4 层，
  一次查询只需 3～4 次 IO，且非叶子层几乎常驻内存（buffer pool）。
- 哈希索引只支持等值查询，无法做范围查询和排序，而 `where a > 10 order by a` 是数据库最常见的诉求。
- B+ 树的**叶子节点用双向链表串联且按序存放**，所以范围扫描和 `order by` 只需顺序遍历叶子链，
  这正是 B 树做不到的（B 树数据分散在各层节点上）。

补充两点面试加分项：一是聚簇索引 vs 二级索引——InnoDB 的聚簇索引叶子存整行数据，
二级索引叶子存主键值，因此二级索引查询需要「回表」；
二是覆盖索引——若查询字段全在索引里就无需回表，这也是「避免 select *」的底层原因。

### 2.2 事务与传播行为

事务的 ACID：原子性（Atomicity，靠 undo log 回滚）、
一致性（Consistency，由前两者共同保证）、
隔离性（Isolation，靠锁与 MVCC）、
持久性（Durability，靠 redo log）。

MySQL 默认隔离级别是「可重复读（RR）」，通过 MVCC（多版本并发控制）实现：
每行记录有隐藏的 `trx_id` 与回滚指针，事务读取时按 ReadView 判断该版本是否可见，
从而做到「读不加锁」。RR 下仍存在幻读风险，InnoDB 用间隙锁（Gap Lock）+ 临键锁（Next-Key Lock）兜住。

Spring 的事务传播行为共七种，最需要记住的是前三种：

- **REQUIRED（默认）**：当前有事务就加入，没有就新建。日常使用基本都用它。
- **REQUIRES_NEW**：无论当前有没有事务，都挂起外层事务并新建一个独立事务。
  适用于「日志记录不能因为业务回滚而丢失」这类场景。
- **NESTED**：嵌套事务，用 savepoint 实现，内层回滚不影响外层已执行部分，
  但外层回滚会连带内层——它和外层是同一个物理事务。
- SUPPORTS / NOT_SUPPORTED / MANDATORY / NEVER 属于辅助类型，主要是语义声明。

事务失效的四大经典原因：方法不是 public、自调用（this 调用不走代理）、
异常被 catch 没有抛出、抛出的不是 RuntimeException（默认只对运行时异常回滚，
受检异常需显式配置 rollbackFor）。

## 三、编码与边界

### 3.1 字符集与编码陷阱

Java 的 `file.encoding` 是 **JVM 启动期属性**，在 Windows 中文环境上 JDK 17 默认取 GBK。
这带来的典型事故是：中文文本经第三方 SDK 序列化时按平台默认字符集编码，
在服务端按 UTF-8 解码后变成乱码（俗称「锟斤拷」）。

本项目的真实事故就属于此类：中文文本传入 Milvus 的 BM25 分词器后，
乱码字节流被搜索引擎的 Rust 分词组件按多字节切分，触发 Rust panic，
最终导致 Milvus 容器以 SIGABRT（退出码 134）崩溃。

修复方式是显式固定编码：`-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`，
并且在 Maven 里同时配置 `project.build.sourceEncoding` 与 surefire 的 `argLine`，
确保编译、测试、运行三个 JVM 都是 UTF-8——只改其中一处仍会翻车。

### 3.2 时间戳与时钟

`System.currentTimeMillis()` 返回挂钟时间，可能因 NTP 校时而回拨；
`System.nanoTime()` 返回单调递增的计时器，虽不代表绝对时间，但适合测耗时。
因此「测时延用 nanoTime、记时间点用 currentTimeMillis」是更严谨的写法。
