package org.bluesky.dataprep.excel;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ImportMapper {

    @Insert("INSERT INTO import_batch (id, file_name, template_version, data_type, total_rows, "
            + "success_rows, failed_rows, batch_status) VALUES "
            + "(#{id}, #{fileName}, #{templateVersion}, #{dataType}, 0, 0, 0, 'RUNNING')")
    int insertBatch(@Param("id") String id, @Param("fileName") String fileName,
                    @Param("templateVersion") String templateVersion, @Param("dataType") String dataType);

    @Update("UPDATE import_batch SET total_rows = #{totalRows}, success_rows = #{successRows}, "
            + "failed_rows = #{failedRows}, batch_status = #{batchStatus}, "
            + "completed_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int completeBatch(@Param("id") String id, @Param("totalRows") int totalRows,
                      @Param("successRows") int successRows, @Param("failedRows") int failedRows,
                      @Param("batchStatus") String batchStatus);

    @Insert("INSERT INTO import_row_error (id, batch_id, sheet_name, row_number, field_name, "
            + "error_code, error_message) VALUES (#{id}, #{batchId}, #{sheetName}, #{rowNumber}, "
            + "#{fieldName}, #{errorCode}, #{errorMessage})")
    int insertError(@Param("id") String id, @Param("batchId") String batchId,
                    @Param("sheetName") String sheetName, @Param("rowNumber") int rowNumber,
                    @Param("fieldName") String fieldName, @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    @Select("SELECT id, file_name AS \"fileName\", template_version AS \"templateVersion\", "
            + "data_type AS \"dataType\", total_rows AS \"totalRows\", success_rows AS \"successRows\", "
            + "failed_rows AS \"failedRows\", batch_status AS \"batchStatus\", started_at AS \"startedAt\", "
            + "completed_at AS \"completedAt\" FROM import_batch ORDER BY started_at DESC LIMIT 20")
    List<Map<String, Object>> selectRecentBatches();

    @Select("SELECT id, row_number AS \"rowNumber\", field_name AS \"fieldName\", "
            + "error_code AS \"errorCode\", error_message AS \"errorMessage\" FROM import_row_error "
            + "WHERE batch_id = #{batchId} ORDER BY row_number")
    List<Map<String, Object>> selectErrors(String batchId);
}
