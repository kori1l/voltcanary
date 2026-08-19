package com.voltcanary.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.voltcanary.analytics.domain.*;
import com.voltcanary.analytics.dto.*;
import com.voltcanary.analytics.repository.MlSettingsRepository;
import com.voltcanary.analytics.service.MlPolicyMapper;
import com.voltcanary.analytics.service.MlPolicyService;
import com.voltcanary.analytics.service.MlPolicyValidator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MlPolicyServiceTest {
    private final MlSettingsRepository repository = mock(MlSettingsRepository.class);
    private final MlPolicyService service = new MlPolicyService(
            repository,
            new MlPolicyMapper(),
            new MlPolicyValidator());

    @Test void deviceWithoutOverrideInheritsAccountDefaults() {
        UUID account = UUID.randomUUID(), device = UUID.randomUUID(); MlSettings value = settings(account, null);
        when(repository.findByDeviceId(device)).thenReturn(Optional.empty());
        when(repository.findByAccountIdAndDeviceIdIsNull(account)).thenReturn(Optional.of(value));
        MlSettingsResponse response = service.device(account, device);
        assertEquals("INHERITED", response.source()); assertEquals(PredictionMode.MODEL_ONLY, response.predictionMode());
    }
    @Test void deviceOverrideWinsAndCanBeCleared() {
        UUID account = UUID.randomUUID(), device = UUID.randomUUID(); MlSettings value = settings(account, device);
        when(repository.findByDeviceId(device)).thenReturn(Optional.of(value));
        assertEquals("DEVICE_OVERRIDE", service.device(account, device).source());
        service.clearDevice(account, device); verify(repository).delete(value);
    }
    @Test void deviceOverrideFromAnotherAccountIsHidden() {
        UUID account = UUID.randomUUID(), otherAccount = UUID.randomUUID(), device = UUID.randomUUID();
        when(repository.findByDeviceId(device)).thenReturn(Optional.of(settings(otherAccount, device)));
        assertThrows(ResponseStatusException.class, () -> service.device(account, device));
        assertThrows(ResponseStatusException.class, () -> service.clearDevice(account, device));
        verify(repository, never()).delete(any());
    }
    @Test void disabledMlRejectsModelMode() {
        MlSettingsRequest request = new MlSettingsRequest(false, PredictionMode.MODEL_ONLY, .5, MaintenanceMlBehavior.SUPPRESS_ML, true);
        assertThrows(ResponseStatusException.class, () -> service.saveAccount(UUID.randomUUID(), UUID.randomUUID(), request));
    }
    @Test void thresholdOutsideUnitRangeIsRejected() {
        MlSettingsRequest request = new MlSettingsRequest(true, PredictionMode.MODEL_ONLY, 1.1, MaintenanceMlBehavior.SUPPRESS_ML, true);
        assertThrows(ResponseStatusException.class, () -> service.saveAccount(UUID.randomUUID(), UUID.randomUUID(), request));
    }
    private MlSettings settings(UUID account, UUID device) { MlSettings v = new MlSettings(); v.setId(UUID.randomUUID()); v.setAccountId(account); v.setDeviceId(device); v.setMlEnabled(true); v.setPredictionMode(PredictionMode.MODEL_ONLY); v.setConfidenceWarningThreshold(.7); v.setMaintenanceBehavior(MaintenanceMlBehavior.SUPPRESS_ML); v.setExplanationsVisible(true); return v; }
}
