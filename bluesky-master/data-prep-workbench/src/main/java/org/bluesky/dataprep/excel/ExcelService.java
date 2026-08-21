package org.bluesky.dataprep.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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

/** Apache POI 生成/解析 xlsx。 */@Service
public class ExcelService {

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
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
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
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("导出失败", ex);
        }
    }

    /** 解析上传的 xlsx：首行表头按 schema 列定义（中文表头去 * 与括号注释后）匹配回列 key。 */
    public List<Map<String, String>> parse(MultipartFile file, EntitySchema<?> schema) {
        try (InputStream input = file.getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.rowIterator();
            if (!rows.hasNext()) {
                throw ApiException.badRequest("Excel 内容为空");
            }
            Row header = rows.next();
            List<String> keys = new ArrayList<>();
            List<Integer> columns = new ArrayList<>();
            for (Cell cell : header) {
                String key = matchColumn(normalizeKey(String.valueOf(cellValue(cell))), schema);
                if (key != null) {
                    keys.add(key);
                    columns.add(cell.getColumnIndex());
                }
            }
            if (keys.isEmpty()) {
                throw ApiException.badRequest("表头与模板不匹配，请先下载模板");
            }
            List<Map<String, String>> dataRows = new ArrayList<>();
            while (rows.hasNext()) {
                Row row = rows.next();
                Map<String, String> fields = new LinkedHashMap<>();
                boolean empty = true;
                for (int i = 0; i < keys.size(); i++) {
                    Cell cell = row.getCell(columns.get(i));
                    String value = cell == null ? "" : String.valueOf(cellValue(cell)).trim();
                    if (!value.isEmpty()) {
                        empty = false;
                    }
                    fields.put(keys.get(i), value);
                }
                if (!empty) {
                    dataRows.add(fields);
                }
            }
            return dataRows;
        } catch (IOException ex) {
            throw ApiException.badRequest("Excel 文件读取失败：" + ex.getMessage());
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

    private Object cellValue(Cell cell) {
        switch (cell.getCellType()) {
            case NUMERIC:
                double value = cell.getNumericCellValue();
                if (value == Math.floor(value) && !Double.isInfinite(value)
                        && Math.abs(value) < 1e15) {
                    return (long) value;
                }
                return value;
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case FORMULA:
                return cell.getCellFormula();
            default:
                return cell.getStringCellValue();
        }
    }

    private String normalizeKey(String headerText) {
        return headerText.replace("*", "").split("\\(")[0].trim();
    }

    private void writeHeader(XSSFWorkbook workbook, Sheet sheet, EntitySchema<?> schema) {
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

    private byte[] toBytes(XSSFWorkbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
