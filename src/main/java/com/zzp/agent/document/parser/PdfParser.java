package com.zzp.agent.document.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PdfParser implements DocumentParser {

    /** 页眉高度 = 页面高度的前 8%（可调） */
    private static final float HEADER_RATIO = 0.08f;
    /** 页脚高度 = 页面高度的后 8%（可调） */
    private static final float FOOTER_RATIO = 0.08f;

    /** 目录行：行尾是独立页码数字（点线式“…… 9” 或章节式“引言 1”） */
    private static final Pattern TOC_LINE =
            Pattern.compile(".*[.·•]{2,}\\s*\\d+\\s*$|.*\\s\\d{1,3}\\s*$");

    /** 目录标识行：如「目录」「目 录」（中英文均可按需扩展） */
    private static final Pattern TOC_HEADER =
            Pattern.compile("\\s*目\\s*录\\s*");

    @Override
    public String parse(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return filterTocSegments(new HeaderFilteringStripper().getText(doc));
        }
    }

    /**
     * 分段过滤目录页：命中「目录」标识后进入目录区，其间「行尾带页码」的行视为目录项丢弃；
     * 一旦遇到不带页码的普通行（即正文/前言开始），退出目录区，后续行照常保留。
     * 比逐行盲删更通用：目录识别以「目录」字样为触发条件，正文中的数字结尾行不会无差别被删。
     */
    private String filterTocSegments(String text) {
        if (text == null) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean inToc = false;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (!inToc) {
                // 尚未进入目录区：命中「目录」标识则进入
                if (TOC_HEADER.matcher(line).matches()) {
                    inToc = true;
                    // 目录标识行本身也丢弃
                    continue;
                }
                sb.append(rawLine).append('\n');
            } else {
                // 目录区内：行尾带页码 → 目录项，丢弃；否则视为目录区结束
                if (TOC_LINE.matcher(line).matches()) {
                    continue;
                }
                inToc = false;             // 目录区到此为止
                sb.append(rawLine).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.PDF);
    }

    /** 带页眉过滤的剥离器：把每页顶部 HEADER_RATIO 区域内的字符丢弃 */
    private static final class HeaderFilteringStripper extends PDFTextStripper {

        private float pageHeight;

        @Override
        protected void startPage(PDPage page) throws IOException {
            this.pageHeight = page.getMediaBox().getHeight();
            super.startPage(page);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            float y = text.getYDirAdj();
            if (y < pageHeight * HEADER_RATIO || y > pageHeight * (1 - FOOTER_RATIO)) {
                return; // 位于页眉或页脚区域，不进正文
            }
            super.processTextPosition(text);
        }
    }
}