package com.mrlbs.ai.review;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE_NAME = "mrlbs.booking.exchange";
    public static final String QUEUE_NAME = "mrlbs.ai.review.queue";
    public static final String ROUTING_KEY = "booking.pending_approval";

    public static final String DLX_NAME = "mrlbs.booking.dlx";
    public static final String DLQ_NAME = "mrlbs.ai.review.dlq";
    public static final String DLQ_ROUTING_KEY = "booking.review.dead";

    @Bean
    public ConnectionFactory connectionFactory(
            @Value("${spring.rabbitmq.addresses:}") String addresses,
            @Value("${spring.rabbitmq.host:localhost}") String host,
            @Value("${spring.rabbitmq.port:5672}") int port,
            @Value("${spring.rabbitmq.username:guest}") String username,
            @Value("${spring.rabbitmq.password:guest}") String password,
            @Value("${spring.rabbitmq.publisher-confirm-type:correlated}") String confirmType,
            @Value("${spring.rabbitmq.publisher-returns:true}") boolean publisherReturns
    ) throws Exception {
        CachingConnectionFactory factory;
        if (addresses != null && (addresses.startsWith("amqp://") || addresses.startsWith("amqps://"))) {
            java.net.URI uri = new java.net.URI(addresses);
            com.rabbitmq.client.ConnectionFactory rmqFactory = new com.rabbitmq.client.ConnectionFactory();
            rmqFactory.setHost(uri.getHost());
            rmqFactory.setPort(uri.getPort() > 0 ? uri.getPort() : (addresses.startsWith("amqps://") ? 5671 : 5672));
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                rmqFactory.setUsername(parts[0]);
                if (parts.length > 1) {
                    rmqFactory.setPassword(parts[1]);
                }
            }
            if (uri.getPath() != null && uri.getPath().length() > 1) {
                rmqFactory.setVirtualHost(uri.getPath().substring(1));
            }
            if (addresses.startsWith("amqps://")) {
                rmqFactory.useSslProtocol(javax.net.ssl.SSLContext.getDefault());
                rmqFactory.enableHostnameVerification();
                rmqFactory.enableHostnameVerification();
            }
            rmqFactory.setAutomaticRecoveryEnabled(false);
            factory = new CachingConnectionFactory(rmqFactory);
        } else if (addresses != null && !addresses.trim().isEmpty()) {
            factory = new CachingConnectionFactory();
            factory.setAddresses(addresses);
            factory.setUsername(username);
            factory.setPassword(password);
        } else {
            factory = new CachingConnectionFactory(host, port);
            factory.setUsername(username);
            factory.setPassword(password);
        }

        if ("correlated".equalsIgnoreCase(confirmType)) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        } else if ("simple".equalsIgnoreCase(confirmType)) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        }
        factory.setPublisherReturns(publisherReturns);
        return factory;
    }

    @Bean
    public TopicExchange bookingExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_NAME, true, false);
    }

    @Bean
    public Queue aiReviewDlq() {
        return QueueBuilder.durable(DLQ_NAME).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(aiReviewDlq()).to(deadLetterExchange()).with(DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue aiReviewQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DLX_NAME)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding aiReviewBinding() {
        return BindingBuilder.bind(aiReviewQueue()).to(bookingExchange()).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper typeMapper =
                new org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("*");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            @Value("${mrlbs.rabbitmq.auto-startup:true}") boolean autoStartup
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setAutoStartup(autoStartup);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
