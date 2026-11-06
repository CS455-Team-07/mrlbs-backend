package com.mrlbs.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class OtpRecoveryTests {
    private static final String EMAIL = "member@iitk.ac.in";

    @Test
    void rejectedDeliveryInvalidatesTheCodeAndAllowsAResendWithoutLoggingIt(CapturedOutput output) {
        JavaMailSender sender = mock(JavaMailSender.class);
        AtomicReference<String> deliveredCode = new AtomicReference<>();
        doAnswer(invocation -> {
            deliveredCode.set(codeFrom(invocation.getArgument(0)));
            throw new MailSendException("simulated SMTP outage");
        }).doAnswer(invocation -> {
            deliveredCode.set(codeFrom(invocation.getArgument(0)));
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        OtpService service = new OtpService(sender, "noreply@iitk.ac.in", "iitk.ac.in");

        assertThatThrownBy(() -> service.generateAndSend(EMAIL)).isInstanceOf(MailSendException.class);
        String failedCode = deliveredCode.get();
        assertThat(service.verify(EMAIL, failedCode)).isFalse();
        assertThat(output.getAll()).doesNotContain(failedCode);

        service.generateAndSend(EMAIL);
        String resentCode = deliveredCode.get();
        assertThat(service.verify(EMAIL, resentCode)).isTrue();
        assertThat(output.getAll()).doesNotContain(resentCode);
    }

    @Test
    void parallelVerificationConsumesTheSameCodeOnlyOnce() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        AtomicReference<String> deliveredCode = new AtomicReference<>();
        doAnswer(invocation -> {
            deliveredCode.set(codeFrom(invocation.getArgument(0)));
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        OtpService service = new OtpService(sender, "noreply@iitk.ac.in", "iitk.ac.in");

        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int attempt = 0; attempt < 12; attempt++) {
                service.generateAndSend(EMAIL);
                String code = " ".repeat(100_000) + deliveredCode.get();
                CountDownLatch ready = new CountDownLatch(16);
                CountDownLatch start = new CountDownLatch(1);
                var results = new ArrayList<Future<Boolean>>();
                for (int worker = 0; worker < 16; worker++) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Verification start timed out");
                        }
                        return service.verify(EMAIL, code);
                    }));
                }
                boolean allReady = ready.await(5, TimeUnit.SECONDS);
                start.countDown();
                assertThat(allReady).isTrue();
                int accepted = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(5, TimeUnit.SECONDS)) {
                        accepted++;
                    }
                }
                assertThat(accepted).as("successful uses of one verification code").isEqualTo(1);
            }
        }
    }

    private static String codeFrom(SimpleMailMessage message) {
        return message.getText().lines().findFirst().orElseThrow().replaceAll("\\D", "");
    }
}
