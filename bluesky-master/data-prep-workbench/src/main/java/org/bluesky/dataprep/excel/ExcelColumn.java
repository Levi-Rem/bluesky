package org.bluesky.dataprep.excel;

/** Excel 列定义：字段 key + 表头 + 列宽。 */
public class ExcelColumn {

    private final String key;
    private final String header;
    private final int width;

    public ExcelColumn(String key, String header, int width) {
        this.key = key;
        this.header = header;
        this.width = width;
    }

    public static ExcelColumn col(String key, String header) {
        return new ExcelColumn(key, header, Math.max(12, header.length() * 2 + 4));
    }

    public String getKey() {
        return key;
    }

    public String getHeader() {
        return header;
    }

    public int getWidth() {
        return width;
    }

    public boolean isRequired() {
        return header.contains("*");
    }
}
