package org.bluesky.dataprep.weather;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WeatherAreaMapper {
    String COLS = "id, code, name, sig_weather_type AS weather_type, "
            + "boundary AS area, lower_value, lower_reference, upper_value, upper_reference, "
            + "status, source_type, source_reference, revision, created_at, created_by, updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM significant_weather_area WHERE deleted = FALSE "
            + "ORDER BY name, id LIMIT #{size} OFFSET #{offset}")
    List<WeatherAreaRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM significant_weather_area WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM significant_weather_area WHERE id = #{id} AND deleted = FALSE")
    WeatherAreaRow findById(String id);

    @Insert("INSERT INTO significant_weather_area (id, code, name, sig_weather_type, boundary, lower_value, "
            + "lower_reference, upper_value, upper_reference, status, source_type, source_reference, revision, "
            + "deleted, created_by, updated_by) VALUES (#{id}, #{code}, #{name}, #{weatherType}, #{area}, "
            + "#{lowerValue}, #{lowerReference}, #{upperValue}, #{upperReference}, #{status}, #{sourceType}, "
            + "#{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insert(WeatherAreaRow row);

    @Update("UPDATE significant_weather_area SET code = #{code}, name = #{name}, sig_weather_type = #{weatherType}, "
            + "boundary = #{area}, lower_value = #{lowerValue}, lower_reference = #{lowerReference}, "
            + "upper_value = #{upperValue}, upper_reference = #{upperReference}, status = #{status}, source_reference = #{sourceReference}, "
            + "updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP(3), revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(WeatherAreaRow row);

    @Update("UPDATE significant_weather_area SET deleted = TRUE, updated_at = CURRENT_TIMESTAMP(3), revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);
}
