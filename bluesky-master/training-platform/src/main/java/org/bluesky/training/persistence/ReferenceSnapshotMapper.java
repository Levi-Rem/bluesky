package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** P03：参考快照持久化（V9 reference_snapshot + exercise_group 固定关系）。 */
public interface ReferenceSnapshotMapper {

    String COLUMNS = "id, version_label, status, schema_version, manifest_json, "
            + "manifest_checksum, source_batch, store_path, published_at, revision";

    @Insert("INSERT INTO reference_snapshot (id, version_label, status, schema_version, "
            + "manifest_json, manifest_checksum, source_batch, store_path, published_at) "
            + "VALUES (#{id}, #{versionLabel}, 'PUBLISHED', #{schemaVersion}, #{manifestJson}, "
            + "#{manifestChecksum}, #{sourceBatch}, #{storePath}, CURRENT_TIMESTAMP(3))")
    int insert(ReferenceSnapshotRow row);

    @Select("SELECT " + COLUMNS + " FROM reference_snapshot WHERE id = #{id}")
    ReferenceSnapshotRow findById(@Param("id") String id);

    @Select("SELECT id, version_label, manifest_checksum, published_at, revision "
            + "FROM reference_snapshot WHERE status = 'PUBLISHED' ORDER BY published_at DESC, id")
    List<ReferenceSnapshotRow> listPublished();

    @Select("SELECT state FROM exercise_group WHERE id = #{groupId}")
    String findGroupState(@Param("groupId") String groupId);

    @Select("SELECT revision FROM exercise_group WHERE id = #{groupId}")
    Long findGroupRevision(@Param("groupId") String groupId);

    @Select("SELECT reference_snapshot_id FROM exercise_group WHERE id = #{groupId}")
    String findGroupSnapshotId(@Param("groupId") String groupId);

    @Update("UPDATE exercise_group SET reference_snapshot_id = #{snapshotId}, "
            + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{groupId} AND revision = #{expectedRevision}")
    int pinSnapshot(@Param("groupId") String groupId,
                    @Param("snapshotId") String snapshotId,
                    @Param("expectedRevision") long expectedRevision);
}
