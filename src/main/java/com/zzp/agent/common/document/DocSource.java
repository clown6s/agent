package com.zzp.agent.common.document;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 与 ES 索引文档的 _source 结构一一对应的反序列化载体。
 *
 * <p>注意索引里用的是下划线命名 document_id，须用 @JsonProperty 映射到 Java 驼峰字段，
 * 否则 Jackson 反序列化拿不到值。
 */
public record DocSource(
        @JsonProperty("document_id") String documentId,
        String text) {
}
