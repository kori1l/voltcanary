package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityAuthProperties;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

/** Persists encrypted delivery in the same transaction as the recovery token. */
@Service
@Slf4j
public class RecoveryMail {

    public record Message(String email, String purpose, String code) {
        @Override
        public String toString() {
            return "RecoveryMessage[purpose=" + purpose + "]";
        }
    }

    public record Queued(UUID id) {}

    private final ObjectProvider<JavaMailSender> senders;
    private final ApplicationEventPublisher events;
    private final Executor executor;
    private final RecoveryMailQueue queue;
    private final RecoveryMailCipher cipher;
    private final RecoveryMailDelivery delivery;
    private final IdentityAuthProperties properties;

    public RecoveryMail(
        ObjectProvider<JavaMailSender> senders,
        ApplicationEventPublisher events,
        @Qualifier("recoveryMailExecutor") Executor executor,
        RecoveryMailQueue queue,
        RecoveryMailCipher cipher,
        RecoveryMailDelivery delivery,
        IdentityAuthProperties properties
    ) {
        this.senders = senders;
        this.events = events;
        this.executor = executor;
        this.queue = queue;
        this.cipher = cipher;
        this.delivery = delivery;
        this.properties = properties;
    }

    public void requireConfigured() {
        JavaMailSender sender = senders.getIfAvailable();
        if (
            sender == null ||
            (sender instanceof JavaMailSenderImpl configured &&
                (configured.getHost() == null || configured.getHost().isBlank()))
        ) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Account recovery email delivery is unavailable"
            );
        }
        cipher.requireConfigured();
    }

    public void queue(String email, String purpose, String code) {
        long ttl = switch (purpose) {
            case "password reset" -> properties.getPasswordResetTokenTtlSeconds();
            case "email verification" -> properties.getEmailVerificationTokenTtlSeconds();
            default -> throw new IllegalArgumentException("Unsupported recovery purpose");
        };
        UUID id = queue.enqueue(new Message(email, purpose, code), Instant.now().plusSeconds(ttl));
        events.publishEvent(new Queued(id));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(Queued event) {
        try {
            executor.execute(delivery::deliverDue);
        } catch (RuntimeException unavailable) {
            // The durable row remains available to the scheduled worker.
            log.warn(
                "recoveryMailDispatchDeferred id={} reasonCode={}",
                event.id(),
                unavailable.getClass().getSimpleName()
            );
        }
    }
}
