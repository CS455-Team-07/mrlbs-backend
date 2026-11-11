package com.mrlbs.config;

import com.mrlbs.security.DemoAuthenticationFilter;
import com.mrlbs.security.InstitutionalOidcUserService;
import com.mrlbs.security.RequestAuditFilter;
import com.mrlbs.security.RequestRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpStatus;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    OAuth2AuthorizationRequestResolver googleAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${mrlbs.auth.institutional-domain}") String institutionalDomain,
            @Value("${mrlbs.auth.dev-allow-any-google-domain:false}") boolean devAllowAnyGoogleDomain,
            Environment environment) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                "/oauth2/authorization");
        boolean allowDevelopmentDomainBypass = devAllowAnyGoogleDomain
                && environment.acceptsProfiles(Profiles.of("dev"));
        if (!allowDevelopmentDomainBypass) {
            resolver.setAuthorizationRequestCustomizer(customizer -> customizer
                    .additionalParameters(parameters -> parameters.put("hd", institutionalDomain)));
        }
        return resolver;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            InstitutionalOidcUserService institutionalOidcUserService,
            OAuth2AuthorizationRequestResolver googleAuthorizationRequestResolver,
            RequestRateLimiter limiter, Environment environment,
            @Value("${mrlbs.security.require-tls:false}") boolean requireTls,
            @Value("${mrlbs.security.auth-per-minute:30}") int authLimit,
            @Value("${mrlbs.security.booking-per-minute:30}") int bookingLimit,
            @Value("${mrlbs.auth.frontend-base-url}") String frontendBaseUrl) throws Exception {
        String frontendUrl = frontendBaseUrl.replaceAll("/+$", "");
        boolean demo = DemoAuthenticationFilter.isEnabled(environment);
        var csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        if (requireTls) {
            csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite(
                    environment.getProperty("server.servlet.session.cookie.same-site", "lax")));
        }

        http
                .cors(Customizer.withDefaults())
                .addFilterBefore(new RequestAuditFilter(limiter, authLimit, bookingLimit, requireTls), CsrfFilter.class)
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/demo/**",
                                "/api/auth/login",
                                "/api/auth/request-code", "/api/auth/verify-code",
                                "/api/ai/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/error",
                                "/api/health",
                                "/actuator/health",
                                "/api/auth/csrf",
                                "/api/auth/login",
                                "/api/auth/request-code",
                                "/api/auth/verify-code",
                                "/api/demo/**")
                        .permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/api/auth/member-login").access((authentication, request) ->
                                new org.springframework.security.authorization.AuthorizationDecision(demo
                                        || authentication.get().getAuthorities().stream()
                                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_MEMBER"))))
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/resources", "/api/resources/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated());

        if (!demo) {
            http.oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(googleAuthorizationRequestResolver))
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(institutionalOidcUserService))
                        .successHandler((request, response, authentication) ->
                                response.sendRedirect(frontendUrl + "/login?authenticated"))
                        .failureHandler((request, response, exception) -> {
                            String error = "authentication";
                            if (exception instanceof OAuth2AuthenticationException oauthException
                                    && "institutional_domain_required".equals(
                                            oauthException.getError().getErrorCode())) {
                                error = "institutional-domain";
                            }
                            response.sendRedirect(frontendUrl + "/login?error=" + error);
                        }));
        } else {
            http.addFilterBefore(new DemoAuthenticationFilter(), AnonymousAuthenticationFilter.class);
        }

        return http.build();
    }
}
