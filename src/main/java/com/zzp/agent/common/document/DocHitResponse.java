package com.zzp.agent.common.document;

import co.elastic.clients.elasticsearch.core.search.Hit;

/**
 * ES 检索命中的接收对象。
 *
 * <p>字段来源：
 * <ul>
 *   <li>chunkId    ← ES 文档 _id（hit.id()）</li>
 *   <li>documentId ← _source 的 document_id</li>
 *   <li>text       ← _source 的 text</li>
 *   <li>score      ← ES 的 _score（hit.score()）</li>
 * </ul>
 *
 * <p>chunkId 与 score 位于命中元数据（不在 _source），由 {@link #from(Hit)} 手动合并。
 */
public record DocHitResponse(String chunkId, String documentId, String text, Double score) {

    /** 由 ES 命中 {@link Hit} 组装。 */
    public static DocHitResponse from(Hit<DocSource> hit) {
        DocSource s = hit.source();
        return new DocHitResponse(hit.id(), s.documentId(), s.text(), hit.score());
    }
}
