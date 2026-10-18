package cn.scheduletool;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {
    private final EventService service;
    public EventController(EventService service) { this.service=service; }
    @GetMapping
    @Operation(summary="按时间范围查询展开后的日程", description="Asia/Shanghai 本地时间，左闭右开区间，最长 366 天。返回 seriesId、occurrenceDate 和 version 用于后续修改。")
    public List<EventService.Occurrence> list(@RequestParam LocalDateTime from,@RequestParam LocalDateTime to) { return service.list(from,to); }
    @GetMapping("/{id}") @Operation(summary="查看日程系列的原始定义")
    public EventService.Event get(@PathVariable String id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(summary="新增日程，创建时 version 填 0")
    public EventService.Event create(@Valid @RequestBody EventInput in) { return service.create(in); }
    @PutMapping("/{id}") @Operation(summary="修改日程", description="scope=SERIES 修改整个系列并重置所有单次例外；scope=OCCURRENCE 必填 occurrenceDate，保留原重复规则。version 用于冲突检查。")
    public EventService.Event update(@PathVariable String id,@RequestParam(defaultValue="SERIES") EventService.Scope scope,
        @RequestParam(required=false) LocalDate occurrenceDate,@Valid @RequestBody EventInput in) { return service.update(id,scope,occurrenceDate,in); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="删除一次或整个系列")
    public void delete(@PathVariable String id,@RequestParam(defaultValue="SERIES") EventService.Scope scope,
        @RequestParam(required=false) LocalDate occurrenceDate,@RequestParam long version) { service.delete(id,scope,occurrenceDate,version); }
}
