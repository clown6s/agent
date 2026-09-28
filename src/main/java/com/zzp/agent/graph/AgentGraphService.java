package com.zzp.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 用 Spring AI Alibaba Graph 的 {@link StateGraph} 搭的 Agent 工作流骨架。
 *
 * <p>图结构（可扩展）：
 * <pre>
 *   START → agent ──条件路由──→ TOOLS → END
 *              │
 *              └────────────→ END   (CHAT)
 * </pre>
 * <ul>
 *   <li><b>agent 节点</b>：用 LLM 判断提问意图（{@link AgentIntentResolver}），写入 state 的 {@code intent}；</li>
 *   <li><b>tools 节点</b>：意图为 TOOLS 时进入，目前是占位，第三步接实际工具（如 search_docs / n2sql）；</li>
 *   <li><b>条件路由</b>：{@code intent==CHAT → END}，{@code intent==TOOLS → tools}。</li>
 * </ul>
 *
 * <p>state 键统一走常量 {@link #QUERY} / {@link #INTENT}，避免魔法字符串。
 */
@Service
public class AgentGraphService {

    /** state 键：用户提问原文本。 */
    public static final String QUERY = "query";
    /** state 键：LLM 判断出的意图名（{@link Intent} 的 name）。 */
    public static final String INTENT = "intent";

    private final AgentIntentResolver intentResolver;
    private final CompiledGraph compiledGraph;

    public AgentGraphService(AgentIntentResolver intentResolver) throws GraphStateException {
        this.intentResolver = intentResolver;
        this.compiledGraph = buildGraph();
    }

    private CompiledGraph buildGraph() throws GraphStateException {
        // agent 节点：读 query → LLM 判断意图 → 写回 state
        AsyncNodeAction agentNode = state -> {
            String query = String.valueOf(state.value(QUERY).orElse(""));
            Intent intent = intentResolver.resolve(query);
            return CompletableFuture.completedFuture(Map.of(INTENT, intent.name()));
        };

        // tools 节点：占位。第三步在这里分发到具体工具（search_docs / n2sql 等）。
        AsyncNodeAction toolsNode = state ->
                CompletableFuture.completedFuture(Map.of(INTENT, "TOOLS_PLACEHOLDER"));

        // 条件路由：根据 intent 值决定下一步（返回值是映射表的 key）
        AsyncEdgeAction route = state -> CompletableFuture.completedFuture(
                String.valueOf(state.value(INTENT).orElse(Intent.CHAT.name())));

        return new StateGraph()
                .addNode("agent", agentNode)
                .addNode("tools", toolsNode)
                .addEdge(StateGraph.START, "agent")
                .addConditionalEdges("agent", route, Map.of(
                        Intent.CHAT.name(), StateGraph.END,
                        Intent.TOOLS.name(), "tools"))
                .addEdge("tools", StateGraph.END)
                .compile();
    }

    /**
     * 执行一次对话：入参只放 query，走完整图。返回最终 state。
     *
     * @param query 用户提问
     * @return 图执行完的最终状态（可在此读 intent、后续读答案）
     */
    public Optional<OverAllState> invoke(String query) {
        return compiledGraph.invoke(Map.of(QUERY, query));
    }

    /** 暴露编译后的图，供流式/打断/恢复（第四步）拿到底层 {@link CompiledGraph}。 */
    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }
}