package com.zzp.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.zzp.agent.common.document.DocHitResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元测试：不启动 Spring、不依赖真实模型/ES/Milvus，用假解析器 + 假工具验证图能构建且条件路由正确。
 */
class AgentGraphTest {

    /** 假解析器：含 "top5"/"查" 判为 TOOLS，否则 CHAT。 */
    private final AgentIntentResolver fakeResolver = query -> {
        String l = query.toLowerCase();
        return (l.contains("top5") || l.contains("查")) ? Intent.TOOLS : Intent.CHAT;
    };

    /** 假工具：把收到的 history 拼进回答，方便断言多轮上下文有没有传进来。 */
    private final AgentTool fakeTool = new AgentTool() {
        @Override
        public AgentToolResult execute(String query) {
            return execute(query, null);
        }

        @Override
        public AgentToolResult execute(String query, String history) {
            String answer = "模拟回答" + (history == null ? "" : "，带历史:" + history);
            return new AgentToolResult(answer, List.of(new DocHitResponse(
                    "chunk-1", "doc-1", "这是命中文本", 0.9)));
        }
    };

    @Test
    void chatIntent_routesToEnd() throws Exception {
        AgentGraphService svc = new AgentGraphService(fakeResolver, fakeTool);
        Optional<OverAllState> state = svc.invoke("什么是Agent?");
        assertTrue(state.isPresent(), "图应执行完成并返回 state");
        assertEquals(Intent.CHAT.name(), state.get().value(AgentGraphService.INTENT).orElse(null),
                "CHAT 意图应原样保留在 state（路由到 END，不进 tools）");
    }

    @Test
    void toolsIntent_runsToolAndWritesAnswerAndSources() throws Exception {
        AgentGraphService svc = new AgentGraphService(fakeResolver, fakeTool);
        Optional<OverAllState> state = svc.invoke("上季度销售额top5的客户");
        assertTrue(state.isPresent(), "图应执行完成并返回 state");
        assertEquals("模拟回答", state.get().value(AgentGraphService.ANSWER).orElse(null),
                "tools 节点应把工具回答写入 state.answer");
        Object sources = state.get().value(AgentGraphService.SOURCES).orElse(null);
        assertTrue(sources instanceof List<?>, "tools 节点应把来源写入 state.sources");
        assertIterableEquals(List.of("chunk-1"), ((List<?>) sources).stream()
                        .map(s -> ((DocHitResponse) s).chunkId()).toList(),
                "sources 应包含工具返回的命中块");
    }

    @Test
    void secondTurn_injectsPreviousAnswerAsHistory() throws Exception {
        AgentGraphService svc = new AgentGraphService(fakeResolver, fakeTool);

        // 第一轮：TOOLS（含 "top5"），产生 answer + sources
        Optional<OverAllState> first = svc.invoke("上季度销售额top5的客户");
        assertTrue(first.isPresent(), "第一轮应执行完成");
        assertEquals("模拟回答", first.get().value(AgentGraphService.ANSWER).orElse(null),
                "第一轮应写入 answer");

        // 第二轮：仍是要查库的追问（含 "查"+"top5" → TOOLS），传第一轮 state，
        // 工具应收到格式化的历史上下文
        Optional<OverAllState> second = svc.invoke("那查下季度top5呢", first);
        assertTrue(second.isPresent(), "第二轮应执行完成");
        String answer = String.valueOf(second.get().value(AgentGraphService.ANSWER).orElse(""));
        assertTrue(answer.contains("带历史") && answer.contains("上一轮回答"),
                "第二轮工具应收到包含上轮回答的历史上下文，实际=" + answer);
    }

    @Test
    void agentNode_passesHistoryToIntentResolver() throws Exception {
        // 记录 agent 节点收到的历史——验证意图判断确实拿到了上一轮上下文。
        // 第一轮(history==null)判 TOOLS 好让 answer 落盘；第二轮收到历史后据实记录。
        final String[] receivedHistory = {null};
        AgentIntentResolver historyCapturing = new AgentIntentResolver() {
            @Override
            public Intent resolve(String query) {
                return historyTurn(query, null);
            }

            @Override
            public Intent resolve(String query, String history) {
                return historyTurn(query, history);
            }

            private Intent historyTurn(String query, String history) {
                receivedHistory[0] = history;
                return history == null ? Intent.TOOLS : Intent.CHAT;
            }
        };
        AgentGraphService svc = new AgentGraphService(historyCapturing, fakeTool);

        Optional<OverAllState> first = svc.invoke("上季度销售额top5的客户");   // TOOLS → answer 落盘
        Optional<OverAllState> second = svc.invoke("那下季度呢", first);        // 带历史走 agent 节点

        assertTrue(second.isPresent());
        assertTrue(receivedHistory[0] != null && receivedHistory[0].contains("上一轮回答"),
                "agent 节点应把上一轮上下文传给意图判断，实际=" + receivedHistory[0]);
    }

    @Test
    void interruptThenResume_flowsThroughTools() throws Exception {
        // 假解析器：永远判 TOOLS → 会命中 interruptBefore("tools")
        AgentIntentResolver toolsResolver = q -> Intent.TOOLS;
        AgentGraphService svc = new AgentGraphService(toolsResolver, fakeTool);
        String threadId = "t-interrupt-1";

        svc.runForApproval(threadId, "查一下top5");
        Optional<StateSnapshot> pending = svc.pendingApproval(threadId);
        assertTrue(pending.isPresent(), "TOOLS 意图应在 tools 节点前中断，留下 checkpoint 快照");

        Optional<OverAllState> done = svc.resumeAgent(threadId);
        assertTrue(done.isPresent(), "恢复后应跑完 tools → END");
        assertEquals("模拟回答", done.get().value(AgentGraphService.ANSWER).orElse(null),
                "恢复后 tools 应写入最终 answer");
    }
}