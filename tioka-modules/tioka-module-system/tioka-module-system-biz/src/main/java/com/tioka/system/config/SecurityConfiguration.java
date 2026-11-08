package com.tioka.system.config;

import com.tioka.system.application.auth.BearerTokenFilter;
import com.tioka.system.application.auth.PublicApiRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http,
                            BearerTokenFilter bearer,
                            PublicApiRegistry publicApis,
                            RestAuthEntryPoint entry,
                            RestAccessDenied denied) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(publicApis.matchers()).permitAll()
                        .requestMatchers(
                                "/api/system/auth/login",
                                "/api/system/auth/refresh",
                                "/api/system/auth/oidc/**",
                                // IM 平台回调（@PublicApi）；扫描时序兜底
                                "/api/connector/im/*/*/events",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**"
                        ).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(entry).accessDeniedHandler(denied))
                .build();
    }
}
