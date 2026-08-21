package org.bluesky.dataprep.excel;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 实体导入导出模式：一张表对应一组列、样例行、导出行构建器与导入应用器。
 *
 * @param <T> 实体 Row 类型
 */
public class EntitySchema<T> {

    private final String entity;
    private final String sheetName;
    private final List<ExcelColumn> columns;
    private final List<String> sampleRow;
    private final Function<Integer, List<T>> listLoader;
    private final Function<T, String> codeGetter;
    private final Function<T, List<String>> rowExporter;
    private final ImportApplier importApplier;

    /** 导入应用器：fields 为单元格文本；existing 为已存在实体（null 表示新增）。 */
    public interface ImportApplier {
        Object apply(Map<String, String> fields, Object existing);
    }

    public EntitySchema(String entity, String sheetName, List<ExcelColumn> columns,
                        List<String> sampleRow, Function<Integer, List<T>> listLoader,
                        Function<T, String> codeGetter, Function<T, List<String>> rowExporter,
                        ImportApplier importApplier) {
        this.entity = entity;
        this.sheetName = sheetName;
        this.columns = columns;
        this.sampleRow = sampleRow;
        this.listLoader = listLoader;
        this.codeGetter = codeGetter;
        this.rowExporter = rowExporter;
        this.importApplier = importApplier;
    }

    public String getEntity() {
        return entity;
    }

    public String getSheetName() {
        return sheetName;
    }

    public List<ExcelColumn> getColumns() {
        return columns;
    }

    public List<String> getSampleRow() {
        return sampleRow;
    }

    public List<T> loadAll() {
        return listLoader.apply(500);
    }

    public Function<T, String> getCodeGetter() {
        return codeGetter;
    }

    /** 提取实体业务编码（带受控的未检转换）。 */
    @SuppressWarnings("unchecked")
    public String codeOf(Object entity) {
        return ((Function<Object, String>) (Function<?, String>) codeGetter).apply(entity);
    }

    public Function<T, List<String>> getRowExporter() {
        return rowExporter;
    }

    /** 导出行构建（带受控的未检转换）。 */
    @SuppressWarnings("unchecked")
    public List<String> exportRow(Object entity) {
        return ((Function<Object, List<String>>) (Function<?, List<String>>) rowExporter).apply(entity);
    }

    public ImportApplier getImportApplier() {
        return importApplier;
    }

    public String headerOf(String key) {
        for (ExcelColumn column : columns) {
            if (column.getKey().equals(key)) {
                return column.getHeader();
            }
        }
        return key;
    }
}
