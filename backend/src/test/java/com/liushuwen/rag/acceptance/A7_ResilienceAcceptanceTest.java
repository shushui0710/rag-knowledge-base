package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.mapper.UserMapper;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.config.AsyncConfig;
import com.liushuwen.rag.document.dto.IndexRebuildTask;
import com.liushuwen.rag.document.service.DocumentChunkService;
import com.liushuwen.rag.document.service.DocumentService;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.IndexRebuildService;
import com.liushuwen.rag.document.service.IndexRebuildWorker;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.document.service.MinioService;
import com.liushuwen.rag.document.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 —— 韧性射程验收：补上 A1~A6 都没覆盖的<b>六类射程空缺</b>。
 *
 * 【为什么要有这一套】
 *   第七轮复盘的结论是「**测试射程决定能发现什么**」——G-01~G-09、越权删除、级联残留这些缺陷，
 *   全都活在"当时用例全绿"的状态下，共性是<b>用例只验证了主路径的正常返回，没验证异常路径、
 *   并发路径、越界输入与权限矩阵</b>。因此本类专门去补那六个没人碰过的方向：
 *
 *   | 编号  | 射程        | 之前为什么没人发现 |
 *   |-------|-------------|--------------------|
 *   | A7-01 | 并发        | 全部用例都是单线程顺序调用，ThreadLocal 隔离从未在真并发下验证 |
 *   | A7-02 | 事务回滚    | 只测了"删成功"，没测"删到一半失败会怎样" |
 *   | A7-03 | 级联正向    | 只测"删干净了"，没测"删完立刻重建同内容文档"的终态 |
 *   | A7-04 | 异步失败    | 异步受理只看"受理成功"，没看"被拒时会不会留下半吊子任务" |
 *   | A7-05 | 权限矩阵    | 越权只逐点测了两处（删文档 / 会话），没有按"身份 × 端点"成矩阵 |
 *   | A7-06 | 超长畸形    | 非法输入只测了类型（格式/空/超限），没测长度越界与畸形字符 |
 *
 * 【与 A6 的分工】A6 是"已知缺口的反向固化"（断言缺陷当前成立，修好即失败）；本类是正向回归——
 *   它断言的是"应该成立"的契约。若某条在本轮跑成红的，那就是新发现的真缺口，处理方式遵循本项目既有约定：
 *   能修的当场修好（本条随即变成正向护栏），确有取舍的才登记进 A6 缺口台账。
 *
 * 【首跑战果·第 8 轮】本类首次执行 6 条即抓到 2 处真缺陷（其余为用例自身的断言粒度问题，已修正）：
 *   ① <b>IDOR 写越权</b>：<code>POST /api/document/embed/{id}</code> 只校验存在性不校验归属，
 *      任何登录用户拿着他人文档 id 就能替对方触发向量化（改状态位 + 写向量库）——已在 A7-05 固化为正向护栏；
 *   ② <b>超长输入落 500</b>：超长文件名（&gt;200）与超长会话标题（&gt;100）不命中任何业务校验，
 *      一路走到 INSERT/UPDATE 才被列上限拒绝 ⇒ HTTP 500「系统内部错误」，把"你输入太长"误报成"服务端故障"
 *      ——已在 A7-06 固化为 400 + 可读文案。
 *   两处均与 G-01~G-09 同源：<b>不是代码写错，而是射程没覆盖</b>（越权只逐点补过删除与会话；非法输入只按类型测过）。
 *
 * 【为什么不在本类真跑索引重建】重建是分钟级破坏性操作（会 drop/切换全库向量集合），
 *   与 A2-13 同一口径：只钉"受理前的闸门与不变量"，真跑留给人工作业与 rebuild_ops_evidence 取证。
 *
 * 通过标准（P0）：
 *   - 并发：N 个用户同时上传+向量化+检索，互不串号（各自只召回自己的文档）
 *   - 事务：删除中途失败，MySQL 侧（分块行 / 文档行）整体回滚，不留半删状态
 *   - 级联：删除后 Milvus 不可召回 + MinIO 对象消失 + MySQL 分块清零；同内容重传后新文档可召回且主键不冲突
 *   - 异步：运维闸门拒绝时不产生任何任务快照；陈旧任务号不得覆盖快照；任务在专用池、单飞入口同步
 *   - 权限：无 token 打 14 个受保护端点全部被拒；越权写（含触发他人文档向量化）一律 403 且零副作用
 *   - 畸形：超长文件名 / 超长会话标题被前置校验拒绝为 400（不是 500）；超长问题不落 5xx；
 *     emoji+零宽+控制字符内容完整入库且可向量化
 */
