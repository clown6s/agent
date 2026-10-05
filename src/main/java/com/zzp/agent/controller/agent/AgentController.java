package com.zzp.agent.controller.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.zzp.agent.common.ApiResponse;
import com.zzp.agent.graph.AgentGraphService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 对话相关接口。
 *
 * <p>统一挂在 {@code /agent/chat} 下，对应 {@link AgentGraphService} 的三类执行方式：
 * <ul>
 *   <li>{@code POST /agent/chat/stream}：流式 SSE，逐节点推送图输出；</li>
 *   <li>{@code POST /agent/chat/interrupt}：中断式（TOOLS 意图在 tools 前暂停等人确认）；</li>
 *   <li>{@code POST /agent/chat/resume}：人工确认后恢复同一 {@code threadId} 会话。</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/chat")
public class AgentController {

    private final AgentGraphService agentGraphService;

    public AgentController(AgentGraphService agentGraphService) {
        this.agentGraphService = agentGraphService;
    }

    /**
     * 流式 SSE 返回：订阅 graph 的 {@code stream(Map)}。
     * <p>每个非终止节点推一条 {@code node} 事件（带节点名），最末 END 节点推一条 {@code result}
     * 事件，内含最终 answer / sources，前端据此渲染完整回答。
     *
     * @param req body 形如 {@code {"query": "上季度销售额top5的客户"}}
     * @return SseEmitter，事件名为 {@code node} / {@code result}
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> req) {
        String query = req.get("query");
        SseEmitter emitter = new SseEmitter(300_000L);
        if (query == null || query.isBlank()) {
            emitter.completeWithError(new IllegalArgumentException("query 不能为空"));
            return emitter;
        }

        Flux<NodeOutput> flux = agentGraphService.streamQuery(query);
        flux.subscribe(
                output -> {
                    try {
                        if (output.isEND()) {
                            OverAllState state = output.state();
                            Map<String, Object> result = new HashMap<>();
                            result.put("answer", state.value(AgentGraphService.ANSWER).orElse(""));
                            result.put("sources", state.value(AgentGraphService.SOURCES).orElse(List.of()));
                            emitter.send(SseEmitter.event().name("result").data(result));
                        } else {
                            Map<String, Object> meta = new HashMap<>();
                            meta.put("node", output.node());
                            meta.put("start", output.isSTART());
                            emitter.send(SseEmitter.event().name("node").data(meta));
                        }
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete);
        return emitter;
    }

    /**
     * 中断式对话起点：TOOLS 意图在 tools 前暂停等人确认；CHAT 直接完成。
     * 调用方自行生成并保存 {@code threadId}，供后续 {@link #resume} 恢复。
     */
    @PostMapping("/interrupt")
    public ApiResponse<Map<String, Object>> interrupt(@RequestBody Map<String, String> req) {
        String threadId = req.get("threadId");
        String query = req.get("query");
        if (threadId == null || threadId.isBlank() || query == null || query.isBlank()) {
            return ApiResponse.fail(400, "threadId 与 query 均必填");
        }
        try {
            agentGraphService.runForApproval(threadId, query);
            Optional<StateSnapshot> pending = agentGraphService.pendingApproval(threadId);
            boolean waiting = pending.isPresent();
            return ApiResponse.ok(Map.of(
                    "threadId", threadId,
                    "waitingApproval", waiting));
        } catch (Exception e) {
            return ApiResponse.fail(500, "中断式执行失败: " + e.getMessage());
        }
    }

    /** 人工确认后恢复同 threadId 的会话，返回最终 answer / sources。 */
    @PostMapping("/resume")
    public ApiResponse<Map<String, Object>> resume(@RequestBody Map<String, String> req) {
        String threadId = req.get("threadId");
        if (threadId == null || threadId.isBlank()) {
            return ApiResponse.fail(400, "threadId 必填");
        }
        try {
            Optional<StateSnapshot> pending = agentGraphService.pendingApproval(threadId);
            if (pending.isEmpty()) {
                return ApiResponse.fail(400, "threadId 没有待恢复的中断点，可能已执行完或线程不存在");
            }
            var finalState = agentGraphService.resumeAgent(threadId);
            if (finalState.isEmpty()) {
                return ApiResponse.fail(500, "恢复执行未返回状态");
            }
            return ApiResponse.ok(Map.of(
                    "answer", finalState.get().value(AgentGraphService.ANSWER).orElse(""),
                    "sources", finalState.get().value(AgentGraphService.SOURCES).orElse(List.of())));
        } catch (Exception e) {
            return ApiResponse.fail(500, "恢复失败: " + e.getMessage());
        }
    }
}