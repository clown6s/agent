package com.zzp.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元测试：不启动 Spring、不依赖真实模型，用假意图解析器验证图能构建且条件路由正确。
 */
class AgentGraphTest {

    /** 假解析器：含 "top5"/"查" 判为 TOOLS，否则 CHAT。 */
    private final AgentIntentResolver fake = query -> {
        String l = query.toLowerCase();
        return (l.contains("top5") || l.contains("查")) ? Intent.TOOLS : Intent.CHAT;
    };

    @Test
    void chatIntent_routesToEnd() throws Exception {
        AgentGraphService svc = new AgentGraphService(fake);
        Optional<OverAllState> state = svc.invoke("什么是Agent?");
        assertTrue(state.isPresent(), "图应执行完成并返回 state");
        assertEquals(Intent.CHAT.name(), state.get().value(AgentGraphService.INTENT).orElse(null),
                "CHAT 意图应原样保留在 state（路由到 END，不改写 intent）");
    }

    @Test
    void toolsIntent_runsToolsNode() throws Exception {
        AgentGraphService svc = new AgentGraphService(fake);
        Optional<OverAllState> state = svc.invoke("上季度销售额top5的客户");
        assertTrue(state.isPresent(), "图应执行完成并返回 state");
        // tools 节点运行后会把 intent 改写为占位标记 TOOLS_PLACEHOLDER
        assertEquals("TOOLS_PLACEHOLDER", state.get().value(AgentGraphService.INTENT).orElse(null),
                "TOOLS 意图应进入 tools 节点并改写 intent");
    }
}