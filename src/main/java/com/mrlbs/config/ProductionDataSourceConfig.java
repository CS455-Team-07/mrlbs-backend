package com.mrlbs.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class ProductionDataSourceConfig {
    @Bean
    HikariDataSource dataSource(DataSourceProperties properties, Environment environment) throws SQLException {
        requireVerifiedPostgresTls(properties.getUrl());
        requireProtocol(environment, "mrlbs.auth.frontend-base-url", "https");
        requireProtocol(environment, "mrlbs.ai.gemini.base-url", "https");
        requireProtocol(environment, "mrlbs.telemetry.service-url", "https");
        requireProtocol(environment, "spring.rabbitmq.addresses", "amqps");
        for (String origin : environment.getRequiredProperty("mrlbs.cors.allowed-origins").split(",", -1)) {
            URI uri = requireProtocol(origin.strip(), "mrlbs.cors.allowed-origins", "https");
            if (!uri.getPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("Production CORS entries must be exact HTTPS origins");
            }
        }
        if (!"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy"))
                || !environment.getProperty("mrlbs.security.require-tls", Boolean.class, false)
                || !environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)
                || !environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class, false)
                || !environment.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class, false)
                || !environment.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", Boolean.class, false)) {
            throw new IllegalStateException("Production requires HTTPS, secure session cookies and verified SMTP STARTTLS");
        }

        var pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        try (var connection = pool.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT ssl,
                         NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls
                         OR has_schema_privilege(current_user, current_schema(), 'CREATE')
                         OR has_database_privilege(current_user, current_database(), 'CREATE')
                         OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = pg_roles.oid
                                    AND relnamespace = current_schema()::regnamespace)
                         OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = pg_roles.oid)) AS restricted
                     FROM pg_stat_ssl CROSS JOIN pg_roles
                     WHERE pid = pg_backend_pid() AND rolname = current_user
                     """)) {
            if (!result.next() || !result.getBoolean("ssl") || !result.getBoolean("restricted")) {
                throw new IllegalStateException("Production PostgreSQL requires TLS and a restricted runtime role");
            }
            return pool;
        } catch (RuntimeException | SQLException exception) {
            pool.close();
            throw exception;
        }
    }

    private static void requireVerifiedPostgresTls(String url) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("A PostgreSQL JDBC URL is required");
        }
        Map<String, String> options = new HashMap<>();
        String query = URI.create(url.substring(5)).getRawQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", 2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if (options.putIfAbsent(name, value) != null) {
                    throw new IllegalStateException("Duplicate PostgreSQL JDBC options");
                }
            }
        }
        if (!"verify-full".equals(options.get("sslmode")) || options.containsKey("sslhostnameverifier")
                || (options.containsKey("sslfactory")
                && !"org.postgresql.ssl.DefaultJavaSSLFactory".equals(options.get("sslfactory")))) {
            throw new IllegalStateException("PostgreSQL requires sslmode=verify-full and standard certificate validation");
        }
    }

    private static void requireProtocol(Environment environment, String property, String scheme) {
        requireProtocol(environment.getRequiredProperty(property), property, scheme);
    }

    private static URI requireProtocol(String value, String property, String scheme) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(property + " must contain a valid " + scheme + " URL");
        }
        if (!scheme.equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || (!"amqps".equals(scheme) && uri.getUserInfo() != null)) {
            throw new IllegalStateException(property + " must contain a valid " + scheme + " URL");
        }
        return uri;
    }
}
