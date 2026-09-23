package com.zzp.agent.document.split;

/**
 * 文档拆分后的一个文本块。
 *
 * @param chunkId   块唯一标识，格式 documentId_块编号（如 39bee5…_0）
 * @param documentId 所属文档的 documentId（对整篇文本计算的 SHA-256 指纹）
 * @param index     从 0 起的块序号
 * @param title     该块锚定的结构标题（如「第二章 犯罪」）；若是无标题的纯正文续块则为空字符串
 * @param text      块文本
 * @param charCount text 长度（即字符数）
 */
public record TextChunk(String chunkId, String documentId, int index,
                        String title, String text, int charCount) {
}