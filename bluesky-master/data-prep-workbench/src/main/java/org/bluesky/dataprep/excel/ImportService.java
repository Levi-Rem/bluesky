package org.bluesky.dataprep.excel;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 导入编排：逐行应用、逐行记错、批次落库。单行失败不影响其余行。 */
@Service
public class ImportService {

    private static final String TEMPLATE_VERSION = "v1";

    private final ExcelService excelService;
    private final EntitySchemas schemas;
    private final ImportMapper importMapper;
    private final RevisionService revisionService;

    public ImportService(ExcelService excelService, EntitySchemas schemas,
                         ImportMapper importMapper, RevisionService revisionService) {
        this.excelService = excelService;
        this.schemas = schemas;
        this.importMapper = importMapper;
        this.revisionService = revisionService;
    }

    /**
     * 逐行导入。刻意不做整批事务：单行业务失败由各行自己的服务事务回滚，
     * 批次/错误记录独立落库，避免外层事务被行级异常标记 rollback-only。
     */
    public Map<String, Object> importFile(String entity, MultipartFile file) {
        EntitySchema<Object> schema = raw(entity);
        List<Map<String, String>> rows = excelService.parse(file, schema);
        String batchId = UUID.randomUUID().toString();
        importMapper.insertBatch(batchId, file.getOriginalFilename(), TEMPLATE_VERSION, entity);

        Map<String, Object> existingByCode = new HashMap<>();
        for (Object item : schema.loadAll()) {
            existingByCode.put(schema.codeOf(item), item);
        }

        int success = 0;
        int failed = 0;
        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + 2; // Excel 第1行是表头
            Map<String, String> fields = rows.get(i);
            try {
                String lookupKey = fields.get("code") == null ? "" : fields.get("code");
                if ("performance".equals(entity)) {
                    lookupKey += "/" + value(fields, "icaoWakeCategory")
                            + "/" + value(fields, "reacatWakeCategory")
                            + "/" + value(fields, "altitudeLayer");
                }
                Object existing = existingByCode.get(lookupKey);
                Object saved = schema.getImportApplier().apply(fields, existing);
                if (saved != null) {
                    existingByCode.put(schema.codeOf(saved), saved);
                }
                success++;
            } catch (ApiException ex) {
                failed++;
                importMapper.insertError(UUID.randomUUID().toString(), batchId,
                        schema.getSheetName(), rowNumber, "", "HTTP_" + ex.getStatus(), ex.getMessage());
            } catch (Exception ex) {
                failed++;
                importMapper.insertError(UUID.randomUUID().toString(), batchId,
                        schema.getSheetName(), rowNumber, "", "INTERNAL",
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
        }

        String status = failed == 0 ? "COMPLETED"
                : success > 0 ? "COMPLETED_WITH_ERRORS" : "FAILED";
        importMapper.completeBatch(batchId, rows.size(), success, failed, status);
        if (success > 0) {
            revisionService.increment();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("dataType", entity);
        result.put("totalRows", rows.size());
        result.put("successRows", success);
        result.put("failedRows", failed);
        result.put("batchStatus", status);
        return result;
    }

    private static String value(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null ? "" : value;
    }

    public List<Map<String, Object>> recentBatches() {
        return importMapper.selectRecentBatches();
    }

    public List<Map<String, Object>> batchErrors(String batchId) {
        return importMapper.selectErrors(batchId);
    }

    @SuppressWarnings("unchecked")
    private EntitySchema<Object> raw(String entity) {
        EntitySchema<?> schema = schemas.schema(entity);
        return (EntitySchema<Object>) schema;
    }
}
