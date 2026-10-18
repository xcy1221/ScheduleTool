package cn.scheduletool;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.*;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.*;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.mcp.enabled",havingValue="true")
@EnableConfigurationProperties(McpSettings.class)
class McpOAuth {
    static final String READ="schedule:read",WRITE="schedule:write";
    @Bean RegisteredClientRepository mcpClients(McpSettings p) {
        p.validate();
        var builder=RegisteredClient.withId("scheduletool-chatgpt").clientId(p.clientId())
            .clientSecret("{bcrypt}"+p.clientSecretHash()).clientName("ScheduleTool ChatGPT")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .scope(READ).scope(WRITE)
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder().authorizationCodeTimeToLive(Duration.ofMinutes(2))
                .accessTokenTimeToLive(Duration.ofMinutes(15)).refreshTokenTimeToLive(Duration.ofDays(7))
                .reuseRefreshTokens(false).build());
        p.redirectUris().forEach(builder::redirectUri);
        return new InMemoryRegisteredClientRepository(builder.build());
    }
    @Bean OAuth2AuthorizationService mcpAuthorizations(JdbcTemplate db,RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(db,clients);
    }
    @Bean OAuth2AuthorizationConsentService mcpConsents(JdbcTemplate db,RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(db,clients);
    }
    @Bean AuthorizationServerSettings authorizationServerSettings(McpSettings p) {
        return AuthorizationServerSettings.builder().issuer(p.issuer()).build();
    }
    @Bean RSAKey mcpKey(McpSettings p) throws Exception {
        var key=RSAKey.parse(Files.readString(p.signingKeyFile()));
        if(!key.isPrivate() || key.size()<2048) throw new IllegalStateException("MCP 需要持久化的 RSA 私钥（至少 2048 位）");
        return key;
    }
    @Bean JWKSource<SecurityContext> jwkSource(RSAKey key) {
        var keys=new JWKSet(key); return (selector,context)->selector.select(keys);
    }
    @Bean OAuth2TokenCustomizer<JwtEncodingContext> mcpClaims(McpSettings p) {
        return context->{ if(OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            context.getClaims().audience(new ArrayList<>(List.of(p.resource())));
            context.getClaims().claim("client_id",context.getRegisteredClient().getClientId());
        }};
    }
    @Bean JwtDecoder mcpJwtDecoder(RSAKey key,McpSettings p,OAuth2AuthorizationService authorizations) throws Exception {
        var decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        OAuth2TokenValidator<Jwt> audience=jwt->jwt.getAudience().equals(List.of(p.resource()))?
            OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        OAuth2TokenValidator<Jwt> active=jwt->{
            var stored=authorizations.findByToken(jwt.getTokenValue(),OAuth2TokenType.ACCESS_TOKEN);
            return stored!=null && stored.getAccessToken()!=null && stored.getAccessToken().isActive()?
                OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(p.issuer()),audience,active));
        return decoder;
    }
    @Bean @Order(1) SecurityFilterChain oauthSecurity(HttpSecurity http,McpSettings p) throws Exception {
        var oauth=OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(oauth.getEndpointsMatcher()).with(oauth,server->server
            .authorizationEndpoint(endpoint->endpoint.consentPage("/mcp-consent").authenticationProviders(providers->providers.forEach(provider->{
                if(provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider code) {
                    code.setAuthenticationValidator(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR
                        .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR).andThen(context->{
                            OAuth2AuthorizationCodeRequestAuthenticationToken request=context.getAuthentication();
                            if(!p.resource().equals(request.getAdditionalParameters().get("resource")) ||
                                !"S256".equals(request.getAdditionalParameters().get("code_challenge_method")))
                                throw new OAuth2AuthorizationCodeRequestAuthenticationException(new OAuth2Error("invalid_request"),request);
                        }));
                }
            })))
            .tokenEndpoint(endpoint->endpoint.authenticationProviders(providers->providers.replaceAll(provider->new AuthenticationProvider() {
                public boolean supports(Class<?> type) { return provider.supports(type); }
                public Authentication authenticate(Authentication authentication) {
                    if(authentication instanceof OAuth2AuthorizationGrantAuthenticationToken grant) {
                        Object resource=grant.getAdditionalParameters().get("resource");
                        if(resource!=null && !p.resource().equals(resource)) throw new OAuth2AuthenticationException(new OAuth2Error("invalid_target"));
                    }
                    return provider.authenticate(authentication);
                }
            }))));
        return http.authorizeHttpRequests(a->a.anyRequest().authenticated())
            .exceptionHandling(e->e.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/mcp-login"),new MediaTypeRequestMatcher(MediaType.TEXT_HTML))).build();
    }
    @Bean @Order(2) SecurityFilterChain mcpSecurity(HttpSecurity http,McpSettings p,JwtDecoder decoder) throws Exception {
        return http.securityMatcher("/mcp").authorizeHttpRequests(a->a.anyRequest().authenticated())
            .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(decoder)).authenticationEntryPoint((req,res,error)->{
                res.setHeader("WWW-Authenticate","Bearer resource_metadata=\""+p.issuer()+"/.well-known/oauth-protected-resource/mcp\"");
                res.sendError(401);
            })).csrf(c->c.disable()).requestCache(c->c.disable())
            .sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)).build();
    }
    @Bean @Order(3) SecurityFilterChain mcpLoginSecurity(HttpSecurity http,Auth auth) throws Exception {
        return http.securityMatcher("/mcp-login","/mcp-logout","/mcp-consent")
            .authorizeHttpRequests(a->a.requestMatchers("/mcp-consent").authenticated().anyRequest().permitAll())
            .authenticationProvider(new AuthenticationProvider() {
                public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
                public Authentication authenticate(Authentication authentication) {
                    var details=(WebAuthenticationDetails)authentication.getDetails();
                    try { auth.checkCredentials(authentication.getName(),String.valueOf(authentication.getCredentials()),details.getRemoteAddress()); }
                    catch(ApiError.Failure failure) { throw new BadCredentialsException("登录失败或尝试次数过多"); }
                    var user=User.withUsername(authentication.getName()).password("").roles("USER").build();
                    return UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities());
                }
            }).formLogin(form->form.loginPage("/mcp-login").loginProcessingUrl("/mcp-login"))
            .logout(logout->logout.logoutUrl("/mcp-logout").logoutSuccessUrl("/mcp-login")).build();
    }
}
