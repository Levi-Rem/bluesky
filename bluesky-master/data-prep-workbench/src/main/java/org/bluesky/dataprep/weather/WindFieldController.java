package org.bluesky.dataprep.weather;

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
@RequestMapping("/api/wind-field")
public class WindFieldController {

    private final WindFieldService service;

    public WindFieldController(WindFieldService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<WindFieldRow> list(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public WindFieldRow get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public WindFieldRow create(@Valid @RequestBody WindFieldRow row) {
        return service.create(row);
    }

    @PutMapping("/{id}")
    public WindFieldRow update(@PathVariable String id, @Valid @RequestBody WindFieldRow row) {
        return service.update(id, row);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam int revision) {
        service.delete(id, revision);
    }

    @PostMapping("/{id}/status")
    public WindFieldRow changeStatus(@PathVariable String id,
                                     @RequestParam String status,
                                     @RequestParam int revision) {
        return service.changeStatus(id, status, revision);
    }
}
