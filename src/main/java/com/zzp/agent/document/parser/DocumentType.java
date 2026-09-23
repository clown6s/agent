package com.zzp.agent.document.parser;

import java.util.Locale;

public enum DocumentType {

    PDF, DOCX, DOC, TXT, MD;
    public static DocumentType fromFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("无法识别文件类型（无扩展名）: " + fileName);
        }
        return switch (lower.substring(dot + 1)) {
            case "pdf" -> PDF;
            case "docx" -> DOCX;
            case "doc" -> DOC;
            case "txt" -> TXT;
            case "md", "markdown" -> MD;
            default -> throw new IllegalArgumentException("不支持的文档类型: " + fileName);
        };
    }
}
