package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.dto.ProcessingBundleViews.Entry;
import com.voltcanary.device.dto.ProcessingBundleViews.RuntimeRequest;
import com.voltcanary.device.dto.ProcessingBundleViews.RuntimeTarget;
import com.voltcanary.device.service.ProcessingBundleReads;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalProcessingBundlesController {

    private final ProcessingBundleReads useCase;

    public InternalProcessingBundlesController(ProcessingBundleReads useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/internal/source-processing-bundles")
    public List<Entry> list(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
        @RequestParam(value = "after", defaultValue = "0") long after,
        @RequestParam(value = "limit", defaultValue = "100") int limit,
        @RequestParam(value = "sourceId", required = false) UUID sourceId,
        @RequestParam(value = "generation", required = false) Long generation
    ) {
        return useCase.list(token, after, limit, sourceId, generation);
    }

    @GetMapping("/internal/device-variable-runtime-access")
    public java.util.Map<String, Boolean> active(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
        @RequestParam("deviceId") UUID device,
        @RequestParam("sourceId") UUID source,
        @RequestParam("variableId") UUID variable
    ) {
        return useCase.active(token, device, source, variable);
    }

    @PostMapping("/internal/device-variable-runtime-access")
    public List<RuntimeTarget> activeBatch(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
        @RequestBody RuntimeRequest request
    ) {
        return useCase.activeBatch(token, request);
    }
}
