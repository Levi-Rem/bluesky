package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** P01：组序号分配（V8 group_sequence）。 */
public interface GroupSequenceMapper {

    @Insert("INSERT IGNORE INTO group_sequence (exercise_group_id, next_value) VALUES (#{groupId}, 0)")
    int insertIfAbsent(@Param("groupId") String groupId);

    @Select("SELECT next_value FROM group_sequence WHERE exercise_group_id = #{groupId} FOR UPDATE")
    Long lockCurrentValue(@Param("groupId") String groupId);

    @Update("UPDATE group_sequence SET next_value = #{value}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE exercise_group_id = #{groupId}")
    int updateValue(@Param("groupId") String groupId, @Param("value") long value);
}
