package com.voltcanary.device.device;

import com.voltcanary.device.account.Account;
import com.voltcanary.device.account.AccountService;
import com.voltcanary.device.devicetype.DeviceType;
import com.voltcanary.device.devicetype.DeviceTypeRepository;
import com.voltcanary.device.location.LocationService;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private static final String DEFAULT_STATUS = "ACTIVE";

    private final AccountService accountService;
    private final LocationService locationService;
    private final DeviceRepository deviceRepository;
    private final DeviceTypeRepository deviceTypeRepository;

    public DeviceService(
            AccountService accountService,
            LocationService locationService,
            DeviceRepository deviceRepository,
            DeviceTypeRepository deviceTypeRepository) {
        this.accountService = accountService;
        this.locationService = locationService;
        this.deviceRepository = deviceRepository;
        this.deviceTypeRepository = deviceTypeRepository;
    }

    @Transactional
    public DeviceResponse create(AuthenticatedUser user, DeviceRequest request) {
        Account account = accountService.currentAccount(user);
        DeviceType deviceType = deviceType(request.type());
        if (request.locationId() != null) {
            locationService.findOwned(user, request.locationId());
        }

        Instant now = Instant.now();
        Device device = new Device();
        device.setId(UUID.randomUUID());
        device.setAccountId(account.getId());
        device.setLocationId(request.locationId());
        device.setDeviceTypeId(deviceType.getId());
        device.setName(request.name());
        device.setManufacturer(request.manufacturer());
        device.setModel(request.model());
        device.setStatus(DEFAULT_STATUS);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);

        return toResponse(deviceRepository.save(device), deviceType);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(AuthenticatedUser user) {
        Account account = accountService.currentAccount(user);
        return deviceRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(AuthenticatedUser user, UUID id) {
        return toResponse(findOwned(user, id));
    }

    @Transactional
    public DeviceResponse update(AuthenticatedUser user, UUID id, DeviceRequest request) {
        Device device = findOwned(user, id);
        DeviceType deviceType = deviceType(request.type());
        if (request.locationId() != null) {
            locationService.findOwned(user, request.locationId());
        }

        device.setLocationId(request.locationId());
        device.setDeviceTypeId(deviceType.getId());
        device.setName(request.name());
        device.setManufacturer(request.manufacturer());
        device.setModel(request.model());
        device.setUpdatedAt(Instant.now());

        return toResponse(deviceRepository.save(device), deviceType);
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID id) {
        deviceRepository.delete(findOwned(user, id));
    }

    public Device findOwned(AuthenticatedUser user, UUID id) {
        Account account = accountService.currentAccount(user);
        return deviceRepository.findByIdAndAccountId(id, account.getId())
                .orElseThrow(() -> new NotFoundException("Device not found"));
    }

    private DeviceResponse toResponse(Device device) {
        DeviceType deviceType = deviceTypeRepository.findById(device.getDeviceTypeId())
                .orElseThrow(() -> new NotFoundException("Device type not found"));
        return toResponse(device, deviceType);
    }

    private DeviceResponse toResponse(Device device, DeviceType deviceType) {
        return new DeviceResponse(
                device.getId(),
                device.getLocationId(),
                device.getName(),
                deviceType.getCode(),
                device.getManufacturer(),
                device.getModel(),
                device.getStatus());
    }

    private DeviceType deviceType(String code) {
        return deviceTypeRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown device type: " + code));
    }
}
