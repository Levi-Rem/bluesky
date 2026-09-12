package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/** P02：终端管理持久化（workstation_terminal + V8 扩展列）。 */
public interface TerminalAdminMapper {

    String COLUMNS = "id, name, exercise_group_id, frequency, unit_mode, enabled, revision";

    @Insert("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id, "
            + "frequency, unit_mode, enabled) "
            + "VALUES (#{id}, #{name}, 'PSEUDO_PILOT', #{exerciseGroupId}, #{frequency}, #{unitMode}, 1)")
    int insert(@Param("id") String id,
               @Param("name") String name,
               @Param("exerciseGroupId") String exerciseGroupId,
               @Param("frequency") BigDecimal frequency,
               @Param("unitMode") String unitMode);

    @Select("SELECT COUNT(*) FROM exercise_group WHERE id = #{groupId}")
    int countGroupById(@Param("groupId") String groupId);

    @Select("SELECT COUNT(*) FROM workstation_terminal "
            + "WHERE exercise_group_id = #{groupId} AND frequency = #{frequency}")
    int countByGroupAndFrequency(@Param("groupId") String groupId,
                                 @Param("frequency") BigDecimal frequency);

    @Select("SELECT " + COLUMNS + " FROM workstation_terminal "
            + "WHERE exercise_group_id = #{groupId} AND frequency = #{frequency} LIMIT 1")
    TerminalAdminRow findByGroupAndFrequency(@Param("groupId") String groupId,
                                             @Param("frequency") BigDecimal frequency);

    @Select("SELECT " + COLUMNS + " FROM workstation_terminal WHERE id = #{terminalId}")
    TerminalAdminRow findById(@Param("terminalId") String terminalId);

    @Select("SELECT " + COLUMNS + " FROM workstation_terminal "
            + "WHERE exercise_group_id = #{groupId} ORDER BY frequency, id")
    List<TerminalAdminRow> listByGroup(@Param("groupId") String groupId);

    @Update("<script>"
            + "UPDATE workstation_terminal SET revision = revision + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "<if test='name != null'>, name = #{name}</if> "
            + "<if test='unitMode != null'>, unit_mode = #{unitMode}</if> "
            + "<if test='enabled != null'>, enabled = #{enabled}</if> "
            + "WHERE id = #{terminalId} AND revision = #{expectedRevision}"
            + "</script>")
    int update(@Param("terminalId") String terminalId,
               @Param("expectedRevision") long expectedRevision,
               @Param("name") String name,
               @Param("unitMode") String unitMode,
               @Param("enabled") Boolean enabled);
}
