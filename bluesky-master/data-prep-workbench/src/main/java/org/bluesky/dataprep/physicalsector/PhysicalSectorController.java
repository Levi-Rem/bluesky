package org.bluesky.dataprep.physicalsector;

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
@RequestMapping("/api/physical-sector")
public class PhysicalSectorController {
    private final PhysicalSectorService service;

    public PhysicalSectorController(PhysicalSectorService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<PhysicalSectorRow> list(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public PhysicalSectorRow get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    public PhysicalSectorRow create(@Valid @RequestBody PhysicalSectorRow row) { return service.create(row); }

    @PutMapping("/{id}")
    public PhysicalSectorRow update(@PathVariable String id, @Valid @RequestBody PhysicalSectorRow row) {
        return service.update(id, row);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam int revision) { service.delete(id, revision); }
}
