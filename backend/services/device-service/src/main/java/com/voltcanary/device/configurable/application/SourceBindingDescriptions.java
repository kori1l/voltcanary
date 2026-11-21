package com.voltcanary.device.configurable.application;

import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SourceBindingDescriptions {
    private final SourceBindingCatalog catalog;

    public VclSourceBindingDescription describe(AuthenticatedUser actor, UUID sourceId, Long schemaRevision) {
        return catalog.describe(actor.id(), sourceId, schemaRevision);
    }
}
