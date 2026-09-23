package com.zzp.agent.document.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public interface DocumentParser {
    String parse(InputStream in) throws IOException;

    default Set<DocumentType> supportedTypes() {
        return Set.of();
    }
}
