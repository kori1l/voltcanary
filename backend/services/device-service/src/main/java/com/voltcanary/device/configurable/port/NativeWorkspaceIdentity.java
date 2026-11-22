package com.voltcanary.device.configurable.port;

import com.voltcanary.vcl.VclCanonical;
import java.util.Set;
import java.util.UUID;

public interface NativeWorkspaceIdentity {
    record Membership(UUID workspace, UUID actor, String role, long version, Set<UUID> teams) {
        public Membership {
            teams = Set.copyOf(teams);
        }

        public String digest() {
            return VclCanonical.sha256(
                "NATIVE_WORKSPACE_MEMBERSHIP/1:" +
                    workspace +
                    ":" +
                    actor +
                    ":" +
                    role +
                    ":" +
                    version +
                    ":" +
                    teams.stream().sorted().toList()
            );
        }
    }

    Membership membership(UUID workspace, UUID actor);
    boolean teamExists(UUID workspace, UUID team);

    record ResourceReads(
        String membershipDigest,
        long associationVersion,
        Set<UUID> deviceIds,
        String digest
    ) {
        public ResourceReads {
            deviceIds = Set.copyOf(deviceIds);
        }
    }

    ResourceReads resources(UUID workspace, UUID account, UUID actor);
}
