package org.bluesky.dataprep.performance;

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

@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    private final PerformanceService service;

    public PerformanceController(PerformanceService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<PerformanceRow> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public PerformanceRow get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public PerformanceRow create(@Valid @RequestBody PerformanceRow row) {
        return service.create(row);
    }

    @PutMapping("/{id}")
    public PerformanceRow update(@PathVariable String id, @Valid @RequestBody PerformanceRow row) {
        return service.update(id, row);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam int revision) {
        service.delete(id, revision);
    }

    @PostMapping("/{id}/status")
    public PerformanceRow changeStatus(@PathVariable String id,
                                       @RequestParam String status,
                                       @RequestParam int revision) {
        return service.changeStatus(id, status, revision);
    }
}
