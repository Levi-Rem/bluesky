package org.bluesky.dataprep.nav;

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
@RequestMapping("/api/nav-point")
public class NavPointController {

    private final NavPointService service;

    public NavPointController(NavPointService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<NavPointRow> list(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public NavPointRow get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public NavPointRow create(@Valid @RequestBody NavPointRow row) {
        return service.create(row);
    }

    @PutMapping("/{id}")
    public NavPointRow update(@PathVariable String id, @Valid @RequestBody NavPointRow row) {
        return service.update(id, row);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam int revision) {
        service.delete(id, revision);
    }

    @PostMapping("/{id}/status")
    public NavPointRow changeStatus(@PathVariable String id,
                                    @RequestParam String status,
                                    @RequestParam int revision) {
        return service.changeStatus(id, status, revision);
    }
}
