package com.voltcanary.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserRole;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.ForgotPasswordRequest;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.service.AuthService;
import com.voltcanary.identity.service.RecoveryMail;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
        "spring.jpa.open-in-view=false",
        "app.auth.mail-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    }
)
class RecoveryDeliveryBoundaryTest {

    @Autowired
    private AuthService auth;

    @Autowired
    private RecoveryMail mail;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private JavaMailSender sender;

    @Test
    void resetCodeGoesOnlyToTheAccountMailbox() {
        String email = "recovery-" + UUID.randomUUID() + "@example.test";
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash("unused-in-this-test");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        users.save(user);
        var response = auth.forgotPassword(new ForgotPasswordRequest(email));
        var delivered = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(3000)).send(delivered.capture());
        assertThat(response.message()).isEqualTo("password reset requested");
        assertThat(delivered.getValue().getTo()).containsExactly(email);
        assertThat(delivered.getValue().getText()).contains("password reset code");
    }

    @Test
    void rolledBackResetDoesNotSendAMessage() {
        transactions.executeWithoutResult(status -> {
            mail.queue("ignored@example.test", "password reset", "uncommitted-code");
            status.setRollbackOnly();
        });
        verifyNoInteractions(sender);
    }

    @Test
    void recoveryEventDiagnosticDoesNotContainTheCodeOrRecipient() {
        assertThat(
            new RecoveryMail.Message(
                "private@example.test",
                "password reset",
                "private-code"
            ).toString()
        ).doesNotContain("private-code", "private@example.test");
    }
}
