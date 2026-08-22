package org.bluesky.dataprep.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bluesky.dataprep.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Apache POI 生成/解析 xlsx。 */@Service
public class ExcelService {

    public static final String ROW_NUMBER_KEY = "__excelRowNumber";

    public byte[] template(EntitySchema<?> schema) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(schema.getSheetName());
            writeHeader(workbook, sheet, schema);
            Row sample = sheet.createRow(1);
            for (int i = 0; i < schema.getSampleRow().size() && i < schema.getColumns().size(); i++) {
                sample.createCell(i).setCellValue(schema.getSampleRow().get(i));
            }
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("模板生成失败", ex);
        }
    }

    public byte[] export(EntitySchema<?> schema) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(schema.getSheetName());
            writeHeader(workbook, sheet, schema);
            int rowIndex = 1;
            for (Object entity : schema.loadAll()) {
                Row row = sheet.createRow(rowIndex++);
                List<String> cells = schema.exportRow(entity);
                for (int i = 0; i < cells.size(); i++) {
                    row.createCell(i).setCellValue(cells.get(i));
                }
            }
            byte[] result = toBytes(workbook);
            workbook.dispose();
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("导出失败", ex);
        }
    }

    /** 解析上传的 xlsx：首行表头按 schema 列定义（中文表头去 * 与括号注释后）匹配回列 key。 */
    public List<Map<String, String>> parse(MultipartFile file, EntitySchema<?> schema) {
        String filename = file.getOriginalFilename();
        if (file.isEmpty()) throw ApiException.badRequest("Excel 文件为空");
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw ApiException.badRequest("仅支持 .xlsx 文件");
        }
        try (InputStream input = file.getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() == 0) throw ApiException.badRequest("Excel 内容为空");
            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            Iterator<Row> rows = sheet.rowIterator();
            if (!rows.hasNext()) {
                throw ApiException.badRequest("Excel 内容为空");
            }
            Row header = rows.next();
            List<String> keys = new ArrayList<>();
            List<Integer> columns = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();
            for (Cell cell : header) {
                String key = matchColumn(normalizeKey(cellText(cell, formatter, evaluator)), schema);
                if (key != null) {
                    if (!seenKeys.add(key)) throw ApiException.badRequest("Excel 表头重复：" + schema.headerOf(key));
                    keys.add(key);
                    columns.add(cell.getColumnIndex());
                }
            }
            List<String> missingHeaders = new ArrayList<>();
            for (ExcelColumn column : schema.getColumns()) {
                if (column.isRequired() && !seenKeys.contains(column.getKey())) missingHeaders.add(column.getHeader());
            }
            if (!missingHeaders.isEmpty()) {
                throw ApiException.badRequest("缺少必填表头：" + String.join("、", missingHeaders));
            }
            List<Map<String, String>> dataRows = new ArrayList<>();
            while (rows.hasNext()) {
                Row row = rows.next();
                Map<String, String> fields = new LinkedHashMap<>();
                boolean empty = true;
                for (int i = 0; i < keys.size(); i++) {
                    Cell cell = row.getCell(columns.get(i));
                    String value = cell == null ? "" : cellText(cell, formatter, evaluator).trim();
                    if (!value.isEmpty()) {
                        empty = false;
                    }
                    fields.put(keys.get(i), value);
                }
                if (!empty) {
                    fields.put(ROW_NUMBER_KEY, String.valueOf(row.getRowNum() + 1));
                    dataRows.add(fields);
                }
            }
            return dataRows;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw ApiException.badRequest("Excel 文件格式不正确或已损坏");
        }
    }

    private String matchColumn(String headerText, EntitySchema<?> schema) {
        for (ExcelColumn column : schema.getColumns()) {
            if (normalizeKey(column.getHeader()).equals(headerText)) {
                return column.getKey();
            }
        }
        return null;
    }

    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        return formatter.formatCellValue(cell, evaluator);
    }

    private String normalizeKey(String headerText) {
        return headerText.replace("*", "").split("\\(")[0].trim();
    }

    private void writeHeader(Workbook workbook, Sheet sheet, EntitySchema<?> schema) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Row header = sheet.createRow(0);
        for (int i = 0; i < schema.getColumns().size(); i++) {
            ExcelColumn column = schema.getColumns().get(i);
            Cell cell = header.createCell(i);
            cell.setCellValue(column.getHeader());
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, column.getWidth() * 256);
        }
    }

    private byte[] toBytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
