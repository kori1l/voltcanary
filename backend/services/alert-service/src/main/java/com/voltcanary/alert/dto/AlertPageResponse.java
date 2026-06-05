package com.voltcanary.alert.dto;

import java.util.List;

public record AlertPageResponse(
        List<AlertResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
