package org.bluesky.training.display;

import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workstation/display-settings")
public class DisplaySettingsController {
    private final DisplaySettingsService service;

    public DisplaySettingsController(DisplaySettingsService service) {
        this.service = service;
    }

    @PutMapping
    public DisplaySettingsView save(@RequestBody DisplaySettingsRequest request) {
        return service.save(request);
    }
}
