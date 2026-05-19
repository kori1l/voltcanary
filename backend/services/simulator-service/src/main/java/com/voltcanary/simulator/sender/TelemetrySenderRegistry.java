package com.voltcanary.simulator.sender;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TelemetrySenderRegistry {

    private final List<TelemetrySender> senders;

    public TelemetrySenderRegistry(List<TelemetrySender> senders) {
        this.senders = senders;
    }

    public TelemetrySender senderFor(String protocol) {
        return senders.stream()
                .filter(sender -> sender.supports(protocol))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported simulation protocol: " + protocol));
    }
}
