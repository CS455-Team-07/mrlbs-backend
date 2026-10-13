package com.mrlbs.config;

import com.mrlbs.security.DemoAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${mrlbs.auth.enabled:false}") boolean authenticationEnabled) throws Exception {

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/demo/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/error",
                                "/api/health",
                                "/actuator/health",
                                "/api/auth/csrf",
                                "/api/auth/request-code",
                                "/api/auth/verify-code",
                                "/api/auth/member-login",
                                "/api/demo/**")
                        .permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/resources", "/api/resources/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated());

        if (!authenticationEnabled) {
            http.addFilterBefore(new DemoAuthenticationFilter(), AnonymousAuthenticationFilter.class);
        }

        return http.build();
    }
}
