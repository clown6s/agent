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

@Service
public class PdfParser implements DocumentParser {

    /** 页眉高度 = 页面高度的前 8%（可调） */
    private static final float HEADER_RATIO = 0.08f;
    /** 页脚高度 = 页面高度的后 8%（可调） */
    private static final float FOOTER_RATIO = 0.08f;

    @Override
    public String parse(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new HeaderFilteringStripper().getText(doc);
        }
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