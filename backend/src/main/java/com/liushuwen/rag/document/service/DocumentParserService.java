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
 * 文档解析服务：按文件类型（PDF/Word/TXT/MD）提取纯文本。
 * 位于上传与向量化之间，产出的文本是后续分块与 Embedding 的输入。
 * 【设计要点】解析库选型：PDF 用 PDFBox（轻量纯 Java、专注 PDF），Word 用 POI（XWPF 处理 OOXML），TXT/MD 直接按 UTF-8 读取
 * 【常见问题】扫描版 PDF 怎么办？——页面是图片无文本层，PDFBox 抽不出字，需先 OCR；复杂排版为何可能乱序？——PDF 是排版格式、文字按坐标散落，setSortByPosition(true) 按位置重排可缓解
 */
@Slf4j
@Service
public class DocumentParserService {

    /**
     * 解析文档，按 fileType 路由到对应解析策略，提取纯文本。
     * 【设计要点】策略路由：switch 分发到 parsePdf/parseDocx/parseText，不支持的格式抛 BusinessException 快速失败
     * 【常见问题】解析异常怎么传播？——统一包装为 BusinessException 上抛，由全局异常处理器转成友好响应，避免底层 IOException 泄露到接口层
     *
     * @param file     上传的文件
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
     * 解析 PDF：PDFBox 加载文档后抽取全部文本。
     * 【设计要点】PDDocument 生命周期：必须 close 释放内存与文件句柄，try-finally 兜底保证异常路径也不泄漏
     * 【常见问题】setSortByPosition(true) 解决什么？——PDF 文字按坐标存储，不排序可能输出乱序；Loader.loadPDF 为什么传字节数组？——PDFBox 3.x 新 API，随机访问需要完整字节
     */
    private String parsePdf(InputStream inputStream) throws IOException {
        // 功能：PDFBox 3.x 用 Loader.loadPDF() 加载字节流｜要点：PDF 是排版格式，需专用库还原文本流
        PDDocument document = Loader.loadPDF(inputStream.readAllBytes());
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // 功能：按文字坐标排序输出｜要点：缓解 PDF 乱序问题
            String text = stripper.getText(document);

            log.info("PDF解析完成: pages={}, textLength={}",
                    document.getNumberOfPages(), text.length());
            return text.trim();
        } finally {
            // 功能：finally 兜底关闭 PDDocument｜要点：不关闭会泄漏内存与文件句柄
            document.close();
        }
    }

    /**
     * 解析 Word（.docx）：POI XWPF 读取 OOXML 段落并按换行拼接。
     * 【设计要点】docx 本质是 ZIP 包裹的 XML：XWPFDocument 解包后按 Paragraph 粒度遍历取 getText()
     * 【常见问题】POI 与 PDFBox 怎么分工？——POI 主攻 Office 系（Word/Excel/PPT），PDFBox 专注 PDF；表格文字能取到吗？——getParagraphs 不含表格，需另遍历 XWPFTable
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
     * 解析纯文本（TXT/MD）：直接按 UTF-8 读取全部字节。
     * 【设计要点】编码处理：显式指定 StandardCharsets.UTF_8，避免平台默认编码差异导致中文乱码
     * 【常见问题】MD 的格式标记（#、**）要不要去掉？——当前保留原文，标记对语义检索影响有限，需要更干净时可加预处理
     */
    private String parseText(InputStream inputStream) throws IOException {
        String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        log.info("文本解析完成: textLength={}", text.length());
        return text.trim();
    }
    /**
     * 解析已加载的输入流（重传/重解析场景：文件已从 MinIO 读出，不再经 MultipartFile）。
     * 【设计要点】方法重载：与 parse(MultipartFile, String) 共用同一套路由逻辑，入参形态不同、行为一致
     * 【常见问题】为什么入参用 InputStream 而不是 byte[]？——流式接口对调用方更通用，实现内部按需 readAllBytes
     */
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
