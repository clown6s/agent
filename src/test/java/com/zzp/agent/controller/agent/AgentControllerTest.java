package com.zzp.agent.controller.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.zzp.agent.common.ApiResponse;
import com.zzp.agent.common.document.DocHitResponse;
import com.zzp.agent.graph.AgentGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentController 端点逻辑单测：直接构造控制器 + mock {@link AgentGraphService}，断言返回的
 * {@link ApiResponse} 契约（code/message/data）。
 *
 * <p>注意：Spring Boot 4 已移除 web slice 注解（{@code @WebMvcTest}），故这里不依赖 MockMvc/Spring
 * 测试切片（那会拉全量上下文、要求外部 ES/Milvus/DeepSeek 可达），而是纯 Mockito 直测控制器方法——
 * 同样把校验、纯聊天取答案、待确认标志、resume 错误映射这些"端点层业务"钉死。真正的 HTTP 往返已由
 * {@code AgentGraphIntegrationTest}（service 层）与下面的行为断言共同覆盖。
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    AgentGraphService agentGraphService;

    AgentController controller;

    private static final DocHitResponse HIT =
            new DocHitResponse("chunk-1", "doc-1", "命中文本", 0.9);

    private static OverAllState answerState(String answer) {
        return new OverAllState(Map.of(
                AgentGraphService.ANSWER, answer,
                AgentGraphService.SOURCES, List.of(HIT)));
    }

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentGraphService);
    }

    @SuppressWarnings("unchecked")
    @Test
    void interrupt_pureChat_returnsAnswerAndSources() {
        when(agentGraphService.runForApproval("t1", "嗨"))
                .thenReturn(Optional.of(answerState("直接回答。")));
        when(agentGraphService.pendingApproval("t1")).thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.interrupt(Map.of("threadId", "t1", "query", "嗨"));

        assertEquals(200, resp.code());
        Map<String, Object> data = resp.data();
        assertEquals("t1", data.get("threadId"));
        assertFalse((Boolean) data.get("waitingApproval"), "纯聊天不应报待确认");
        assertEquals("直接回答。", data.get("answer"), "纯聊天应能把最终答案放回响应");
        assertTrue(((List<?>) data.get("sources")).size() == 1, "纯聊天应带回来源");
    }

    @SuppressWarnings("unchecked")
    @Test
    void interrupt_waitingApproval_hasNoAnswer() {
        when(agentGraphService.pendingApproval("t1"))
                .thenReturn(Optional.of(mock(StateSnapshot.class)));

        ApiResponse<Map<String, Object>> resp = controller.interrupt(Map.of("threadId", "t1", "query", "查一下"));

        assertEquals(200, resp.code());
        Map<String, Object> data = resp.data();
        assertTrue((Boolean) data.get("waitingApproval"), "应报待确认");
        assertNull(data.get("answer"), "中断未完成时不应带最终答案");
    }

    @Test
    void interrupt_missingFields_returns400() {
        ApiResponse<Map<String, Object>> resp = controller.interrupt(Map.of("query", "缺threadId"));
        assertEquals(400, resp.code());
    }

    @Test
    void resume_returnsFinalAnswer() {
        when(agentGraphService.pendingApproval("t1"))
                .thenReturn(Optional.of(mock(StateSnapshot.class)));
        when(agentGraphService.resumeAgent("t1")).thenReturn(Optional.of(answerState("综合作答。")));

        ApiResponse<Map<String, Object>> resp = controller.resume(Map.of("threadId", "t1"));

        assertEquals(200, resp.code());
        assertEquals("综合作答。", resp.data().get("answer"));
    }

    @Test
    void resume_nothingPending_returns400() {
        when(agentGraphService.pendingApproval("t1")).thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.resume(Map.of("threadId", "t1"));

        assertEquals(400, resp.code(), "无可恢复中断点应返回 400");
    }

    @Test
    void resume_missingThreadId_returns400() {
        ApiResponse<Map<String, Object>> resp = controller.resume(Map.of());
        assertEquals(400, resp.code());
    }
}