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

/** 区域性气象数据：名称、类型、区域和垂直范围。 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {
    private final WeatherAreaService service;

    public WeatherController(WeatherAreaService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<WeatherAreaRow> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public WeatherAreaRow get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public WeatherAreaRow create(@Valid @RequestBody WeatherAreaRow row) {
        return service.create(row);
    }

    @PutMapping("/{id}")
    public WeatherAreaRow update(@PathVariable String id, @Valid @RequestBody WeatherAreaRow row) {
        return service.update(id, row);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam int revision) {
        service.delete(id, revision);
    }
}
