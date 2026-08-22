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
import java.util.Locale;

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

        int[] progress = new int[2];
        try {
            return processBatch(entity, schema, rows, batchId, progress);
        } catch (RuntimeException fatal) {
            try {
                importMapper.completeBatch(batchId, rows.size(), progress[0], progress[1], "ABORTED");
            } catch (RuntimeException ignored) {
                // 数据库不可用时无法更新批次；保留原始异常给调用方。
            }
            throw fatal;
        }
    }

    private Map<String, Object> processBatch(String entity, EntitySchema<Object> schema,
                                             List<Map<String, String>> rows, String batchId,
                                             int[] progress) {

        Map<String, Object> existingByCode = new HashMap<>();
        for (Object item : schema.loadAll()) {
            existingByCode.put(normalizeLookup(entity, schema.codeOf(item)), item);
        }

        int success = 0;
        int failed = 0;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> fields = rows.get(i);
            int rowNumber = parseRowNumber(fields, i + 2);
            try {
                String lookupKey = value(fields, "code");
                if ("performance".equals(entity)) {
                    lookupKey += "/" + value(fields, "icaoWakeCategory")
                            + "/" + value(fields, "reacatWakeCategory")
                            + "/" + value(fields, "altitudeLayer");
                } else if ("weather".equals(entity)) {
                    lookupKey = value(fields, "name") + "/" + value(fields, "weatherType");
                }
                Object existing = existingByCode.get(normalizeLookup(entity, lookupKey));
                Object saved = schema.getImportApplier().apply(fields, existing);
                if (saved != null) {
                    existingByCode.put(normalizeLookup(entity, schema.codeOf(saved)), saved);
                }
                success++;
                progress[0] = success;
            } catch (ApiException ex) {
                failed++;
                progress[1] = failed;
                recordError(batchId, schema.getSheetName(), rowNumber, "HTTP_" + ex.getStatus(), ex.getMessage());
            } catch (Exception ex) {
                failed++;
                progress[1] = failed;
                recordError(batchId, schema.getSheetName(), rowNumber, "INTERNAL",
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

    private void recordError(String batchId, String sheetName, int rowNumber, String code, String message) {
        String safe = message == null ? "未知错误" : message;
        if (safe.length() > 512) safe = safe.substring(0, 509) + "...";
        importMapper.insertError(UUID.randomUUID().toString(), batchId, sheetName, rowNumber, "", code, safe);
    }

    private static int parseRowNumber(Map<String, String> fields, int fallback) {
        try {
            return Integer.parseInt(value(fields, ExcelService.ROW_NUMBER_KEY));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String normalizeLookup(String entity, String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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
