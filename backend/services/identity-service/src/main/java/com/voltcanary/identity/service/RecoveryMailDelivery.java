package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RecoveryMailDelivery {
    private final RecoveryMailQueue queue;
    private final RecoveryMailCipher cipher;
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public RecoveryMailDelivery(RecoveryMailQueue queue, RecoveryMailCipher cipher, ObjectProvider<JavaMailSender> senders, IdentityAuthProperties properties) {
        this.queue = queue;
        this.cipher = cipher;
        this.senders = senders;
        from = properties.getMailFrom();
    }

    @Scheduled(initialDelayString = "${app.auth.mail-delivery.initial-delay:5s}", fixedDelayString = "${app.auth.mail-delivery.delay:5s}")
    public void deliverDue() {
        for (int count = 0; count < 10; count++) {
            var claim = queue.claim();
            if (claim == null) return;
            try {
                var payload = cipher.decrypt(claim.ciphertext());
                var message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(payload.email());
                message.setSubject("VoltCanary " + payload.purpose());
                message.setText("Your " + payload.purpose() + " code is:\n\n" + payload.code()
                        + "\n\nIf you did not request this code, ignore this email.");
                senders.getObject().send(message);
                queue.delivered(claim);
            } catch (RuntimeException unavailable) {
                queue.failed(claim, unavailable.getClass().getSimpleName());
                log.warn("recoveryMailDeliveryDeferred id={} reasonCode={}", claim.id(), unavailable.getClass().getSimpleName());
            }
        }
    }
}
