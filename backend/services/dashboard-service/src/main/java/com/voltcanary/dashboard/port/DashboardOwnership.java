package com.voltcanary.dashboard.port;

import com.voltcanary.dashboard.domain.DashboardActor;
import java.util.UUID;

/** No raw business values cross the ownership boundary. */
public interface DashboardOwnership {
    DashboardActor resolveReadOnly(String authorization);
    DashboardActor resolve(String authorization);

    boolean canReadDevice(String authorization, UUID deviceId, DashboardActor actor);

    boolean canReadLocation(String authorization, UUID locationId, DashboardActor actor);
}
