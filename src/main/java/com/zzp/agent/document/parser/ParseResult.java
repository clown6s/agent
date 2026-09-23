package com.zzp.agent.document.parser;

/** 解析结果：documentId 为对解析文本计算的 SHA-256 指纹（内容唯一标识） */
public record ParseResult(String documentId, String fileName, DocumentType type, String text, int length) {
}
