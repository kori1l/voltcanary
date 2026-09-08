package com.voltcanary.alert.notification.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NotificationProviderRegistry {

    private final Map<String, NotificationProviderAdapter> adapters;

    public NotificationProviderRegistry(List<NotificationProviderAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        adapter -> normalize(adapter.channelType()),
                        Function.identity()));
    }

    public Optional<NotificationProviderAdapter> find(String channelType) {
        return Optional.ofNullable(adapters.get(normalize(channelType)));
    }

    public boolean hasAdapter(String channelType) {
        return adapters.containsKey(normalize(channelType));
    }

    private String normalize(String channelType) {
        return channelType == null ? "" : channelType.trim().toUpperCase(Locale.ROOT);
    }
}
