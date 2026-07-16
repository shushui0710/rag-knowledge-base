package com.liushuwen.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.liushuwen.rag.**.mapper")
public class RagKnowledgeBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagKnowledgeBaseApplication.class, args);
    }
}
