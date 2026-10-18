package cn.scheduletool;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
    @Bean OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("ScheduleTool 日程 API").version("v1")
            .description("单用户日程服务。所有业务时间使用 Asia/Shanghai 的 ISO 本地日期时间；全天 end 为排他结束日。请先 POST /api/v1/auth/login，再使用 Bearer token。"))
            .components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
