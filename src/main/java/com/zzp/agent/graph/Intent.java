package com.zzp.agent.graph;

/**
 * 用户提问的意图。由 agent 节点用 LLM 判断，决定图的条件路由：
 * <ul>
 *   <li>{@link #CHAT}：普通知识问答 → 直接基于检索资料生成回答（走 END）；</li>
 *   <li>{@link #TOOLS}：需要调用工具（如 n2sql / search_docs）→ 进 tools 节点。</li>
 * </ul>
 * 后续加新工具时扩展此枚举即可。
 */
public enum Intent {
    CHAT,
    TOOLS
}