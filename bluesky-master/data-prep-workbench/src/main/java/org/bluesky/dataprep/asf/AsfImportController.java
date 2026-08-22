package org.bluesky.dataprep.asf;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/asf")
public class AsfImportController {

    private final AsfImportService service;
    private final PhysicalSectorAsfImportService physicalSectorService;
    private final AircraftPerformanceAsfImportService aircraftPerformanceService;

    public AsfImportController(AsfImportService service,
                               PhysicalSectorAsfImportService physicalSectorService,
                               AircraftPerformanceAsfImportService aircraftPerformanceService) {
        this.service = service;
        this.physicalSectorService = physicalSectorService;
        this.aircraftPerformanceService = aircraftPerformanceService;
    }

    @PostMapping(value = "/replace-airspace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AsfImportResult replaceAirspace(@RequestParam("characteristicPoints") MultipartFile points,
                                           @RequestParam("routes") MultipartFile routes,
                                           @RequestParam(defaultValue = "false") boolean confirmReplace) {
        if (!confirmReplace) {
            throw org.bluesky.dataprep.common.ApiException.badRequest(
                    "必须确认替换现有导航点、航路与航段数据");
        }
        return service.replace(points, routes);
    }

    @PostMapping(value = "/replace-physical-sectors", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhysicalSectorImportResult replacePhysicalSectors(@RequestParam("fdpVolumes") MultipartFile file,
                                                              @RequestParam(defaultValue = "false") boolean confirmReplace) {
        if (!confirmReplace) {
            throw org.bluesky.dataprep.common.ApiException.badRequest("必须确认替换现有物理扇区数据");
        }
        return physicalSectorService.replace(file);
    }

    @PostMapping(value = "/replace-aircraft-performances", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AircraftPerformanceImportResult replaceAircraftPerformances(
            @RequestParam("aircraftPerformances") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean confirmReplace) {
        if (!confirmReplace) {
            throw org.bluesky.dataprep.common.ApiException.badRequest("必须确认替换现有机型性能数据");
        }
        return aircraftPerformanceService.replace(file);
    }
}
