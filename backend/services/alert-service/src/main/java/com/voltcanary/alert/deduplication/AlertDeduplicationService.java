package com.voltcanary.alert.deduplication;

import org.springframework.stereotype.Service;

@Service
public class AlertDeduplicationService {

    public boolean isDuplicate(String deduplicationKey) {
        // Deduplication strategy will be implemented when alert creation is wired.
        return false;
    }
}