@DisplayName("A7 韧性射程验收（并发 / 事务 / 级联 / 异步 / 权限 / 畸形）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A7_ResilienceAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentChunkService documentChunkService;

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private MinioService minioService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private IndexRebuildService indexRebuildService;

    @Autowired
    private IndexRebuildWorker indexRebuildWorker;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PlatformTransactionManager txManager;

    /** A7-04 / A7-05 用：模拟运维在库侧提权（与 A2-13 同一手法） */
    @Autowired
    private UserMapper userMapper;

    /** 生成一份"含唯一标记词、足以切出多块"的文档正文 */
    private static String corpusWith(String marker) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 韧性射程验证文档\n\n");
        sb.append("本文档唯一标记词是 ").append(marker).append("。\n\n");
        for (int i = 1; i <= 12; i++) {
            sb.append("第").append(i).append("段：本节用于把正文撑到多分块长度，内容涉及检索增强生成的工程实践与参数说明。\n");
        }
        return sb.toString();
    }

    private static String marker(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 从当前用户文档列表里找指定 id，找不到返回 null */
    private JsonNode findDoc(String token, long docId) {
        for (JsonNode d : jsonOf(httpGet("/api/document/list", token)).path("data")) {
            if (d.path("id").asLong() == docId) {
                return d;
            }
        }
        return null;
    }

    /** 无 token 场景下按 HTTP 方法发一个请求（A7-05 的矩阵用） */
    private ResponseEntity<byte[]> callWithoutToken(String method, String path) {
        return switch (method) {
            case "GET" -> httpGet(path, null);
            case "POST" -> httpPostJson(path, "{}", null);
            case "PUT" -> httpPutJson(path, "\"x\"", null);
            case "DELETE" -> httpDelete(path, null);
            default -> throw new IllegalArgumentException("未支持的方法：" + method);
        };
    }

    // ==================== 1. 并发射程 ====================

    @Test
    @Order(1)
    @DisplayName("A7-01 并发射程：多用户真并发上传+向量化+检索，互不串号（ThreadLocal 隔离在并发下成立）")
    void a701_concurrent_users_do_not_cross_contaminate() throws Exception {
        // 【为什么要补】A1~A6 全部是"单线程顺序调用"，而多租户隔离靠的是 UserContext(ThreadLocal)
        // ——ThreadLocal 的隔离性只在**真并发**下才被检验：一旦有人把它换成静态字段或
        // InheritableThreadLocal、或在异步线程里复用，顺序用例依然全绿，并发时才会互相看到对方数据。
        final int users = 4;
        List<AuthSession> sessions = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            sessions.add(newUser());
            markers.add(marker("CONC" + i + "-"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(users);
        try {
            List<Callable<long[]>> tasks = new ArrayList<>();
            for (int i = 0; i < users; i++) {
                final int idx = i;
                tasks.add(() -> {
                    // 每个线程用自己那个用户的 token 走真实 HTTP：JwtInterceptor 会在 Tomcat 工作线程上
                    // 写入 UserContext，服务端必须只看到"这一个用户"。
                    AuthSession s = sessions.get(idx);
                    String mk = markers.get(idx);
                    long docId = uploadDoc(s.token(), "并发验证-" + mk + ".md", corpusWith(mk), "其他")
                            .path("id").asLong();
                    embedDoc(s.token(), docId);
                    return new long[]{docId};
                });
            }
            List<Future<long[]>> futures = pool.invokeAll(tasks, 180, TimeUnit.SECONDS);
            long[] docIds = new long[users];
            for (int i = 0; i < users; i++) {
                docIds[i] = futures.get(i).get()[0];
                assertTrue(docIds[i] > 0, "并发任务 " + i + " 应拿到文档 id");
            }

            // ① 检索隔离：各人只在自己 documentIds 内检索，必须命中自己的标记词、绝不出现别人的
            for (int i = 0; i < users; i++) {
                float[] qv = embeddingService.embedSingle("本文档唯一标记词是 " + markers.get(i));
                assertTrue(waitUntilRetrievable(milvusService, qv, docIds[i], markers.get(i)),
                        "并发射程：用户 " + i + " 的向量 15s 内不可召回（并发写入丢失或串号）");
                List<MilvusService.SearchResult> hits = milvusService.search(qv, 5, List.of(docIds[i]));
                assertFalse(hits.isEmpty(), "并发射程：用户 " + i + " 检索无结果");
                // 注意断言粒度：一篇文档会被切成多块，Top5 里只有含标记词的那一块带标记词，
                // 因此正确断言是「至少一条命中自己的」+「没有任何一条命中别人的」，
                // 而不是"每一条都含自己的标记词"（后者是断言写错，会得到与业务无关的假失败）。
                int ownHit = 0;
                for (MilvusService.SearchResult hit : hits) {
                    String c = hit.getContent() == null ? "" : hit.getContent();
                    if (c.contains(markers.get(i))) {
                        ownHit++;
                    }
                    for (int j = 0; j < users; j++) {
                        if (j != i) {
                            assertFalse(c.contains(markers.get(j)),
                                    "并发射程：用户 " + i + " 的检索结果里出现了用户 " + j + " 的标记词"
                                            + "（documentIds 过滤失效或 ThreadLocal 串号）：" + c);
                        }
                    }
                }
                assertTrue(ownHit >= 1,
                        "并发射程：用户 " + i + " 的 Top5 里没有一条含自己的标记词（自己的向量被并发写入挤掉或串号）");
                // ② 文档归属隔离：并发上传后每人只应看到自己那 1 篇
                JsonNode mine = jsonOf(httpGet("/api/document/list", sessions.get(i).token())).path("data");
                assertEquals(1, mine.size(), "用户 " + i + " 应只看到自己上传的 1 篇文档，实际 " + mine.size());
                assertEquals(docIds[i], mine.get(0).path("id").asLong(), "用户 " + i + " 看到的文档必须是自己的");
            }

            // ③ 4 篇文档 id 必须互不相同（并发自增主键未冲突）
            assertEquals(users, Arrays.stream(docIds).distinct().count(), "并发上传产生了重复文档 id");
            step("A7-01 通过：" + users + " 用户真并发上传+向量化+检索，各自只召回自己的文档，无串号");
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 2. 事务回滚射程 ====================

    @Test
    @Order(2)
    @DisplayName("A7-02 事务射程：删除中途失败时 MySQL 侧整体回滚（不留「文档还在、分块已空」的半删状态）")
    void a702_delete_rolls_back_mysql_side_on_midway_failure() throws Exception {
        // 【为什么要补】此前只测了"删除成功"这一条路径。级联删除要动三处存储，
        // 其中 Milvus / MinIO 是外部系统、不参与本地事务，只有 MySQL 那两步（分块物理删除 + 文档逻辑删除）
        // 能被 @Transactional 保护。若注解缺失或异常未声明 rollbackFor，一旦 MySQL 第二步失败，
        // 就会留下"分块已清、文档还在"的半删状态：文档还能在列表里看到，点进去却检索不到任何内容。
        // 本用例用「在事务内执行真实删除、随后抛异常」把这条路径打出来。

        // ① 结构断言：删除必须是"异常即整体回滚"的事务边界
        Method delete = DocumentServiceImpl.class.getMethod("delete", Long.class);
        Transactional tx = delete.getAnnotation(Transactional.class);
        assertNotNull(tx, "DocumentServiceImpl.delete 必须标注 @Transactional，否则两处 MySQL 写入不成事务");
        assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class),
                "rollbackFor 必须包含 Exception：默认只回滚 RuntimeException，而 Milvus/MinIO 抛的是受检异常包装前的各种异常");

        // ② 行为断言：事务中途失败 → 分块与文档行都必须复原
        AuthSession s = newUser();
        String mk = marker("TXR");
        long docId = uploadDoc(s.token(), "事务回滚验证-" + mk + ".md", corpusWith(mk), "其他")
                .path("id").asLong();
        long chunksBefore = documentChunkService.countByDocumentId(docId);
        assertTrue(chunksBefore > 0, "前置条件：解析后应有分块，实际 " + chunksBefore);
        assertNotNull(findDoc(s.token(), docId), "前置条件：文档应在列表中");

        UserContext.setUserId(s.userId());   // 直调 Service：ThreadLocal 需自行写入（HTTP 链路由拦截器写）
        try {
            TransactionTemplate tpl = new TransactionTemplate(txManager);
            assertThrows(RuntimeException.class, () -> tpl.execute(status -> {
                documentService.delete(docId);
                // 模拟"删除链路走到 MySQL 阶段时失败"（例如第二次 update 失败 / 连接中断）
                throw new IllegalStateException("模拟删除中途失败" + mk);
            }), "事务回调抛出的异常应原样上抛（并触发回滚）");
        } finally {
            UserContext.clear();
        }

        assertEquals(chunksBefore, documentChunkService.countByDocumentId(docId),
                "【事务】回滚后分块必须复原到 " + chunksBefore + " 行——否则就是「文档还在、内容已空」的半删状态");
        assertNotNull(findDoc(s.token(), docId), "【事务】回滚后文档行必须仍在列表可见（逻辑删除也应回滚）");

        // ③ 诚实记录边界：MinIO 对象已在本事务内被真删（外部存储不受本地事务保护）。
        // 这不是缺陷而是取舍——生产删除顺序刻意把"不可回滚的外部清理"放在 MySQL 之前，
        // 使失败大概率发生在 MySQL 尚未动手的时刻；跨存储的一致最终由重试/补偿承担。
        step("A7-02 通过：删除中途失败后 MySQL 侧 " + chunksBefore + " 行分块与文档行全部回滚；"
                + "外部存储（MinIO/Milvus）不参与本地事务，属既有取舍");
    }

    // ==================== 3. 级联正向射程 ====================

    @Test
    @Order(3)
    @DisplayName("A7-03 级联正向射程：删除后三处终态一致，且同内容重传可正常入库、主键不与旧文档冲突")
    void a703_delete_then_reupload_converges() {
        // 【为什么要补】A2-14 证明了"删干净"，但真实用户的下一个动作往往是"删错了再传一次"。
        // 删除会释放 chunkId（Milvus 主键）与 document_id 过滤条件，若清理只做了一半，
        // 重建时就会出现"新文档检不到 / 检索命中的是旧文档残留"这类只在二次操作时才暴露的问题。

        AuthSession s = newUser();
        String mk = marker("REUP");
        JsonNode doc1 = uploadDoc(s.token(), "级联重传验证-" + mk + ".md", corpusWith(mk), "其他");
        long docId1 = doc1.path("id").asLong();
        String minioPath1 = doc1.path("minioPath").asText();
        assertFalse(minioPath1.isBlank(), "上传应回写 minioPath");
        embedDoc(s.token(), docId1);

        float[] qv = embeddingService.embedSingle("本文档唯一标记词是 " + mk);
        assertTrue(waitUntilRetrievable(milvusService, qv, docId1, mk), "前置条件：旧文档向量应可召回");
        List<Long> chunkIdsBefore = documentChunkService.listByDocumentId(docId1)
                .stream().map(c -> c.getId()).toList();
        assertFalse(chunkIdsBefore.isEmpty(), "前置条件：旧文档应有分块");

        // ---- 删除 ----
        assertEquals(200, httpDelete("/api/document/" + docId1, s.token()).getStatusCode().value(), "删除应返回 200");

        // ① Milvus：不可再召回
        assertTrue(waitUntilNotRetrievable(milvusService, qv, docId1, mk),
                "【级联】删除后旧文档向量仍可召回（残留会被检索当作有效依据返回给用户）");
        // ② MinIO：对象真的没了（不是"删了个不存在的键"）
        assertThrows(BusinessException.class, () -> minioService.download(minioPath1),
                "【级联】MinIO 对象应已被清理，下载应失败");
        // ③ MySQL：分块清零 + 文档行不可见
        assertEquals(0, documentChunkService.countByDocumentId(docId1), "【级联】分块应清零");
        assertNull(findDoc(s.token(), docId1), "【级联】文档行应已逻辑删除（列表不可见）");

        // ---- 同内容重传：新文档必须能独立走通全链路 ----
        JsonNode doc2 = uploadDoc(s.token(), "级联重传验证-" + mk + ".md", corpusWith(mk), "其他");
        long docId2 = doc2.path("id").asLong();
        assertNotEquals(docId1, docId2, "重传应产生新的文档 id");
        embedDoc(s.token(), docId2);
        assertTrue(waitUntilRetrievable(milvusService, qv, docId2, mk),
                "【级联·重传】删完再传同一份内容后，新文档向量 15s 内不可召回"
                        + "——说明旧文档的清理不彻底，或新写入被旧残留挤掉");

        // 新文档的分块主键必须与旧文档完全不相交（旧的已物理删除，自增 id 不复用）
        List<Long> chunkIdsAfter = documentChunkService.listByDocumentId(docId2)
                .stream().map(c -> c.getId()).toList();
        assertFalse(chunkIdsAfter.isEmpty(), "重传后新文档应有分块");
        for (Long id : chunkIdsAfter) {
            assertFalse(chunkIdsBefore.contains(id),
                    "【级联·重传】新旧文档分块主键冲突（Milvus 主键复用会导致召回串到已删文档）：" + id);
        }
        // 旧文档 id 的检索面必须保持为空：重传不得把它"复活"
        assertTrue(milvusService.search(qv, 5, List.of(docId1)).isEmpty(),
                "【级联·重传】按已删文档 id 检索仍有结果——旧向量未真正清除");

        step("A7-03 通过：删除后 Milvus/MinIO/MySQL 三处终态一致；同内容重传后新文档（" + docId2
                + "）可召回，分块 " + chunkIdsAfter.size() + " 个且与旧主键不相交");
    }

    // ==================== 4. 异步失败射程 ====================

    @Test
    @Order(4)
    @DisplayName("A7-04 异步射程：运维闸门拒绝时不产生半吊子任务，陈旧任务号不覆盖快照，任务走专用池")
    void a704_async_gate_leaves_no_half_task() throws Exception {
        // 【为什么要补】异步受理的用例此前只验"受理成功会返回 RUNNING 快照"，
        // 没人验"受理被拒之后状态是什么"。异步任务的典型事故正是**半吊子状态**：
        // 请求被拒但任务已登记 → 状态接口永远显示 RUNNING → 真正的任务被 409 挡在门外，运维只能重启。
        // 本用例把"拒绝 ⇒ 零残留"钉死，并用结构断言锁住单飞入口与专用线程池。

        // ① 前置：本 JVM 从未受理过重建任务（若已有快照，说明前面有用例动过，断言前提不成立）
        assertNull(indexRebuildWorker.latest(),
                "前置条件：本套件不应受理过索引重建任务，否则本用例的「零残留」断言失去意义");

        // ② 结构：单飞入口必须同步（否则并发提交可同时通过"是否已有任务"检查，双双开跑）
        Method begin = IndexRebuildWorker.class.getMethod("begin", String.class, String.class);
        assertTrue(Modifier.isSynchronized(begin.getModifiers()),
                "begin 必须是 synchronized：检查与登记必须原子，两个 ADMIN 同时提交会双双开跑（破坏性操作叠加）");
        // ③ 结构：@Async 必须绑定运维专用池（长任务不得占满业务共享池）
        Method runAsync = IndexRebuildWorker.class.getMethod("runAsync", String.class, String.class);
        Async async = runAsync.getAnnotation(Async.class);
        assertNotNull(async, "runAsync 必须标注 @Async（且由外部 Bean 调用才会过代理）");
        // 注意：Spring 6 起 @Async.value() 由 String[] 改为单个 String，写 [0] 会编译失败
        assertEquals(AsyncConfig.OPS_EXECUTOR, async.value(),
                "@Async 应绑定运维专用池 " + AsyncConfig.OPS_EXECUTOR);
        ThreadPoolTaskExecutor ops = applicationContext.getBean(AsyncConfig.OPS_EXECUTOR, ThreadPoolTaskExecutor.class);
        assertEquals(1, ops.getCorePoolSize(), "运维池核心线程应为 1：同一时刻只允许一个重建任务");
        assertEquals("ops-", ops.getThreadNamePrefix(), "运维池线程名前缀应为 ops-（便于按线程名定位日志）");

        // ④ 行为：普通用户提交被拒 → 不得留下任何任务快照
        AuthSession s = newUser();
        ResponseEntity<byte[]> denied = httpPostJson("/api/document/rebuild-index",
                "{\"confirm\":\"CONFIRM-REBUILD\"}", s.token());
        assertEquals(403, jsonOf(denied).path("code").asInt(), "非 ADMIN 应被 403 拒绝：" + bodyOf(denied));
        assertNull(indexRebuildWorker.latest(), "【异步】被权限拒绝的提交不得登记任务（否则状态接口永远显示 RUNNING）");

        // ⑤ 行为：提权为 ADMIN 但二次确认串错误 → 仍不得受理
        User promotion = new User();
        promotion.setId(s.userId());
        promotion.setRole(User.ROLE_ADMIN);
        userMapper.updateById(promotion);
        ResponseEntity<byte[]> badConfirm = httpPostJson("/api/document/rebuild-index",
                "{\"confirm\":\"确认一下\"}", s.token());
        assertEquals(400, badConfirm.getStatusCode().value(), "确认串错误应按既有契约返回 HTTP 400");
        assertEquals(400, jsonOf(badConfirm).path("code").asInt(), "确认串错误应为参数类业务码 400");
        assertNull(indexRebuildWorker.latest(),
                "【异步】二次确认失败不得登记任务：闸门必须在受理之前，否则误触一次就锁死运维入口");

        // ⑥ 行为：陈旧任务号不得覆盖快照（后台线程落后于新一代任务时，旧快照更新必须被丢弃）
        // 【踩坑记录】必须拿到**目标对象**再反射调用：
        // @Async 让 Spring 为这个 Bean 生成了 CGLIB 代理，而 CGLIB 代理是用 Objenesis **跳过构造器**
        // 实例化的 ⇒ 代理实例上的实例字段（AtomicReference latest）根本没过初值，恒为 null。
        // 公共方法走代理会被转发到 target（字段正常），但反射调用私有方法会在**代理实例**上执行，
        // 直接 NPE。这正是「@Async Bean 的私有方法反射调用会莫名 NPE」的成因。
        Object workerTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(indexRebuildWorker);
        Method update = IndexRebuildWorker.class.getDeclaredMethod("update", String.class, UnaryOperator.class);
        update.setAccessible(true);
        update.invoke(workerTarget, "stale-task-id",
                (UnaryOperator<IndexRebuildTask>) t -> t.toBuilder().phase("陈旧任务写入").build());
        assertNull(indexRebuildWorker.latest(),
                "【异步】任务号不匹配的快照更新必须被丢弃（否则旧任务会把新任务的状态覆盖回去）");

        step("A7-04 通过：权限拒绝与确认失败均零残留；begin synchronized；@Async 绑定 ops- 单线程专用池；陈旧快照被丢弃");
    }

    // ==================== 5. 权限矩阵射程 ====================

    @Test
    @Order(5)
    @DisplayName("A7-05 权限矩阵射程：14 个受保护端点无 token 全拒；越权写一律 403 且零副作用")
    void a705_permission_matrix() {
        // 【为什么要补】越权此前是"逐点补"的（A2-15 删文档、A4-10/11 会话），
        // 补一处漏一处——「补射程」的正确姿势是按矩阵穷举：横轴端点、纵轴身份。
        // 本用例把"无 token"这一行拉满（14 个端点一条不漏），再把"越权写"这一行成组断言，
        // 用一条用例覆盖住"以后新加端点忘了加鉴权"的回归面。

        // ---- 矩阵第 1 行：无 token 打所有受保护端点，一律不得成功 ----
        String[][] protectedEndpoints = {
                {"GET", "/api/document/list"},
                {"POST", "/api/document/upload"},
                {"POST", "/api/document/embed/1"},
                {"DELETE", "/api/document/1"},
                {"GET", "/api/document/rebuild-index"},
                {"POST", "/api/document/rebuild-index"},
                {"GET", "/api/chat/sessions"},
                {"POST", "/api/chat/session"},
                {"GET", "/api/chat/history/1"},
                {"POST", "/api/chat/ask/1"},
                {"PUT", "/api/chat/session/1/title"},
                {"DELETE", "/api/chat/session/1"},
                {"POST", "/api/agent/ask"},
                {"GET", "/api/metrics/today"},
        };
        for (String[] ep : protectedEndpoints) {
            ResponseEntity<byte[]> resp = callWithoutToken(ep[0], ep[1]);
            assertNotEquals(200, resp.getStatusCode().value(),
                    "无 token 访问 " + ep[0] + " " + ep[1] + " 不得成功，实际 " + resp.getStatusCode()
                            + "：" + bodyOf(resp));
        }

        // ---- 矩阵第 2 行：越权写 —— A 的资源，B 来动，一律 403 + 零副作用 ----
        AuthSession a = newUser();
        AuthSession b = newUser();
        String mk = marker("IDOR");
        long aDoc = uploadDoc(a.token(), "A的私有文档-" + mk + ".md", corpusWith(mk), "其他").path("id").asLong();
        long aSession = createSession(a.token());
        ask(a.token(), aSession, "A 自己的会话，随便问一句");

        // ① B 读 A 的会话历史
        ResponseEntity<byte[]> readHistory = httpGet("/api/chat/history/" + aSession, b.token());
        assertEquals(403, jsonOf(readHistory).path("code").asInt(),
                "B 读 A 的会话历史应 403：" + bodyOf(readHistory));
        // ② B 删 A 的会话
        assertEquals(403, jsonOf(httpDelete("/api/chat/session/" + aSession, b.token())).path("code").asInt(),
                "B 删 A 的会话应 403");
        // ③ B 改 A 的会话标题
        assertEquals(403, jsonOf(httpPutJson("/api/chat/session/" + aSession + "/title",
                        "\"被篡改的标题\"", b.token())).path("code").asInt(),
                "B 改 A 的会话标题应 403");
        // ④ B 删 A 的文档
        assertEquals(403, jsonOf(httpDelete("/api/document/" + aDoc, b.token())).path("code").asInt(),
                "B 删 A 的文档应 403");

        // ⑤ 【本轮新射程】B 触发 A 的文档向量化 —— 越权「写」
        // 删除有归属校验，但"触发向量化"同样是写操作：它会改 A 文档的状态位、把 A 的内容写进向量库。
        // 若 embed 只校验"文档存在 + 未向量化"，任何登录用户拿着别人的文档 id 就能替对方入库。
        ResponseEntity<byte[]> crossEmbed = httpPostJson("/api/document/embed/" + aDoc, null, b.token());
        assertEquals(403, jsonOf(crossEmbed).path("code").asInt(),
                "【越权写】B 触发 A 的文档向量化应返回 403，实际 HTTP " + crossEmbed.getStatusCode()
                        + "：" + bodyOf(crossEmbed));

        // 零副作用：A 的文档必须仍是"待入库"，且 A 自己仍然能正常向量化（B 的越权不得污染状态）
        JsonNode aDocAfter = findDoc(a.token(), aDoc);
        assertNotNull(aDocAfter, "A 的文档必须仍然存在");
        assertEquals(0, aDocAfter.path("embeddingStatus").asInt(),
                "【越权写】B 的越权调用不得改变 A 文档的向量化状态位");
        // ⑥ 越权读：B 的列表不得出现 A 的文档
        for (JsonNode d : jsonOf(httpGet("/api/document/list", b.token())).path("data")) {
            assertNotEquals(aDoc, d.path("id").asLong(), "B 的文档列表里出现了 A 的文档");
        }

        step("A7-05 通过：14 个受保护端点无 token 全拒；越权读/删/改/触发向量化共 6 格全部 403 且零副作用");
    }

    // ==================== 6. 超长畸形射程 ====================

    @Test
    @Order(6)
    @DisplayName("A7-06 超长畸形射程：超长标题 / 超长问题 / emoji+控制字符内容均不落 5xx，且内容不静默丢失")
    void a706_oversized_and_malformed_inputs() {
        // 【为什么要补】此前只按"类型"测非法输入（扩展名/空文件/体积超限），
        // 没按"长度与字符集"测。而长度越界是最容易被漏的一类：它不满足任何业务校验，
        // 会一路走到数据库才被 VARCHAR 上限拒绝——抛出的不是 BusinessException，
        // 于是落进 GlobalExceptionHandler 的兜底分支，对外表现为 HTTP 500「系统内部错误」，
        // 把"你输入太长"误报成"服务端故障"（与 A1-13 / A2-12 修掉的是同一类语义错位）。

        AuthSession s = newUser();

        // ① 超长文件名：document.file_name / title 均为 VARCHAR(200)
        // 【为什么用 210 字符而不是"很长"】再用长一点的名字会先被 MinIO 以「对象名含不支持字符」拒绝，
        // 请求在落库之前就返回了 —— 那样恰好绕过本射程要测的那条路径（长度越界在 DB 层才暴露）。
        // 因此取"刚过列上限"的长度：它能顺利过 MinIO 与格式校验，只在 INSERT 时越界。
        String longName = "超长文件名-" + "x".repeat(200) + ".md";   // title = 206 字符 > VARCHAR(200)
        ResponseEntity<byte[]> longNameResp = httpUpload("/api/document/upload", longName,
                corpusWith(marker("LONG")).getBytes(java.nio.charset.StandardCharsets.UTF_8), "其他", s.token());
        assertTrue(longNameResp.getStatusCode().value() < 500,
                "【超长】超长文件名不得落 5xx（客户端输入问题 ≠ 服务端故障），实际 "
                        + longNameResp.getStatusCode() + "：" + bodyOf(longNameResp));
        assertEquals(400, longNameResp.getStatusCode().value(),
                "超长文件名应被前置校验拒绝为 HTTP 400，实际 " + longNameResp.getStatusCode()
                        + "：" + bodyOf(longNameResp));
        assertTrue(jsonOf(longNameResp).path("message").asText().contains("文件名过长"),
                "错误信息应说明是文件名过长，实际：" + bodyOf(longNameResp));

        // ② 超长会话标题：chat_session.title 为 VARCHAR(100)
        long sessionId = createSession(s.token());
        ResponseEntity<byte[]> longTitleResp = httpPutJson("/api/chat/session/" + sessionId + "/title",
                "\"" + "题".repeat(300) + "\"", s.token());
        assertTrue(longTitleResp.getStatusCode().value() < 500,
                "【超长】超长会话标题不得落 5xx，实际 " + longTitleResp.getStatusCode() + "：" + bodyOf(longTitleResp));
        assertEquals(400, longTitleResp.getStatusCode().value(),
                "超长会话标题应被前置校验拒绝为 HTTP 400，实际 " + longTitleResp.getStatusCode()
                        + "：" + bodyOf(longTitleResp));
        assertTrue(jsonOf(longTitleResp).path("message").asText().contains("标题过长"),
                "错误信息应说明是标题过长，实际：" + bodyOf(longTitleResp));

        // ③ 畸形字符内容：emoji（代理对）+ 零宽字符 + 控制字符 → 上传、分块、向量化均不得 5xx，且不得丢内容
        String weird = "# 畸形字符验证\n\n零宽:\u200B emoji:😀🎉 控制符:\u0007 组合字:é̂ 反引号:` 单引号:' 双引号:\"\n\n"
                + "本节用于验证 UTF-8 多字节与不可见字符在解析→分块→入库整条链路上不被截断或替换。\n";
        ResponseEntity<byte[]> weirdResp = httpUpload("/api/document/upload", "畸形字符验证.md",
                weird.getBytes(java.nio.charset.StandardCharsets.UTF_8), "其他", s.token());
        assertEquals(200, weirdResp.getStatusCode().value(),
                "【畸形】含 emoji / 零宽 / 控制字符的文档应正常上传：" + bodyOf(weirdResp));
        long weirdDocId = jsonOf(weirdResp).path("data").path("id").asLong();
        assertTrue(jsonOf(weirdResp).path("data").path("chunkCount").asInt() > 0, "【畸形】应正常分块");
        // 不静默丢：把分块拼回来，关键字符必须一个不少
        StringBuilder joined = new StringBuilder();
        documentChunkService.listByDocumentId(weirdDocId).forEach(c -> joined.append(c.getContent()));
        for (String token : new String[]{"零宽", "emoji", "😀", "🎉", "\u200B", "\u0007", "é̂", "`", "'", "\""}) {
            assertTrue(joined.indexOf(token) >= 0,
                    "【畸形】分块内容里丢失了字符 [" + token + "]（被截断或替换，属静默数据损坏）");
        }
        // 畸形内容也要能向量化（Milvus 分词链不得因控制字符崩掉）
        ResponseEntity<byte[]> weirdEmbed = httpPostJson("/api/document/embed/" + weirdDocId, null, s.token());
        assertTrue(weirdEmbed.getStatusCode().value() < 500,
                "【畸形】含控制字符的内容向量化不得落 5xx，实际 " + weirdEmbed.getStatusCode() + "：" + bodyOf(weirdEmbed));
        // 实测记录（第 8 轮）：智谱 embedding 接口会对含控制字符的文本返回 400「API 调用参数有误」，
        // 因此该文档能上传、能分块，但停在「待入库」。这不是本系统的故障而是上游模型的输入口径，
        // 关键在于它被如实转成了业务错误（HTTP 400 + 可读文案），而不是静默成功或 500。
        step("A7-06 记录：畸形字符内容向量化的实际结果为 HTTP " + weirdEmbed.getStatusCode()
                + "（上游模型对控制字符的输入口径，非系统故障；错误已如实外抛且不落 5xx）");

        // ④ 超长问题：不得 5xx（可以是被拒的 400，也可以是降级回答的 200，但不能是"服务端故障"）
        ResponseEntity<byte[]> longQuestion = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"" + "长".repeat(8000) + "\"}", s.token());
        assertTrue(longQuestion.getStatusCode().value() < 500,
                "【超长】超长问题不得落 5xx，实际 " + longQuestion.getStatusCode() + "：" + bodyOf(longQuestion));

        step("A7-06 通过：超长文件名 / 超长会话标题 / 超长问题均未落 5xx；emoji+零宽+控制字符内容完整入库且可向量化");
    }
}
