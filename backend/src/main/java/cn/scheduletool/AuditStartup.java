package cn.scheduletool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.SQLException;

@Component
public class AuditStartup implements SmartInitializingSingleton {
    private final JdbcTemplate db;
    private final AuditService audit;
    @Value("${app.audit-check-permissions:true}") private boolean checkPermissions;
    public AuditStartup(JdbcTemplate db,AuditService audit) { this.db=db; this.audit=audit; }
    @Override public void afterSingletonsInstantiated() {
        if(checkPermissions) {
            requireDenied("UPDATE audit_log SET payload=payload WHERE 1=0");
            requireDenied("DELETE FROM audit_log WHERE 1=0");
        }
        if(!audit.verify(null,null).valid()) throw new IllegalStateException("审计链校验失败，拒绝启动");
    }
    private void requireDenied(String sql) {
        try { db.update(sql); }
        catch(org.springframework.dao.DataAccessException e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause())
                if(cause instanceof SQLException s && s.getErrorCode()==1142) return;
            throw e;
        }
        throw new IllegalStateException("应用账号具有审计 UPDATE/DELETE 权限；请先改为按表最小授权");
    }
}
