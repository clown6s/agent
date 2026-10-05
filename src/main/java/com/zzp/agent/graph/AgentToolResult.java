package com.zzp.agent.graph;

import com.zzp.agent.common.document.DocHitResponse;

import java.util.List;

/**
 * 一次工具调用的结果：给用户的回答 + 引用来源。
 *
 * @param answer  基于资料生成、已带 [来源: documentId] 标注的回答文本
 * @param sources 命中的文档块（供上层展示来源）
 */
public record AgentToolResult(String answer, List<DocHitResponse> sources) {
}
