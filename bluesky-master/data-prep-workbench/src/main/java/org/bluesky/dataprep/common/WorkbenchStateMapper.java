package org.bluesky.dataprep.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkbenchStateMapper {

    @Select("SELECT revision FROM workbench_state WHERE id = 1")
    Long selectRevision();

    @Update("UPDATE workbench_state SET revision = revision + 1 WHERE id = 1")
    int incrementRevision();
}
