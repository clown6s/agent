package com.zzp.agent.document.parser;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;


@Service
public class DocumentParseService {

    private final Map<DocumentType, DocumentParser> parserMap;

    public DocumentParseService(List<DocumentParser> parsers) {
        parserMap = new EnumMap<>(DocumentType.class);
        for (DocumentParser parser : parsers) {
            for (DocumentType type : parser.supportedTypes()) {
                DocumentParser existing = parserMap.putIfAbsent(type, parser);
                if (existing != null && existing != parser) {
                    throw new IllegalStateException("文档类型 " + type + " 已被 "
                            + existing.getClass().getSimpleName() + " 注册，重复注册："
                            + parser.getClass().getSimpleName());
                }
            }
        }
    }

    /** 按文件路径解析，扩展名决定走哪个解析器 */
    public ParseResult parse(String fileName, InputStream in) throws IOException {
        DocumentType type = DocumentType.fromFileName(fileName);
        DocumentParser parser = parserMap.get(type);
        if (parser == null) {
            throw new UnsupportedOperationException("没有 " + type + " 的解析器");
        }
        String text = parser.parse(in);
        String documentId = computeDocumentId(text);
        return new ParseResult(documentId, fileName, type, text, text.length());
    }

    /** 对解析文本计算 SHA-256 指纹，作为内容唯一标识 documentId */
    private String computeDocumentId(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 规范必有的算法，正常情况下不会触发
            throw new IllegalStateException("系统不支持 SHA-256 算法", e);
        }
    }
}
