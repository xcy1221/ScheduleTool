package cn.scheduletool;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class WebSecurity {
    // REST continues to use Auth's session and CSRF checks. OAuth has its own scoped chains.
    @Bean @Order(100)
    SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a->a.anyRequest().permitAll())
            .csrf(c->c.disable()).requestCache(c->c.disable())
            .securityContext(c->c.disable()).sessionManagement(c->c.disable()).build();
    }
}
