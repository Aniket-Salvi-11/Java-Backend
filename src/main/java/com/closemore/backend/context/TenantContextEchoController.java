package com.closemore.backend.context;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TenantContextEchoController {

    private final TenantContextEchoService echoService;

    @GetMapping("/internal/tenant-context-echo")
    public TenantContextEchoService.TenantEcho echo() {
        return echoService.readCurrentSessionVariables();
    }
}
