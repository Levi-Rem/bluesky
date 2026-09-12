package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** P18：屏幕方案与标牌布局持久化（V13 display_profile / aircraft_label_layout）。 */
public interface DisplayProfileMapper {

    String COLUMNS = "id, terminal_id, name, is_default, content_json, revision";

    @Insert("INSERT INTO display_profile (id, terminal_id, name, is_default, content_json) "
            + "SELECT #{id}, #{terminalId}, #{name}, CASE WHEN (SELECT COUNT(*) FROM "
            + "display_profile WHERE terminal_id = #{terminalId}) = 0 THEN 1 ELSE 0 END, "
            + "#{contentJson}")
    int insert(@Param("id") String id, @Param("terminalId") String terminalId,
               @Param("name") String name, @Param("contentJson") String contentJson);

    @Select("SELECT " + COLUMNS + " FROM display_profile WHERE id = #{id}")
    Map<String, Object> findById(@Param("id") String id);

    @Select("SELECT " + COLUMNS + " FROM display_profile "
            + "WHERE terminal_id = #{terminalId} AND name = #{name} LIMIT 1")
    Map<String, Object> findByName(@Param("terminalId") String terminalId,
                                   @Param("name") String name);

    @Select("SELECT COUNT(*) FROM display_profile WHERE terminal_id = #{terminalId}")
    int countByTerminal(@Param("terminalId") String terminalId);

    @Select("SELECT " + COLUMNS + " FROM display_profile WHERE terminal_id = #{terminalId} "
            + "AND is_default = 1 LIMIT 1")
    Map<String, Object> findDefault(@Param("terminalId") String terminalId);

    @Select("SELECT " + COLUMNS + " FROM display_profile WHERE terminal_id = #{terminalId} "
            + "ORDER BY is_default DESC, name")
    List<Map<String, Object>> listByTerminal(@Param("terminalId") String terminalId);

    @Update("UPDATE display_profile SET content_json = #{contentJson}, "
            + "revision = revision + 1 WHERE id = #{id}")
    int updateContent(@Param("id") String id, @Param("contentJson") String contentJson);

    @Update("UPDATE display_profile SET content_json=#{contentJson},revision=revision+1 WHERE id=#{id} AND revision=#{revision}")
    int updateContentAtRevision(@Param("id") String id,@Param("contentJson") String contentJson,@Param("revision") long revision);

    @Delete("DELETE FROM display_profile WHERE id = #{id} AND is_default = 0")
    int deleteById(@Param("id") String id);

    // ---- 标牌布局 ----
    // MERGE INTO ... KEY(...) 为 H2 专有语法，MySQL 8 必报错（评审 F12）。
    // 改为 UPDATE-then-INSERT：同一事务内先更新，未命中再插入，
    // 并发竞态由 uq_label_layout (terminal_id, aircraft_id) 兜底（F13 乐观并发随 P18 接线）。

    @Update("UPDATE aircraft_label_layout SET angle_deg = #{angleDeg}, "
            + "distance_px = #{distancePx}, mode = #{mode}, pinned = #{pinned}, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE terminal_id = #{terminalId} AND aircraft_id = #{aircraftId}")
    int updateLabelLayout(@Param("terminalId") String terminalId,
                          @Param("aircraftId") String aircraftId,
                          @Param("angleDeg") double angleDeg,
                          @Param("distancePx") int distancePx,
                          @Param("mode") String mode, @Param("pinned") boolean pinned);

    @Insert("INSERT INTO aircraft_label_layout (id, terminal_id, aircraft_id, angle_deg, "
            + "distance_px, mode, pinned) VALUES (#{id}, #{terminalId}, #{aircraftId}, "
            + "#{angleDeg}, #{distancePx}, #{mode}, #{pinned})")
    int insertLabelLayout(@Param("id") String id, @Param("terminalId") String terminalId,
                          @Param("aircraftId") String aircraftId,
                          @Param("angleDeg") double angleDeg, @Param("distancePx") int distancePx,
                          @Param("mode") String mode, @Param("pinned") boolean pinned);

    @Delete("DELETE FROM aircraft_label_layout WHERE terminal_id = #{terminalId} "
            + "AND aircraft_id = #{aircraftId}")
    int deleteLabelLayout(@Param("terminalId") String terminalId,
                          @Param("aircraftId") String aircraftId);

    @Select("SELECT terminal_id, aircraft_id, angle_deg, distance_px, mode, pinned "
            + "FROM aircraft_label_layout WHERE terminal_id = #{terminalId}")
    List<Map<String, Object>> labelLayoutsForTerminal(@Param("terminalId") String terminalId);
}
