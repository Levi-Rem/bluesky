package org.bluesky.dataprep.excel;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bluesky.dataprep.DataPrepApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExcelApiTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String[] NAV_HEADERS = {
            "编码*", "名称*", "类型*(VOR/DME/NDB/TACAN/WAYPOINT)", "经度*", "纬度*",
            "海拔(米)", "频率(MHz)", "描述"};

    @Test
    void templateDownloadsAndParsesBack() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/templates/nav-point"))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            Row sample = sheet.getRow(1);
            org.assertj.core.api.Assertions.assertThat(header.getCell(0).getStringCellValue())
                    .isEqualTo("编码*");
            org.assertj.core.api.Assertions.assertThat(sample.getCell(0).getStringCellValue())
                    .isEqualTo("PUD");
        }
    }

    @Test
    void importNavPointsCreateUpdateAndRowErrors() throws Exception {
        byte[] file = navPointXlsx(new String[][]{
                {"NX-01", "导入点一", "VOR", "121.0", "31.0", "10", "112.5", "新建"},
                {"AND", "南汇导航台改名", "DME", "121.8", "31.1", "5", "", "按编码更新"},
                {"NX-02", "缺经度", "VOR", "", "31.0", "", "", "该行应失败"},
                {"NX-03", "坏纬度", "VOR", "121.0", "abc", "", "", "该行也应失败"}});

        MvcResult imported = mockMvc.perform(multipart("/api/imports/nav-point")
                        .file(new MockMultipartFile("file", "nav.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.successRows").value(2))
                .andExpect(jsonPath("$.failedRows").value(2))
                .andExpect(jsonPath("$.batchStatus").value("COMPLETED_WITH_ERRORS"))
                .andReturn();
        String batchId = com.jayway.jsonpath.JsonPath.read(
                imported.getResponse().getContentAsString(), "$.batchId");

        mockMvc.perform(get("/api/imports/{id}/errors", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rowNumber").value(4))
                .andExpect(jsonPath("$[0].errorMessage").value(
                        org.hamcrest.Matchers.containsString("经度")));

        mockMvc.perform(get("/api/nav-point").param("size", "100"))
                .andExpect(jsonPath("$.items[?(@.code=='NX-01')].name").value("导入点一"))
                .andExpect(jsonPath("$.items[?(@.code=='AND')].name").value("南汇导航台改名"));
    }

    @Test
    void importAirwayWithSegmentCodes() throws Exception {
        String[] headers = {"编码*", "名称*", "方向*(ONE_WAY/TWO_WAY)", "下限值", "下限基准",
                "上限值", "上限基准", "航段(起点编码-终点编码;…)"};
        byte[] file = xlsx(headers, new String[][]{
                {"AX-01", "导入航路", "TWO_WAY", "6000", "MSL", "12000", "MSL", "PUD-SASAN;SASAN-AND"}});

        mockMvc.perform(multipart("/api/imports/airway")
                        .file(new MockMultipartFile("file", "airway.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successRows").value(1))
                .andExpect(jsonPath("$.batchStatus").value("COMPLETED"));

        mockMvc.perform(get("/api/airway").param("size", "100"))
                .andExpect(jsonPath("$.items[?(@.code=='AX-01')].segments.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.code=='AX-01')].segments[1].endPointCode").value("AND"));
    }

    @Test
    void importAirwayWithBadNavCodeFailsThatRowOnly() throws Exception {
        String[] headers = {"编码*", "名称*", "方向*(ONE_WAY/TWO_WAY)", "下限值", "下限基准",
                "上限值", "上限基准", "航段(起点编码-终点编码;…)"};
        byte[] file = xlsx(headers, new String[][]{
                {"AX-02", "好航路", "ONE_WAY", "", "", "", "", "PUD-SASAN"},
                {"AX-03", "坏引用", "ONE_WAY", "", "", "", "", "PUD-NOPE"}});

        MvcResult imported = mockMvc.perform(multipart("/api/imports/airway")
                        .file(new MockMultipartFile("file", "airway2.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("COMPLETED_WITH_ERRORS"))
                .andReturn();
        String batchId = com.jayway.jsonpath.JsonPath.read(
                imported.getResponse().getContentAsString(), "$.batchId");

        mockMvc.perform(get("/api/imports/{id}/errors", batchId))
                .andExpect(jsonPath("$[0].errorMessage").value(
                        org.hamcrest.Matchers.containsString("导航点不存在")));
    }

    @Test
    void importCat048WithoutBindingFailsRow() throws Exception {
        String[] headers = {"编码*", "名称*", "类别*(CAT021/CAT048/CAT062)", "版本", "发送周期(毫秒)",
                "传输方式(UNICAST/MULTICAST)", "目标IP", "目标端口", "TTL", "最大报文(字节)",
                "通道启用(true/false)", "绑定雷达站(编码;…)"};
        byte[] file = xlsx(headers, new String[][]{
                {"CH-048-X", "未绑定048", "CAT048", "", "", "", "", "", "", "", "", ""}});

        mockMvc.perform(multipart("/api/imports/asterix-channel")
                        .file(new MockMultipartFile("file", "ch48.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedRows").value(1))
                .andExpect(jsonPath("$.batchStatus").value("FAILED"));
    }

    @Test
    void exportContainsSeededRows() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/export/nav-point"))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            org.assertj.core.api.Assertions.assertThat(sheet.getLastRowNum())
                    .isGreaterThanOrEqualTo(3);
            List<String> codes = new java.util.ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                codes.add(sheet.getRow(i).getCell(0).getStringCellValue());
            }
            org.assertj.core.api.Assertions.assertThat(codes).contains("PUD", "AND", "SASAN");
        }
    }

    @Test
    void unsupportedEntityRejected() throws Exception {
        mockMvc.perform(get("/api/templates/unknown-entity"))
                .andExpect(status().isNotFound());
    }

    // ---- 构造 xlsx ----

    private byte[] navPointXlsx(String[][] rows) {
        return xlsx(NAV_HEADERS, rows);
    }

    private byte[] xlsx(String[] headers, String[][] rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("数据");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
