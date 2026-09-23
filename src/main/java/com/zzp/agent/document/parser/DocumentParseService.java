package com.zzp.agent.document.parser;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;


@Service
public class DocumentParseService {

    private final Map<DocumentType, DocumentParser> parserMap;
    public DocumentParseService(List<DocumentParser> parsers) {
        parserMap = new EnumMap<>(DocumentType.class);
        for (DocumentParser parser : parsers) {
            for (DocumentType type : parser.supportedTypes()) {
                parserMap.put(type, parser);
            }
        }
    }

    /** 按文件路径解析，扩展名决定走哪个解析器 */
    public ParseResult parse(String filePath) throws IOException {
        String fileName = Paths.get(filePath).getFileName().toString();
        DocumentType type = DocumentType.fromFileName(fileName);
        DocumentParser parser = parserMap.get(type);
        if (parser == null) {
            throw new UnsupportedOperationException("没有 " + type + " 的解析器");
        }
        try (InputStream in = Files.newInputStream(Path.of(filePath))) {
            String text = parser.parse(in);
            return new ParseResult(fileName, type, text, text.length());
        }
    }
}
