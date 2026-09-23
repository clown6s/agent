package com.zzp.agent.document.parser;

public record ParseResult(String fileName, DocumentType type, String text, int length) {
}
