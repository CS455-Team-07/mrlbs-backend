package com.mrlbs.config;

import com.mrlbs.security.DemoAuthenticationFilter;
import com.mrlbs.security.InstitutionalOidcUserService;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

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
            @Value("${mrlbs.auth.enabled:false}") boolean authenticationEnabled,
            @Value("${mrlbs.auth.frontend-base-url}") String frontendBaseUrl) throws Exception {
        String frontendUrl = frontendBaseUrl.replaceAll("/+$", "");

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Demo identity-switcher and member-login endpoints
                        .ignoringRequestMatchers("/api/demo/**", "/api/auth/member-login",
                                "/api/auth/login",
                                "/api/auth/request-code", "/api/auth/verify-code",
                                "/api/auth/logout", "/logout"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/error",
                                "/api/health",
                                "/actuator/health",
                                "/api/auth/csrf",
                                "/api/auth/login",
                                "/api/auth/member-login",
                                "/api/auth/request-code",
                                "/api/auth/verify-code",
                                "/api/auth/logout",
                                "/logout",
                                "/api/demo/**")
                        .permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/resources", "/api/resources/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated());

        if (authenticationEnabled) {
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
