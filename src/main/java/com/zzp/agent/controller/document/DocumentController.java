package com.zzp.agent.controller.document;

import com.zzp.agent.common.ApiResponse;
import com.zzp.agent.document.parser.DocumentParseService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/document")
public class DocumentController {

    private final DocumentParseService documentParseService;

    public DocumentController(DocumentParseService documentParseService) {
        this.documentParseService = documentParseService;
    }

    @PostMapping("/upload")
    public ApiResponse<Object> parse(@RequestParam("file") MultipartFile file) {
        try {
            documentParseService.parse(file.getOriginalFilename(), file.getInputStream());
            return ApiResponse.ok("");
        } catch (Exception e) {
            return ApiResponse.fail(500, "上传失败: " + e.getMessage());
        }
    }
}
