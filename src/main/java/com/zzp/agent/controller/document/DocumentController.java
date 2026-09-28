package com.zzp.agent.controller.document;

import com.zzp.agent.common.ApiResponse;
import com.zzp.agent.common.document.DocHitResponse;
import com.zzp.agent.document.intake.DocumentIntakeService;
import com.zzp.agent.document.search.QueryDocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/document")
public class DocumentController {

    private final DocumentIntakeService documentIntakeService;
    private final QueryDocumentService queryDocumentService;

    public DocumentController(DocumentIntakeService documentIntakeService,
                              QueryDocumentService queryDocumentService) {
        this.documentIntakeService = documentIntakeService;
        this.queryDocumentService = queryDocumentService;
    }

    /** 上传一份文档并走完整摄入流程：解析 → 切块 → embedding → 写 ES(全文) → 写 Milvus(向量)。 */
    @PostMapping("/upload")
    public ApiResponse<Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            DocumentIntakeService.IntakeResult result =
                    documentIntakeService.intake(file.getOriginalFilename(), file.getInputStream());
            // 返回一个带 documentId/chunkCount 的可读对象，方便调用方确认结果
            return ApiResponse.ok(new java.util.HashMap<String, Object>() {{
                put("documentId", result.documentId());
                put("chunkCount", result.chunkCount());
            }});
        } catch (Exception e) {
            return ApiResponse.fail(500, "上传失败: " + e.getMessage());
        }
    }

    /**
     * 混合召回检索：对一句话 query 做 ES 全文 + Milvus 向量并行召回、RRF 融合后返回 topK 个文档块。
     *
     * @param query      用户查询（必填）
     * @param topK       返回命中数（默认 5）
     * @param documentId 可选；非空时仅在该文档内召回
     */
    @GetMapping("/search")
    public ApiResponse<List<DocHitResponse>> search(@RequestParam("query") String query,
                                                    @RequestParam(value = "topK", defaultValue = "5") int topK,
                                                    @RequestParam(value = "documentId", required = false) String documentId) {
        try {
            List<DocHitResponse> hits = queryDocumentService.getDocHitResponses(query, topK, documentId);
            return ApiResponse.ok(hits);
        } catch (Exception e) {
            return ApiResponse.fail(500, "检索失败: " + e.getMessage());
        }
    }
}
