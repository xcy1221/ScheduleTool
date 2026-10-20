package cn.scheduletool;

import jakarta.validation.constraints.*;

public record TodoInput(
    @NotBlank @Size(max=200) String title,
    @Size(max=4000) String description,
    @Size(max=4000) String expectedEffect,
    @Size(max=4000) String implementationIdea,
    TodoService.Status status, boolean pinned,
    @Min(0) Long version
) {
    TodoInput normalized(boolean update) {
        if(update && version==null) throw new ApiError.Failure(400,"请提供待办版本号");
        if(title==null || title.isBlank() || title.strip().length()>200) throw new ApiError.Failure(400,"待办标题须为 1—200 字");
        return new TodoInput(title.strip(),text(description),text(expectedEffect),text(implementationIdea),
            status==null?TodoService.Status.PENDING:status,pinned,version==null?0:version);
    }
    private static String text(String value) {
        if(value!=null && value.length()>4000) throw new ApiError.Failure(400,"待办文本不能超过 4000 字");
        return value==null?"":value;
    }
}
