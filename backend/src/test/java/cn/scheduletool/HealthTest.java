package cn.scheduletool;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthTest {
    @Test void databaseFailureIsUnavailableWithoutLeakingDetails() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject("SELECT 1",Integer.class)).thenThrow(new DataAccessResourceFailureException("private-database-details"));
        var response=new Health(db).health();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,response.getStatusCode());
        assertEquals("DOWN",response.getBody().get("status"));
        assertEquals(1,response.getBody().size());
        assertEquals("no-store",response.getHeaders().getCacheControl());
    }
}
