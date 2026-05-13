package com.voltcanary.device.location;

import com.voltcanary.device.account.Account;
import com.voltcanary.device.account.AccountService;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationService {

    private final AccountService accountService;
    private final LocationRepository locationRepository;

    public LocationService(AccountService accountService, LocationRepository locationRepository) {
        this.accountService = accountService;
        this.locationRepository = locationRepository;
    }

    @Transactional
    public LocationResponse create(AuthenticatedUser user, LocationRequest request) {
        Account account = accountService.currentAccount(user);
        Instant now = Instant.now();

        Location location = new Location();
        location.setId(UUID.randomUUID());
        location.setAccountId(account.getId());
        location.setName(request.name());
        location.setDescription(request.description());
        location.setCreatedAt(now);
        location.setUpdatedAt(now);

        return toResponse(locationRepository.save(location));
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> list(AuthenticatedUser user) {
        Account account = accountService.currentAccount(user);
        return locationRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public LocationResponse get(AuthenticatedUser user, UUID id) {
        return toResponse(findOwned(user, id));
    }

    @Transactional
    public LocationResponse update(AuthenticatedUser user, UUID id, LocationRequest request) {
        Location location = findOwned(user, id);
        location.setName(request.name());
        location.setDescription(request.description());
        location.setUpdatedAt(Instant.now());
        return toResponse(locationRepository.save(location));
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID id) {
        locationRepository.delete(findOwned(user, id));
    }

    public Location findOwned(AuthenticatedUser user, UUID id) {
        Account account = accountService.currentAccount(user);
        return locationRepository.findByIdAndAccountId(id, account.getId())
                .orElseThrow(() -> new NotFoundException("Location not found"));
    }

    private LocationResponse toResponse(Location location) {
        return new LocationResponse(location.getId(), location.getName(), location.getDescription());
    }
}
