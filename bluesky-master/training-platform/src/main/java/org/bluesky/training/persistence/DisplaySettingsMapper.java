package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DisplaySettingsMapper {
    @Update("UPDATE system_parameter SET parameter_value = #{value}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE parameter_key = #{key}")
    int update(@Param("key") String key, @Param("value") String value);
}
