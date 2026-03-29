package com.omnichannel.support.config;

import com.omnichannel.support.security.AgentAccessService;
import com.omnichannel.support.security.AdminAccessService;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final AgentAccessService agentAccessService;
    private final AdminAccessService adminAccessService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/login",
                                "/login.html",
                                "/error",
                                "/favicon.ico",
                                "/app.css",
                                "/app.js",
                                "/webhooks/meta/whatsapp",
                                "/oauth2/**")
                        .permitAll()
                        .requestMatchers("/admin", "/admin.html", "/v1/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/agent", "/v1/tickets", "/v1/tickets/merge")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/v1/tickets/**")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/customer", "/v1/me/**", "/v1/customers/**", "/v1/inbound/**")
                        .authenticated()
                        .requestMatchers("/v1/tickets/**")
                        .authenticated()
                        .requestMatchers("/actuator/health", "/actuator/info")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2Login(oauth -> oauth.loginPage("/login").userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService())))
                .logout(logout -> logout.logoutSuccessUrl("/").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                .exceptionHandling(handling -> handling.accessDeniedPage("/"));
        return http.build();
    }

    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
            authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
            if (agentAccessService.isAllowedAgent(user.getEmail())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_AGENT"));
            }
            if (adminAccessService.isAllowedAdmin(user.getEmail())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "email");
        };
    }
}
