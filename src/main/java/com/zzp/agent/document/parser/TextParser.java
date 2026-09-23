package com.zzp.agent.document.parser;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Service
public class TextParser implements DocumentParser{
    @Override
    public String parse(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.TXT, DocumentType.MD);
    }
}
