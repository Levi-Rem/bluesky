package org.bluesky.dataprep.radar;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RadarMapper {

    String SITE_COLS = "id, code, name, sac, sic, longitude, latitude, altitude_m, maximum_range_nm, "
            + "status, source_type, source_reference, revision, deleted, created_at, created_by, "
            + "updated_at, updated_by ";
    String CHANNEL_COLS = "id, code, name, category, edition, period_ms, transmission_mode, destination_ip, "
            + "destination_port, network_interface, ttl, maximum_datagram_bytes, channel_enabled, "
            + "config_revision, status, source_type, source_reference, revision, deleted, created_at, "
            + "created_by, updated_at, updated_by ";

    // ---- 逻辑雷达站 ----

    @Select("SELECT " + SITE_COLS + "FROM logical_radar_site WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<RadarSiteRow> selectSites(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM logical_radar_site WHERE deleted = FALSE")
    long countSites();

    @Select("SELECT " + SITE_COLS + "FROM logical_radar_site WHERE id = #{id} AND deleted = FALSE")
    RadarSiteRow findSiteById(String id);

    @Select("SELECT COUNT(*) FROM logical_radar_site WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countSiteByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Insert("INSERT INTO logical_radar_site (id, code, name, sac, sic, longitude, latitude, altitude_m, "
            + "maximum_range_nm, status, source_type, source_reference, revision, deleted, created_by, updated_by) "
            + "VALUES (#{id}, #{code}, #{name}, #{sac}, #{sic}, #{longitude}, #{latitude}, #{altitudeM}, "
            + "#{maximumRangeNm}, #{status}, #{sourceType}, #{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insertSite(RadarSiteRow row);

    @Update("UPDATE logical_radar_site SET code = #{code}, name = #{name}, sac = #{sac}, sic = #{sic}, "
            + "longitude = #{longitude}, latitude = #{latitude}, altitude_m = #{altitudeM}, "
            + "maximum_range_nm = #{maximumRangeNm}, status = #{status}, source_reference = #{sourceReference}, "
            + "updated_by = #{updatedBy}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateSite(RadarSiteRow row);

    @Update("UPDATE logical_radar_site SET deleted = TRUE, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markSiteDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE logical_radar_site SET status = #{status}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateSiteStatus(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);

    // ---- ASTERIX 通道 ----

    @Select("SELECT " + CHANNEL_COLS + "FROM asterix_channel WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<AsterixChannelRow> selectChannels(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM asterix_channel WHERE deleted = FALSE")
    long countChannels();

    @Select("SELECT " + CHANNEL_COLS + "FROM asterix_channel WHERE id = #{id} AND deleted = FALSE")
    AsterixChannelRow findChannelById(String id);

    @Select("SELECT COUNT(*) FROM asterix_channel WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countChannelByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Insert("INSERT INTO asterix_channel (id, code, name, category, edition, period_ms, transmission_mode, "
            + "destination_ip, destination_port, network_interface, ttl, maximum_datagram_bytes, "
            + "channel_enabled, config_revision, status, source_type, source_reference, revision, deleted, "
            + "created_by, updated_by) VALUES (#{id}, #{code}, #{name}, #{category}, #{edition}, #{periodMs}, "
            + "#{transmissionMode}, #{destinationIp}, #{destinationPort}, #{networkInterface}, #{ttl}, "
            + "#{maximumDatagramBytes}, #{channelEnabled}, #{configRevision}, #{status}, #{sourceType}, "
            + "#{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insertChannel(AsterixChannelRow row);

    @Update("UPDATE asterix_channel SET code = #{code}, name = #{name}, category = #{category}, "
            + "edition = #{edition}, period_ms = #{periodMs}, transmission_mode = #{transmissionMode}, "
            + "destination_ip = #{destinationIp}, destination_port = #{destinationPort}, "
            + "network_interface = #{networkInterface}, ttl = #{ttl}, "
            + "maximum_datagram_bytes = #{maximumDatagramBytes}, channel_enabled = #{channelEnabled}, "
            + "config_revision = config_revision + 1, status = #{status}, "
            + "source_reference = #{sourceReference}, updated_by = #{updatedBy}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateChannel(AsterixChannelRow row);

    @Update("UPDATE asterix_channel SET deleted = TRUE, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markChannelDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE asterix_channel SET status = #{status}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateChannelStatus(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);

    // ---- 绑定 ----

    @Select("SELECT b.radar_site_id FROM radar_channel_binding b "
            + "JOIN logical_radar_site s ON s.id = b.radar_site_id "
            + "WHERE b.channel_id = #{channelId} AND b.deleted = FALSE AND s.deleted = FALSE AND b.enabled = TRUE "
            + "ORDER BY b.display_order")
    List<String> selectBoundSiteIds(String channelId);

    @Select("SELECT c.code FROM radar_channel_binding b "
            + "JOIN asterix_channel c ON c.id = b.channel_id "
            + "WHERE b.radar_site_id = #{siteId} AND b.deleted = FALSE AND c.deleted = FALSE "
            + "ORDER BY b.display_order")
    List<String> selectBoundChannelCodes(String siteId);

    @Insert("INSERT INTO radar_channel_binding (id, radar_site_id, channel_id, enabled, display_order) "
            + "VALUES (#{id}, #{siteId}, #{channelId}, TRUE, #{displayOrder})")
    int insertBinding(@Param("id") String id, @Param("siteId") String siteId,
                      @Param("channelId") String channelId, @Param("displayOrder") int displayOrder);

    @Update("UPDATE radar_channel_binding SET deleted = TRUE WHERE channel_id = #{channelId}")
    int markBindingsDeleted(String channelId);
}
