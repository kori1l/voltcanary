package com.voltcanary.common.events.telemetry;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportContextTest {
    @Test void validatesBoundedOptionalContextAndRetainsFalseAndZero() {
        var metrics=Map.<String,Object>of("latitude",60.1,"longitude",24.9,"transport",Map.of("doorsOpen",false,"scheduleDeviationSeconds",0,"destination","Käpylä"));
        assertTrue(GpsMetrics.valid(metrics));
        assertFalse(TransportContext.fromMetrics(metrics).doorsOpen());
        assertEquals(0,TransportContext.fromMetrics(metrics).scheduleDeviationSeconds());
        for (var context:java.util.List.of(Map.of("unknown",1),Map.of("direction",3),Map.of("doorsOpen",0),Map.of("destination","x".repeat(201)),Map.of("scheduledStart","29:00"),Map.of("scheduleDeviationSeconds",1.5))) {
            assertFalse(GpsMetrics.valid(Map.of("latitude",60.1,"longitude",24.9,"transport",context)));
        }
        assertFalse(GpsMetrics.valid(Map.of("transport",Map.of("doorsOpen",true))));
    }
}
