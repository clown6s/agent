package com.zzp.agent.graph;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Agent 的"思考"策略：跑一次 LLM 调用，返回模型发出的 assistant 消息。
 *
 * <p>返回的 {@link AssistantMessage} 可能携带 {@code tool_calls}（→ 进 tools 节点执行并回边），
 * 也可能是不带工具调用的最终回答文本（→ 进 answer）。策略可替换：默认用真实 LLM
 * （{@link LlmAgentPlanner}），测试注入"脚本化返回 tool_calls 序列"的假实现，确定性验证图的循环。
 */
public interface AgentPlanner {

    /**
     * 执行一次 agent 思考。
     *
     * @param history   当前累积的消息（用户提问 + 既往的工具调用/结果）
     * @param tools     全部工具的 schema-only callback，模型据此做 function-calling
     * @param iteration 已完成的工具轮数（0 起），用于达到上限时强制模型收尾
     * @return 模型发出的 assistant 消息
     */
    AssistantMessage plan(List<Message> history, List<ToolCallback> tools, int iteration);
}