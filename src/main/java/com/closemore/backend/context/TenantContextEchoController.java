package com.closemore.backend.context;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * curl -H "X-User-Id: u-1" -H "X-User-Role: Admin" -H "X-User-Tenant: Acme" \
 *      http://localhost:8080/internal/tenant-context-echo
 *
 * Expect the response to echo back exactly those three values. Omit the headers and expect
 * all three fields to come back null - that's set_config never having run, which is correct
 * for an unauthenticated request.
 */
@RestController
@RequiredArgsConstructor
public class TenantContextEchoController {

    private final TenantContextEchoService echoService;

    @GetMapping("/internal/tenant-context-echo")
    public TenantContextEchoService.TenantEcho echo() {
        return echoService.readCurrentSessionVariables();
    }
}
