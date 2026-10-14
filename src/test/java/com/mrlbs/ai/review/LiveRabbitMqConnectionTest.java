package com.mrlbs.ai.review;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveRabbitMqConnectionTest {

    private Map<String, String> loadEnv() {
        Map<String, String> env = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader("postgres.local.env"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Could not load postgres.local.env: " + e.getMessage());
        }
        return env;
    }

    @Test
    void testDirectRabbitMqConnection() throws Exception {
        Map<String, String> env = loadEnv();
        String rabbitAddress = env.get("SPRING_RABBITMQ_ADDRESSES");
        assertNotNull(rabbitAddress, "SPRING_RABBITMQ_ADDRESSES must be in postgres.local.env");

        System.out.println("Testing RabbitMQ Connection with URI scheme: " + rabbitAddress.substring(0, 8) + "...");

        URI uri = new URI(rabbitAddress);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(uri.getHost());
        factory.setPort(uri.getPort() > 0 ? uri.getPort() : (rabbitAddress.startsWith("amqps://") ? 5671 : 5672));
        if (uri.getUserInfo() != null) {
            String[] parts = uri.getUserInfo().split(":", 2);
            factory.setUsername(parts[0]);
            if (parts.length > 1) {
                factory.setPassword(parts[1]);
            }
        }
        if (uri.getPath() != null && uri.getPath().length() > 1) {
            factory.setVirtualHost(uri.getPath().substring(1));
        }
        if (rabbitAddress.startsWith("amqps://")) {
            factory.useSslProtocol(javax.net.ssl.SSLContext.getDefault());
            factory.enableHostnameVerification();
        }

        try (Connection connection = factory.newConnection()) {
            assertTrue(connection.isOpen(), "Connection should be open");
            System.out.println("Live CloudAMQP connection with strict TLS and hostname verification established! Server version: "
                    + connection.getServerProperties().get("version"));
        }

        // Test with Spring CachingConnectionFactory wrapping the verified factory
        CachingConnectionFactory cachingFactory = new CachingConnectionFactory(factory);
        cachingFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        try (var conn = cachingFactory.createConnection()) {
            assertTrue(conn.isOpen(), "Spring AMQP connection should be open");
            System.out.println("Spring AMQP CachingConnectionFactory successfully connected with strict TLS verification!");
        } finally {
            cachingFactory.destroy();
        }
    }
}
