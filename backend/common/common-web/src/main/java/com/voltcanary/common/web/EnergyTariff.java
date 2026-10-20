package com.voltcanary.common.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EnergyTariff(UUID tariffId, int version, UUID accountId, UUID workspaceId, String currency,
        String timezone, BigDecimal ratePerKWh, Instant effectiveFrom, Instant effectiveTo,
        boolean taxesIncluded, String reason, boolean archived, Instant archivedAt) {
    public EnergyTariff(UUID tariffId,int version,UUID accountId,UUID workspaceId,String currency,String timezone,
            BigDecimal ratePerKWh,Instant effectiveFrom,Instant effectiveTo,boolean taxesIncluded,String reason,boolean archived) {
        this(tariffId,version,accountId,workspaceId,currency,timezone,ratePerKWh,effectiveFrom,effectiveTo,taxesIncluded,reason,archived,null);
    }
}
