package com.voltcanary.common.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record OperatingSchedule(UUID scheduleId,int version,UUID deviceId,String timezone,Instant effectiveFrom,
        Instant effectiveTo,List<Slot> weekSlots,List<LocalDate> holidays,String reason) {
    public record Slot(int dayOfWeek,LocalTime from,LocalTime to) {
    }
}
