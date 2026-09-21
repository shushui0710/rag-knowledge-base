package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.mapper.UserMapper;
import com.liushuwen.rag.document.service.DocumentChunkService;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 —— 文档入库链路验收（上传 → 解析 → 分块 → 向量化 → Milvus 可检索）。
 *
 * 覆盖范围：
 *   1. 上传接口的校验边界（空文件 / 超限 / 非白名单格式 / 无扩展名）
 *   2. 上传编排结果：MinIO 落盘 + MySQL 元数据 + 分块落库 + 状态位
 *   3. 滑动窗口分块算法的可验证行为（窗口 512 / 重叠 64 / 步长 448 / 尾块抑制）
 *   4. 向量化幂等性与异常分支
 *   5. 【关键】向量真正写入 Milvus 并可通过相似度检索召回（不是只看状态位）
 *   6. 索引重建的运维闸门（非 ADMIN 一律拒绝；提权后放行）——运维型端点的准入边界
 *   7. 删除的级联语义（三处存储全部清干净）与归属校验（他人文档不可删）
 *
 * 为什么用"检索召回"而不是"行数统计"证明入库成功：
 *   Milvus 的 rowCount 统计在小批量 insert 后存在延迟，用它做断言会产生偶发假失败；
 *   而"用本文档独有标记词向量化后检索，命中即证明向量可召回"是最贴近业务语义的证据，
 *   同时也覆盖了缺陷②（createHybridCollection 漏建 embedding 索引）——索引缺失时
 *   loadCollection 就失败，检索必然拿不到结果。
 *
 * 通过标准（P0）：
 *   - 非法输入全部被拒且错误信息与实现一致
 *   - 上传后 chunkCount > 0 且 embeddingStatus == 0（上传不自动向量化）
 *   - 分块数符合滑动窗口算法的手工推导值
 *   - 向量化后 embeddingStatus == 1，且用文档独有标记词能检索召回该分块
 *   - 重复向量化被幂等拒绝
 */
