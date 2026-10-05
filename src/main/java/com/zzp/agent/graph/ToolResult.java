package com.zzp.agent.graph;

import com.zzp.agent.common.document.DocHitResponse;

import java.util.List;

/**
 * 一次工具调用的结果：给 LLM 的原始事实输出 + 引用的文档来源。
 *
 * <p>在多步 function-calling 里，工具是纯函数（只返回事实），不做最终作答——
 * 最终回答由 agent 的 LLM 综合所有工具结果生成。{@code output} 是喂回给 LLM 的文本，
 * {@code sources} 供上层展示来源。
 *
 * @param name    工具名
 * @param output  该工具返回给 LLM 的原始结果文本
 * @param sources 命中/引用的文档块（供上层展示来源）
 */
public record ToolResult(String name, String output, List<DocHitResponse> sources) {
}
