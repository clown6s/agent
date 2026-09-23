package com.zzp.agent.document.parser;

import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Service

public class DocParser implements DocumentParser{
    @Override
    public String parse(InputStream in) throws IOException {
        try (WordExtractor extractor = new WordExtractor(in)) {
            return extractor.getText();
        }
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.DOC);
    }

}
