package org.bluesky.training.workstation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workstation")
public class WorkstationController {
    private final WorkstationService workstationService;

    public WorkstationController(WorkstationService workstationService) {
        this.workstationService = workstationService;
    }

    @GetMapping("/bootstrap")
    public WorkstationBootstrapResponse bootstrap() {
        return workstationService.bootstrap();
    }
}
