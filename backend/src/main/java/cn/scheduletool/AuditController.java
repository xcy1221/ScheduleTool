package cn.scheduletool;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {
    private final AuditService audit;
    public AuditController(AuditService audit) { this.audit=audit; }
    @GetMapping @Operation(summary="按序号查询成功提交的日程审计，含完整系列和例外快照")
    public List<AuditService.Entry> list(@RequestParam(defaultValue="0") long after,
        @RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String seriesId,
        @RequestParam(required=false) String requestId) { return audit.list(after,limit,seriesId,requestId); }
    @GetMapping("/verify") @Operation(summary="校验哈希链，可与库外保存的序号和哈希比对")
    public AuditService.Verification verify(@RequestParam(required=false) Long expectedSequence,
        @RequestParam(required=false) String expectedHash) { return audit.verify(expectedSequence,expectedHash); }
}
