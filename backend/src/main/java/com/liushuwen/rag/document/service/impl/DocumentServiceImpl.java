package com.liushuwen.rag.document.service.impl;

import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import com.liushuwen.rag.document.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;

    @Override
    public Document upload(MultipartFile file) {
        // TODO: 实现文件上传到MinIO + 解析文档 + 分块
        // 1. 保存文件到MinIO
        // 2. 解析文档内容（PDFBox/POI）
        // 3. 文本分块
        // 4. 存储元数据到MySQL
        log.info("上传文档: {}", file.getOriginalFilename());
        throw new BusinessException("文档上传功能待实现 - 第2周开发");
    }

    @Override
    public List<Document> list() {
        // TODO: 查询用户的文档列表
        return documentMapper.selectList(null);
    }

    @Override
    public void delete(Long id) {
        // TODO: 删除MinIO文件 + MySQL记录 + Milvus向量
        log.info("删除文档: {}", id);
        documentMapper.deleteById(id);
    }

    @Override
    public void embed(Long id) {
        // TODO: 实现文档向量化入库
        // 1. 读取文档分块
        // 2. 调用Embedding API/模型生成向量
        // 3. 存入Milvus
        log.info("向量化文档: {}", id);
        throw new BusinessException("向量化入库功能待实现 - 第3周开发");
    }
}
