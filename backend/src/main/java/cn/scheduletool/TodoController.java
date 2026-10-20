package cn.scheduletool;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController @RequestMapping("/api/v1/todos")
public class TodoController {
    private final TodoService service;
    public TodoController(TodoService service) { this.service=service; }
    @GetMapping @Operation(summary="搜索待办及有效排期",description="默认排除已完成。unscheduled 表示没有后续或进行中的有效安排，含已取消、已结束的安排。")
    public List<TodoService.Detail> list(@RequestParam(required=false) String q,@RequestParam(required=false) TodoService.Status status,
        @RequestParam(defaultValue="false") boolean includeCompleted,@RequestParam(defaultValue="false") boolean unscheduled) {
        return service.list(q,status,includeCompleted,unscheduled);
    }
    @GetMapping("/{id}") @Operation(summary="待办详情、所有关联系列与下次有效安排")
    public TodoService.Detail get(@PathVariable String id) { return service.detail(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(summary="创建待办，仅标题必填")
    public TodoService.Todo create(@Valid @RequestBody TodoInput input) { return service.create(input); }
    @PutMapping("/{id}") @Operation(summary="编辑、置顶、完成或重开待办，须提供最新 version")
    public TodoService.Todo update(@PathVariable String id,@Valid @RequestBody TodoInput input) { return service.update(id,input); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="删除待办",description="须明确 mode=KEEP_EVENTS 保留并解除所有系列关联，或 DELETE_EVENTS 删除所有关联系列及其单次例外。version 也保护关联集合；先查询并确认影响范围。")
    public void delete(@PathVariable String id,@RequestParam long version,@RequestParam TodoService.DeleteMode mode) { service.delete(id,version,mode); }
}
