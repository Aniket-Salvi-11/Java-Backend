package com.closemore.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
// order = 0 (highest precedence) so the transaction advisor is the OUTERMOST advice on
// @Transactional service methods. This must run before TenantContextAspect (see its @Order),
// otherwise set_config could execute before BEGIN, or worse, on a connection not yet bound
// to the transaction. Do not remove this without re-reading Section 5 of the reference doc.
@EnableTransactionManagement(order = 0)
public class CloseMoreBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloseMoreBackendApplication.class, args);
    }
}
