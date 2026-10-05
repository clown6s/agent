package com.zzp.agent.graph;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心：收集所有 {@link AgentTool} Spring bean，按 {@link AgentTool#name()} 提供按名派发，
 * 并生成"仅声明 schema"的 {@link ToolCallback} 交给 LLM。
 *
 * <p>多步 function-calling 里，工具的实际执行由 graph 的 tools 节点经 {@link #byName(String)} 派发，
 * 不走 spring-ai 的自动执行——因此这里生成的 callback 的 function 是 no-op（从不被调用），
 * 只借其 {@link ToolDefinition} 的 schema 给模型声明工具。执行阶段由
 * {@code DeepSeekChatOptions.internalToolExecutionEnabled(false)} 关掉，与字节码
 * "开关只关 executeToolCalls、不影响发工具" 的实测一致。
 *
 * <p>新增工具 = 新增一个 {@link AgentTool} bean，自动被收集，无需改这里。
 */
@Service
public class ToolRegistry {

    private final Map<String, AgentTool> byName = new HashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool t : tools) {
            byName.put(t.name(), t);
        }
    }

    /** 按名取工具；未注册则抛错（防止静默吞掉）暴露编排问题。 */
    public AgentTool byName(String name) {
        AgentTool tool = byName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return tool;
    }

    public List<AgentTool> all() {
        return List.copyOf(byName.values());
    }

    /** 产出全部工具的 schema-only callback，供 agent 节点一并交给模型做 function-calling。 */
    public List<ToolCallback> schemas() {
        return byName.values().stream().map(ToolRegistry::schemaCallback).toList();
    }

    private static ToolCallback schemaCallback(AgentTool tool) {
        return FunctionToolCallback.builder(tool.name(), (Object input, ToolContext ctx) -> "")
                .description(tool.description())
                .inputType(tool.inputType())
                .build();
    }
}