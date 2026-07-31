package com.liushuwen.rag.document.service;

import com.liushuwen.rag.document.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    Document upload(MultipartFile file, String category);

    List<Document> list();

    void delete(Long id);

    void embed(Long id);
}
