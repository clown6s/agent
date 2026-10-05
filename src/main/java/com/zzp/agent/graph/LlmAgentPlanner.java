package com.zzp.agent.graph;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认 agent 实现：用真实 LLM（{@link ChatModel}，deepseek）做多步 function-calling 决策。
 *
 * <p>关键：必须用 {@link ChatModel#call(Prompt)} 而非 ChatClient——
 * ChatClient 注册工具时会自动挂 {@code ToolCallAdvisor} 在内部执行完整 ReAct 循环，
 * 吞掉每步的 tool_calls；而我们要把"每步决策"交给 graph 的 tools→agent 回边，
 * 所以这里关掉 {@code DeepSeekChatOptions.internalToolExecutionEnabled(false)}，
 * 让模型每次只返回裸 tool_calls（或最终答案），由 graph 决定下一步。该开关经字节码核实
 * 只禁用内部 {@code executeToolCalls}，不影响工具 schema 的发送。
 */
@Service
public class LlmAgentPlanner implements AgentPlanner {

    private static final String SYSTEM_PROMPT = """
            你是一个自主 Agent，负责回答用户问题。规则：
            1. 需要查资料/算数据时，先调用可用工具获取事实，再基于所有工具结果作答；
            2. 一次可以并行调用多个工具；结果相互依赖时，先拿到前序结果再发起下一步调用；
            3. 信息足够后停止调用工具，直接给出最终回答；每条论断用 [来源: documentId] 标注出处；
            4. 资料不足以回答时如实说明，不要编造。
            """;

    private final ChatModel chatModel;
    /** 单次回答允许的工具调用轮数上限，可由配置 {@code agent.maxIterations} 覆盖，默认 5。 */
    private final int maxIterations;

    public LlmAgentPlanner(ChatModel chatModel, @Value("${agent.maxIterations:5}") int maxIterations) {
        this.chatModel = chatModel;
        this.maxIterations = maxIterations;
    }

    @Override
    public AssistantMessage plan(List<Message> history, List<ToolCallback> tools, int iteration) {
        StringBuilder sys = new StringBuilder(SYSTEM_PROMPT);
        if (iteration >= maxIterations) {
            // 已达轮次上限：强制模型不再调工具，直接基于已有信息作答（硬性兜底，防失控循环）
            sys.append("\n【重要】你已调用工具达到上限（")
                    .append(maxIterations).append(" 轮），请直接结合已有工具结果给出最终回答，绝对不要再调用工具。");
        }

        List<Message> promptMessages = new ArrayList<>(history.size() + 1);
        promptMessages.add(new SystemMessage(sys.toString()));
        promptMessages.addAll(history);

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(tools)
                .build();

        return chatModel.call(new Prompt(promptMessages, options)).getResult().getOutput();
    }
}