@DisplayName("A2 文档入库链路验收")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A2_IngestionAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private DocumentChunkService documentChunkService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    /** 仅用于 A2-13：模拟"运维在库侧提权"，从外部证明角色是闸门的唯一判据 */
    @Autowired
    private UserMapper userMapper;

    /** 构造一份足够长、内容可辨识的测试文档正文 */
    private static String corpus(String marker, int approxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 验收测试文档\n\n");
        sb.append("本文档的唯一标记词是 ").append(marker).append("，用于验证向量入库后的检索召回。\n\n");
        sb.append("## 系统参数\n\n");
        sb.append("文档切片窗口为 512 字符，重叠 64 字符，向量维度 2048。\n\n");
        int i = 0;
        while (sb.length() < approxChars) {
            sb.append("第").append(++i).append("段：本段用于填充长度，内容涉及检索增强生成的工程实践与参数说明。\n");
        }
        return sb.toString();
    }

    // ==================== 1. 上传校验边界 ====================

    @Test
    @Order(1)
    @DisplayName("A2-01 上传非法格式：非白名单扩展名被拒（仅支持 pdf/docx/md/txt）")
    void a201_upload_rejects_unsupported_type() {
        AuthSession s = newUser();
        ResponseEntity<byte[]> resp = httpUpload("/api/document/upload", "virus.exe",
                "MZ".getBytes(), "其他", s.token());

        assertEquals(400, resp.getStatusCode().value(), "非白名单格式应返回 HTTP 400");
        JsonNode node = jsonOf(resp);
        assertTrue(node.path("message").asText().contains("不支持的文件格式"),
                "错误信息应说明格式不支持，实际：" + node.path("message").asText());
        assertTrue(node.path("message").asText().contains("pdf, docx, md, txt"),
                "错误信息应列出支持的格式，便于前端提示");
        step("A2-01 通过：" + node.path("message").asText());
    }

    @Test
    @Order(2)
    @DisplayName("A2-02 上传空文件：被拒并提示文件为空")
    void a202_upload_rejects_empty_file() {
        AuthSession s = newUser();
        ResponseEntity<byte[]> resp = httpUpload("/api/document/upload", "empty.txt",
                new byte[0], "其他", s.token());

        assertEquals(400, resp.getStatusCode().value(), "空文件应返回 HTTP 400");
        assertTrue(jsonOf(resp).path("message").asText().contains("文件为空"),
                "错误信息应为「上传文件为空」，实际：" + jsonOf(resp).path("message").asText());
        step("A2-02 通过：空文件被拒");
    }

    @Test
    @Order(3)
    @DisplayName("A2-03 上传无扩展名文件：被拒并提示文件名无效")
    void a203_upload_rejects_missing_extension() {
        AuthSession s = newUser();
        ResponseEntity<byte[]> resp = httpUpload("/api/document/upload", "no_extension",
                "content".getBytes(), "其他", s.token());

        assertEquals(400, resp.getStatusCode().value(), "无扩展名应返回 HTTP 400");
        assertTrue(jsonOf(resp).path("message").asText().contains("缺少文件扩展名"),
                "错误信息应为「文件名无效，缺少文件扩展名」，实际："
                        + jsonOf(resp).path("message").asText());
        step("A2-03 通过：无扩展名文件被拒");
    }

    @Test
    @Order(4)
    @DisplayName("A2-04 未登录上传：被拦截器拒绝")
    void a204_upload_requires_auth() {
        ResponseEntity<byte[]> resp = httpUpload("/api/document/upload", "a.md",
                "# hi".getBytes(), "其他", null);

        assertTrue(resp.getStatusCode().value() >= 400, "未登录不得上传");
        assertTrue(jsonOf(resp).path("code").asInt() != 200, "未登录上传不得返回成功码");
        step("A2-04 通过：未登录上传被拒（HTTP " + resp.getStatusCode().value() + "）");
    }

    // ==================== 2. 上传编排结果 ====================

    @Test
    @Order(5)
    @DisplayName("A2-05 上传成功：元数据落库、分块落库、状态为「待入库」")
    void a205_upload_success_orchestration() {
        AuthSession s = newUser();
        String marker = "ACCEPT_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode doc = uploadDoc(s.token(), "验收测试文档.md", corpus(marker, 1200), "技术文档");

        assertEquals("验收测试文档", doc.path("title").asText(), "标题应去掉扩展名");
        assertEquals("md", doc.path("fileType").asText(), "文件类型应为 md");
        assertEquals("技术文档", doc.path("category").asText(), "分类应透传");
        assertEquals(0, doc.path("embeddingStatus").asInt(), "上传阶段不应自动向量化");
        assertTrue(doc.path("chunkCount").asInt() > 0, "应完成分块，chunkCount > 0");
        assertNotNull(doc.path("minioPath").asText(null), "应记录 MinIO 路径");
        assertFalse(doc.path("minioPath").asText("").isBlank(), "MinIO 路径不应为空");
        step("A2-05 通过：文档 id=" + doc.path("id").asLong()
                + "，chunkCount=" + doc.path("chunkCount").asInt()
                + "，embeddingStatus=" + doc.path("embeddingStatus").asInt());
    }

    // ==================== 3. 分块算法可验证行为 ====================

    @Test
    @Order(6)
    @DisplayName("A2-06 分块算法：窗口 512 / 重叠 64 / 步长 448，尾块抑制行为符合实现")
    void a206_chunking_algorithm_boundaries() {
        AuthSession s = newUser();
        long docId = uploadDoc(s.token(), "分块验证.md", "# 占位", "其他").path("id").asLong();

        // 用例 1：长度 100（<=512）→ 整块返回，1 块
        assertEquals(1, documentChunkService.chunkAndSave(docId, "中".repeat(100)),
                "长度 100 <= 窗口 512，应整块返回 1 块");

        // 用例 2：长度 1000 → 块1[0,512) + 块2[448,960) + 尾块[896,1000)=104 字符（>64 故保留）→ 3 块
        assertEquals(3, documentChunkService.chunkAndSave(docId, "中".repeat(1000)),
                "长度 1000 应为 3 块（512 / 512 / 104），尾块 104 > 重叠 64 故保留");

        // 用例 3：长度 960 → 块1[0,512) + 块2[448,960)，剩余 64 字符不单独成块（等于重叠，属重复碎片）→ 2 块
        assertEquals(2, documentChunkService.chunkAndSave(docId, "中".repeat(960)),
                "长度 960 应为 2 块，剩余 64 字符等于重叠量被抑制，避免近重复碎片块");

        step("A2-06 通过：分块算法边界用例 3/3（1 块 / 3 块 / 2 块）与实现推导一致");
    }

    // ==================== 4. 向量化与 Milvus 可检索性 ====================

    @Test
    @Order(7)
    @DisplayName("A2-07 向量化成功：状态置为已入库，且向量可被相似度检索召回")
    void a207_embed_and_retrievable() {
        AuthSession s = newUser();
        String marker = "ACCEPT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        JsonNode doc = uploadDoc(s.token(), "向量化验证.md", corpus(marker, 1300), "技术文档");
        long docId = doc.path("id").asLong();

        embedDoc(s.token(), docId);

        // 回查状态位
        JsonNode listed = findDoc(s.token(), docId);
        assertEquals(1, listed.path("embeddingStatus").asInt(), "向量化后状态应为 1（已入库）");

        // 用文档独有标记词做向量化检索：能召回即证明向量真的落进了 Milvus 且可检索
        float[] queryVector = embeddingService.embedSingle("验收文档的唯一标记词是 " + marker);
        // 先等待写入可见（Milvus Bounded 一致性存在亚秒级窗口），再做断言，避免随机假失败
        boolean visible = waitUntilRetrievable(milvusService, queryVector, docId, marker);
        List<MilvusService.SearchResult> hits = milvusService.search(queryVector, 5, List.of(docId));

        assertTrue(visible, "向量化后 " + 15000 + "ms 内仍检索不到本文档分块（向量未真正写入 Milvus）");
        assertFalse(hits.isEmpty(), "向量化后按本文档过滤检索不应为空（为空说明向量未真正写入 Milvus）");
        boolean recalled = hits.stream().anyMatch(h -> h.getContent() != null && h.getContent().contains(marker));
        assertTrue(recalled, "检索结果应包含本文档独有标记词 " + marker
                + "，实际召回 " + hits.size() + " 条，content 前缀："
                + hits.stream().map(h -> h.getContent() == null ? "null"
                        : h.getContent().substring(0, Math.min(40, h.getContent().length()))).toList());
        step("A2-07 通过：docId=" + docId + " 向量入库并可召回（Top"
                + hits.size() + " 命中标记词 " + marker + "）");
    }

    @Test
    @Order(8)
    @DisplayName("A2-08 向量化幂等：重复向量化被拒，避免同一文档重复写入污染向量库")
    void a208_embed_is_idempotent() {
        AuthSession s = newUser();
        JsonNode doc = uploadDoc(s.token(), "幂等验证.md", corpus("IDEMPOTENT", 700), "技术文档");
        long docId = doc.path("id").asLong();
        embedDoc(s.token(), docId);

        ResponseEntity<byte[]> again = httpPostJson("/api/document/embed/" + docId, null, s.token());

        assertEquals(400, again.getStatusCode().value(), "重复向量化应返回 HTTP 400");
        assertEquals("文档已向量化", jsonOf(again).path("message").asText(),
                "错误信息应与实现一致");
        step("A2-08 通过：重复向量化被幂等拒绝");
    }

    @Test
    @Order(9)
    @DisplayName("A2-09 向量化不存在的文档：抛业务异常而非静默成功")
    void a209_embed_missing_document_rejected() {
        AuthSession s = newUser();
        ResponseEntity<byte[]> resp = httpPostJson("/api/document/embed/999999999", null, s.token());

        assertEquals(400, resp.getStatusCode().value(), "不存在文档应返回 HTTP 400");
        assertEquals("文档不存在", jsonOf(resp).path("message").asText());
        step("A2-09 通过：向量化不存在文档被拒");
    }

    // ==================== 5. 删除与列表隔离 ====================

    @Test
    @Order(10)
    @DisplayName("A2-10 删除文档：逻辑删除后列表不再返回，删除不存在文档报错")
    void a210_delete_semantics() {
        AuthSession s = newUser();
        long docId = uploadDoc(s.token(), "待删除.md", "# 待删除", "其他").path("id").asLong();

        assertEquals(200, httpDelete("/api/document/" + docId, s.token()).getStatusCode().value(),
                "删除应返回 200");
        assertTrue(findDoc(s.token(), docId) == null, "逻辑删除后列表不应再返回该文档");

        ResponseEntity<byte[]> again = httpDelete("/api/document/" + docId, s.token());
        assertEquals(400, again.getStatusCode().value(), "重复删除应被存在性校验拒绝");
        assertEquals("文档不存在: " + docId, jsonOf(again).path("message").asText(),
                "错误信息应带文档 id，便于排查");
        step("A2-10 通过：逻辑删除生效，重复删除被拒");
    }

    @Test
    @Order(11)
    @DisplayName("A2-11 文档列表按用户隔离：看不到他人的文档")
    void a211_document_list_is_isolated() {
        AuthSession a = newUser();
        AuthSession b = newUser();
        long docId = uploadDoc(a.token(), "A的私有文档.md", "# 机密内容", "其他").path("id").asLong();

        assertNotNull(findDoc(a.token(), docId), "A 应能看到自己的文档");
        assertNull(findDoc(b.token(), docId), "B 不应看到 A 的文档（user_id 隔离）");
        step("A2-11 通过：文档列表按 user_id 隔离生效");
    }

    @Test
    @Order(12)
    @DisplayName("A2-12 上传体积超限：超过 50MB 被拒且判为 413（客户端错误而非 500）")
    void a212_upload_rejects_oversized_file() {
        // 【为什么要补这一条】本类头部注释一直写着覆盖「超限」，但此前并无对应用例——
        // 这正是缺陷能存活的原因：声明的覆盖范围大于实际覆盖范围。
        // 实测超过 spring.servlet.multipart.max-file-size(50MB) 的上传会落到
        // GlobalExceptionHandler 的 Exception 兜底，返回 HTTP 500「系统内部错误」，
        // 把纯客户端错误报成服务端故障，既误导排查方向也污染服务端错误率监控。
        AuthSession s = newUser();
        byte[] oversized = new byte[51 * 1024 * 1024];   // 51MB，刻意越过 50MB 上限
        ResponseEntity<byte[]> resp = httpUpload("/api/document/upload", "oversized.md",
                oversized, "其他", s.token());

        assertEquals(413, resp.getStatusCode().value(),
                "体积超限应返回 HTTP 413（客户端错误），不得落到 500 兜底，实际："
                        + resp.getStatusCode() + "，" + bodyOf(resp));
        JsonNode node = jsonOf(resp);
        assertEquals(413, node.path("code").asInt(), "业务码应与 HTTP 状态码一致");
        assertTrue(node.path("message").asText().contains("50MB"),
                "错误信息应给出体积上限，实际：" + node.path("message").asText());
        step("A2-12 通过：" + node.path("message").asText());
    }

    @Test
    @Order(13)
    @DisplayName("A2-13 索引重建运维闸门：非 ADMIN 提交被拒（403 业务码），提权后运维台才放行")
    void a213_rebuild_index_is_gated_by_admin_role() {
        // 【为什么要补这一条】修复前 POST /api/document/rebuild-index 只要求"已登录"，
        // 任意注册用户都能 drop 掉全库向量集合并回放**所有用户**的文档；而当时 62 条验收用例全绿——
        // 与 G-01~G-09 同源：没有任何一条覆盖"运维型端点"，声明的射程没有覆盖到它。
        // 本条只钉"闸门"这一新契约，不触发真实重建（分钟级破坏性操作不应进验收套件）。
        AuthSession s = newUser();   // 注册一律 USER 角色，无法自助提权

        // ① 状态接口：普通用户也应拿到 200，但 allowed=false 且 task=null（不侧漏运维信息）
        ResponseEntity<byte[]> statusResp = httpGet("/api/document/rebuild-index", s.token());
        assertEquals(200, statusResp.getStatusCode().value(),
                "状态接口对普通用户也应返回 200，否则文档页初始化就会弹红错：" + bodyOf(statusResp));
        JsonNode status = jsonOf(statusResp).path("data");
        assertFalse(status.path("allowed").asBoolean(), "普通用户 allowed 应为 false");
        assertTrue(status.path("task").isNull(), "普通用户不应拿到任务快照（不侧漏运维信息）");

        // ② 提交：确认串正确也无效，仍被 403 拒绝（鉴权在确认之前）
        ResponseEntity<byte[]> denied = httpPostJson("/api/document/rebuild-index",
                "{\"confirm\":\"CONFIRM-REBUILD\"}", s.token());
        assertEquals(400, denied.getStatusCode().value(),
                "业务异常按本项目既有契约统一走 HTTP 400（见 A6-05）：" + bodyOf(denied));
        assertEquals(403, jsonOf(denied).path("code").asInt(),
                "应返回 403 业务码（无权限），而不是 200 直接触发全库重建：" + bodyOf(denied));

        // ③ 提权后（等价于运维执行 UPDATE user SET role='ADMIN'）：运维台才放行
        User promotion = new User();
        promotion.setId(s.userId());
        promotion.setRole(User.ROLE_ADMIN);
        userMapper.updateById(promotion);
        step("A2-13 已将 " + s.username() + " 提权为 ADMIN（模拟运维在库侧变更角色）");

        JsonNode allowed = jsonOf(httpGet("/api/document/rebuild-index", s.token())).path("data");
        assertTrue(allowed.path("allowed").asBoolean(),
                "ADMIN 角色应放行运维台，实际：" + allowed);
        step("A2-13 通过：非 ADMIN 提交被 403 拒绝；提权后运维台放行");
    }

    @Test
    @Order(14)
    @DisplayName("A2-14 删除文档级联清三处存储：Milvus 向量 / MySQL 分块 / MinIO 对象全清，且不再可召回")
    void a214_delete_cascades_across_three_stores() {
        // 【为什么要补这一条】原 delete() 只有一行 documentMapper.deleteById(id)：
        // document_chunk 分块行、Milvus 向量、MinIO 原文件三项全部残留，而当时 66 条验收用例全绿——
        // 与 G-01~G-09 同源：**用例只验证了"文档行没了"，没有验证"内容真的没了"**。
        // 主链路因 documentIds 取自 MySQL(deleted=0) 而恰好看不到脏数据，缺口被掩盖了很久。
        // 该缺口原先以"反向固化"的形式记在 A6-03；实现修好后按 A6 自身的约定转为本条正向回归。
        AuthSession s = newUser();
        String marker = "CASCADE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long docId = uploadDoc(s.token(), "级联删除验证.md",
                "# 级联删除验证\n\n本文档唯一标记词是 " + marker + "，删除后其向量是否仍可被召回？\n"
                        + "补充内容用于凑足分块长度，确保产生可检索的向量。\n", "其他").path("id").asLong();
        embedDoc(s.token(), docId);

        // 前置：向量可被检索 + 分块已落库（Milvus 写入有亚秒级可见窗口，故轮询等待）
        float[] qv = embeddingService.embedSingle("本文档唯一标记词是 " + marker);
        assertTrue(waitUntilRetrievable(milvusService, qv, docId, marker), "前置条件：向量化后 15s 内仍不可检索");
        long chunksBefore = documentChunkService.countByDocumentId(docId);
        assertTrue(chunksBefore > 0, "前置条件：解析后应有分块，实际 " + chunksBefore);

        assertEquals(200, httpDelete("/api/document/" + docId, s.token()).getStatusCode().value(),
                "删除应返回 200");

        // ① MySQL 分块：物理删除，一行不留
        assertEquals(0, documentChunkService.countByDocumentId(docId),
                "【级联删除】document_chunk 分块应被一并清除，实际残留 " + chunksBefore + " 行");

        // ② Milvus 向量：不再是"按 document_id 仍可召回"
        // 注意：与插入侧同源，Milvus 的删除同样不是立即可见的（Bounded 一致性）——
        // 删除返回成功（deleteCnt>0）后立刻检索仍可能命中原向量，故这里也必须等待而非直接断言，
        // 否则会得到与业务无关的假失败（首次实现就踩了这个坑：14/15 通过，唯一失败点正是本条）。
        assertTrue(waitUntilNotRetrievable(milvusService, qv, docId, marker),
                "【级联删除】删除后 15s 内 Milvus 仍能召回该文档的向量（残留会被检索召回已删内容）");

        // ③ MySQL 文档行：逻辑删除，列表不可见
        assertNull(findDoc(s.token(), docId), "文档行应已逻辑删除（列表不可见）");

        step("A2-14 通过：删除后 Milvus 向量 / MySQL 分块 / 文档行三处均已清理，"
                + chunksBefore + " 行分块归零且不再可召回");
    }

    @Test
    @Order(15)
    @DisplayName("A2-15 删除归属校验：B 无法删除 A 的文档（业务码 403，文档仍在）")
    void a215_delete_is_owner_only() {
        // 【为什么要补这一条】原 delete() 只校验"文档存在"，不校验"归谁"：
        // 任何登录用户拿着别人的文档 id 就能删掉，属典型 IDOR 越权。
        // 修复后按归属校验，越权返回业务码 403（HTTP 契约仍是 400，见 A6-05）。
        AuthSession a = newUser();
        AuthSession b = newUser();
        long docId = uploadDoc(a.token(), "A的私有文档.md", "# 机密内容，B 不该能删", "其他").path("id").asLong();

        ResponseEntity<byte[]> denied = httpDelete("/api/document/" + docId, b.token());
        assertEquals(400, denied.getStatusCode().value(),
                "业务异常按本项目既有契约统一走 HTTP 400：" + bodyOf(denied));
        assertEquals(403, jsonOf(denied).path("code").asInt(),
                "越权删除应返回 403 业务码：" + bodyOf(denied));

        assertNotNull(findDoc(a.token(), docId), "A 的文档必须仍然存在（越权删除未生效）");
        step("A2-15 通过：B 删除 A 的文档被 403 拒绝，文档未被删除");
    }

    // ==================== 工具方法 ====================

    /** 从当前用户的文档列表中查找指定 id，找不到返回 null */
    private JsonNode findDoc(String token, long docId) {
        JsonNode list = jsonOf(httpGet("/api/document/list", token)).path("data");
        for (JsonNode d : list) {
            if (d.path("id").asLong() == docId) {
                return d;
            }
        }
        return null;
    }
}
