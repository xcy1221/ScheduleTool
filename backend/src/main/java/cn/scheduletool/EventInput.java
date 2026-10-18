package cn.scheduletool;

import jakarta.validation.constraints.*;
import java.time.*;

public record EventInput(
    @NotBlank(message="请填写日程标题") @Size(max=200, message="标题不能超过 200 字") String title,
    @Size(max=4000, message="备注不能超过 4000 字") String description,
    @NotNull(message="请选择开始时间") LocalDateTime start,
    LocalDateTime end,
    boolean allDay,
    String timeZone,
    Repeat recurrence,
    LocalDate repeatUntil,
    @Pattern(regexp="#[0-9a-fA-F]{6}", message="颜色格式不正确") String color,
    @NotNull(message="请提供版本号") @Min(0) Long version
) {
    public enum Repeat { NONE, DAILY, WEEKLY }
    public EventInput normalized() {
        return normalized(false);
    }
    public EventInput normalized(boolean occurrence) {
        String zone = timeZone == null ? "Asia/Shanghai" : timeZone;
        if (!zone.equals("Asia/Shanghai")) throw new ApiError.Failure(400, "初版仅支持 Asia/Shanghai 时区");
        if (start.getYear() < 1900 || start.getYear() > 2199 || (end != null && (end.getYear() < 1900 || end.getYear() > 2200)))
            throw new ApiError.Failure(400, "日期范围为 1900 至 2199 年");
        if (end != null && !end.isAfter(start)) throw new ApiError.Failure(400, "结束时间必须晚于开始时间");
        if (end != null && Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) throw new ApiError.Failure(400, "单个日程不能超过 366 天");
        if (allDay && (!start.toLocalTime().equals(LocalTime.MIDNIGHT) || end == null || !end.toLocalTime().equals(LocalTime.MIDNIGHT)))
            throw new ApiError.Failure(400, "全天日程使用日期，结束日期不包含在内");
        Repeat r = occurrence || recurrence == null ? Repeat.NONE : recurrence;
        LocalDate until = r == Repeat.NONE ? null : repeatUntil;
        if (until != null && (until.isBefore(start.toLocalDate()) || until.getYear() > 2199))
            throw new ApiError.Failure(400, "重复截止日不能早于开始日，且不能超过 2199 年");
        return new EventInput(title.strip(), description == null ? "" : description, start.withNano(0), end == null ? null : end.withNano(0), allDay, zone, r, until, color == null ? "#256f62" : color, version);
    }
}
