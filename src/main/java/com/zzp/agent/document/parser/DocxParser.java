package com.zzp.agent.document.parser;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Service
public class DocxParser implements DocumentParser {

    @Override
    public String parse(InputStream in) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            return new XWPFWordExtractor(doc).getText();
        }
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.DOCX);
    }

}
