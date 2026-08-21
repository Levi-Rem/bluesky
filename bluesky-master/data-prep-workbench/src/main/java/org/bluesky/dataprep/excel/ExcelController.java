package org.bluesky.dataprep.excel;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 模板下载 / 数据导出 / Excel 导入 三个端点。 */
@RestController
public class ExcelController {

    private final EntitySchemas schemas;
    private final ExcelService excelService;
    private final ImportService importService;

    public ExcelController(EntitySchemas schemas, ExcelService excelService, ImportService importService) {
        this.schemas = schemas;
        this.excelService = excelService;
        this.importService = importService;
    }

    @GetMapping("/api/templates/{entity}")
    public HttpEntity<byte[]> template(@PathVariable String entity) {
        byte[] body = excelService.template(schemas.schema(entity));
        return xlsx(entity + "-template.xlsx", body);
    }

    @GetMapping("/api/export/{entity}")
    public HttpEntity<byte[]> export(@PathVariable String entity) {
        byte[] body = excelService.export(schemas.schema(entity));
        return xlsx(entity + "-export.xlsx", body);
    }

    @PostMapping("/api/imports/{entity}")
    public Map<String, Object> importFile(@PathVariable String entity,
                                          @RequestParam("file") MultipartFile file) {
        return importService.importFile(entity, file);
    }

    @GetMapping("/api/imports")
    public List<Map<String, Object>> batches() {
        return importService.recentBatches();
    }

    @GetMapping("/api/imports/{batchId}/errors")
    public List<Map<String, Object>> errors(@PathVariable String batchId) {
        return importService.batchErrors(batchId);
    }

    @GetMapping("/api/templates")
    public List<String> entities() {
        return schemas.entities();
    }

    private HttpEntity<byte[]> xlsx(String fileName, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build();
        headers.setContentDisposition(disposition);
        headers.setContentLength(body.length);
        return new HttpEntity<>(body, headers);
    }
}
