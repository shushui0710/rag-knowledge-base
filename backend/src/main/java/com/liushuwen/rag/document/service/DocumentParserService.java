package com.liushuwen.rag.document.service;

import com.liushuwen.rag.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档解析服务 - 从PDF/Word/TXT/MD文件中提取纯文本
 *
 * 讲解要点：
 * 1. 为什么每种格式需要不同的解析方式？
 *    - PDF 是排版格式，文字藏在复杂的布局结构里，需要PDFBox"挖"出来
 *    - Word (.docx) 是XML格式，文字在<XWPFParagraph>标签里，需要POI读出来
 *    - TXT/MD 是纯文本，直接读取就行
 *
 * 2. @Service 标注 = "我是业务服务，Spring会自动创建和管理我"
 *    其他类需要解析文档时，只需注入 DocumentParserService
 *
 * 3. PDFBox 和 POI 都是Apache基金会开源项目
 *    - PDFBox: 专门处理PDF的Java库
 *    - POI: 专门处理Office文档(Word/Excel/PPT)的Java库
 *    这两个库在pom.xml里已经添加了依赖
 */
@Slf4j
@Service
public class DocumentParserService {

    /**
     * 解析文档，提取纯文本
     *
     * 根据文件类型选择不同的解析策略（策略模式）
     * 白话：看是什么格式的文件，选对应的"解压工具"
     *
     * @param file 上传的文件
     * @param fileType 文件类型（pdf/docx/md/txt）
     * @return 提取出的纯文本内容
     */
    public String parse(MultipartFile file, String fileType) {
        log.info("开始解析文档: type={}, size={}", fileType, file.getSize());

        try {
            switch (fileType) {
                case "pdf":
                    return parsePdf(file.getInputStream());
                case "docx":
                    return parseDocx(file.getInputStream());
                case "txt":
                case "md":
                    return parseText(file.getInputStream());
                default:
                    throw new BusinessException("不支持的文件格式: " + fileType);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("文档解析失败: type={}, error={}", fileType, e.getMessage(), e);
            throw new BusinessException("文档解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析PDF文件
     *
     * PDFBox的解析流程：
     * 1. Loader.loadPDF() → 加载PDF文件，创建PDDocument对象
     *    PDDocument = PDF文件在Java里的"代言人"，有了它就能操作PDF
     * 2. PDFTextStripper → 把PDF里的文字"刮"出来
     *    stripper.getText() = 逐页扫描，提取所有可见文字
     * 3. 最后关闭PDDocument释放资源
     *
     * 面试考点：
     * - PDF是排版格式，不是文本格式。同一句话可能被拆成多个"块"散落在页面上
     * - PDFBox的getText()会尝试按阅读顺序重组文字，但复杂排版可能不完美
     * - 实际项目中可能需要处理：扫描版PDF（需要OCR）、表格PDF、图片PDF
     */
    private String parsePdf(InputStream inputStream) throws IOException {
        // PDFBox 3.x 使用 Loader.loadPDF() 加载
        // 注意：PDDocument用完必须关闭，否则内存泄漏
        PDDocument document = Loader.loadPDF(inputStream.readAllBytes());
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            // 设置按页提取，每页之间用换行分隔
            stripper.setSortByPosition(true);  // 按文字位置排序（改善乱序问题）
            String text = stripper.getText(document);

            log.info("PDF解析完成: pages={}, textLength={}",
                    document.getNumberOfPages(), text.length());
            return text.trim();
        } finally {
            // finally块：无论是否出错都会执行，确保关闭资源
            // 这是Java资源管理的基本模式：try-finally
            document.close();
        }
    }

    /**
     * 解析Word (.docx) 文件
     *
     * POI的解析流程：
     * 1. XWPFDocument → 加载docx文件
     *    XWPF = XML Word Processing Format，docx本质是XML文件
     * 2. getParagraphs() → 获取所有段落
     *    Word文档由段落(Paragraph)组成，每个段落是一行或一段文字
     * 3. 遍历每个段落，提取文字getText()
     * 4. 拼接所有段落的文字，用换行分隔
     */
    private String parseDocx(InputStream inputStream) throws IOException {
        XWPFDocument docx = new XWPFDocument(inputStream);
        try {
            List<XWPFParagraph> paragraphs = docx.getParagraphs();
            StringBuilder sb = new StringBuilder();

            for (XWPFParagraph paragraph : paragraphs) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(text).append("\n");
                }
            }

            log.info("Word解析完成: paragraphs={}, textLength={}",
                    paragraphs.size(), sb.length());
            return sb.toString().trim();
        } finally {
            docx.close();
        }
    }

    /**
     * 解析纯文本文件（TXT/MD）
     *
     * 最简单的解析——直接读取文件内容
     * MD（Markdown）本质也是纯文本，只是有格式标记（#标题、**加粗等）
     * 暂时直接读取原文，保留格式标记（后续可以优化去掉标记）
     */
    private String parseText(InputStream inputStream) throws IOException {
        String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        log.info("文本解析完成: textLength={}", text.length());
        return text.trim();
    }
            // 前置②：DocumentParserService 新增 parse(InputStream, String) 重载：
        //   public String parse(InputStream in, String fileType) {
        //       switch (fileType) {
        //           case "pdf":  return parsePdf(in);
        //           case "docx": return parseDocx(in);
        //           case "txt":
        //           case "md":   return parseText(in);
        //           default:     throw new BusinessException("不支持的文件格式: " + fileType);
        //       }
        //   }
    
    public String parse(InputStream in, String fileType)throws IOException {
        switch (fileType) {
            case "pdf":  return parsePdf(in);
            case "docx": return parseDocx(in);
            case "txt":
            case "md":   return parseText(in);
            default:     throw new BusinessException("不支持的文件格式: " + fileType);
        }
    }
}
