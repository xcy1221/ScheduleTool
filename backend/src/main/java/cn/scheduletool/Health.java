package cn.scheduletool;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Backend readiness, independent of the optional web bundle and browser sessions. */
@RestController
class Health {
    private final JdbcTemplate db;
    Health(JdbcTemplate db) { this.db=db; }

    @GetMapping("/health")
    ResponseEntity<Map<String,String>> health() {
        boolean ready;
        try { ready=Integer.valueOf(1).equals(db.queryForObject("SELECT 1",Integer.class)); }
        catch(DataAccessException error) { ready=false; }
        return ResponseEntity.status(ready?HttpStatus.OK:HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .body(Map.of("status",ready?"UP":"DOWN"));
    }
}
