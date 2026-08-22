package org.bluesky.dataprep.radar;

import org.bluesky.dataprep.common.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 雷达与通道页：/api/radar 返回站点+通道混合列表；站点与通道各有独立 CRUD 端点。 */
@RestController
public class RadarController {

    private final RadarService service;

    public RadarController(RadarService service) {
        this.service = service;
    }

    @GetMapping("/api/radar")
    public PageResult<Map<String, Object>> listMixed(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (RadarSiteRow site : service.listAllSites()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", site.getId());
            row.put("kind", "SITE");
            row.put("code", site.getCode());
            row.put("name", site.getName());
            row.put("dataType", "逻辑雷达");
            row.put("sac", site.getSac());
            row.put("sic", site.getSic());
            row.put("networkEndpoint", "—");
            row.put("status", site.getStatus());
            all.add(row);
        }
        for (AsterixChannelRow channel : service.listAllChannels()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", channel.getId());
            row.put("kind", "CHANNEL");
            row.put("code", channel.getCode());
            row.put("name", channel.getName());
            row.put("dataType", channel.getCategory());
            row.put("sac", null);
            row.put("sic", null);
            row.put("networkEndpoint",
                    channel.getDestinationIp() == null ? "—"
                            : channel.getDestinationIp() + ":" + channel.getDestinationPort());
            row.put("status", channel.getStatus());
            all.add(row);
        }
        all.sort((a, b) -> String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code"))));

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = org.bluesky.dataprep.common.Paging.safePage(page, safeSize);
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new PageResult<>(new ArrayList<>(all.subList(from, to)), safePage, safeSize, all.size());
    }

    // ---- 站点 ----

    @GetMapping("/api/radar-site")
    public PageResult<RadarSiteRow> listSites(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.listSites(page, size);
    }

    @GetMapping("/api/radar-site/{id}")
    public RadarSiteRow getSite(@PathVariable String id) {
        return service.getSite(id);
    }

    @PostMapping("/api/radar-site")
    public RadarSiteRow createSite(@Valid @RequestBody RadarSiteRow row) {
        return service.createSite(row);
    }

    @PutMapping("/api/radar-site/{id}")
    public RadarSiteRow updateSite(@PathVariable String id, @Valid @RequestBody RadarSiteRow row) {
        return service.updateSite(id, row);
    }

    @DeleteMapping("/api/radar-site/{id}")
    public void deleteSite(@PathVariable String id, @RequestParam int revision) {
        service.deleteSite(id, revision);
    }

    @PostMapping("/api/radar-site/{id}/status")
    public RadarSiteRow changeSiteStatus(@PathVariable String id,
                                         @RequestParam String status,
                                         @RequestParam int revision) {
        return service.changeSiteStatus(id, status, revision);
    }

    // ---- 通道 ----

    @GetMapping("/api/asterix-channel")
    public PageResult<AsterixChannelRow> listChannels(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return service.listChannels(page, size);
    }

    @GetMapping("/api/asterix-channel/{id}")
    public AsterixChannelRow getChannel(@PathVariable String id) {
        return service.getChannel(id);
    }

    @PostMapping("/api/asterix-channel")
    public AsterixChannelRow createChannel(@Valid @RequestBody AsterixChannelRow row) {
        return service.createChannel(row);
    }

    @PutMapping("/api/asterix-channel/{id}")
    public AsterixChannelRow updateChannel(@PathVariable String id, @Valid @RequestBody AsterixChannelRow row) {
        return service.updateChannel(id, row);
    }

    @DeleteMapping("/api/asterix-channel/{id}")
    public void deleteChannel(@PathVariable String id, @RequestParam int revision) {
        service.deleteChannel(id, revision);
    }

    @PostMapping("/api/asterix-channel/{id}/status")
    public AsterixChannelRow changeChannelStatus(@PathVariable String id,
                                                 @RequestParam String status,
                                                 @RequestParam int revision) {
        return service.changeChannelStatus(id, status, revision);
    }
}
