package com.closemore.backend.tenant;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base for tests that drive real HTTP rather than calling services directly.
 *
 * <p>Everything from {@link AbstractRlsIT} is inherited - the container singleton, the
 * {@code @DynamicPropertySource} wiring, the two-tenant seed and {@code asTenant}. Only the web
 * environment differs, which is why the @SpringBootTest annotation is repeated here: the parent
 * declares WebEnvironment.NONE, and an annotation on the subclass takes precedence.
 *
 * <p>The cost is a second Spring context, since the annotation change alters the context cache key.
 * The Postgres container is a JVM-wide singleton so it is not started twice, but Hibernate and
 * Flyway do bootstrap again - a few seconds. That is worth paying to test the controller, the JSON
 * shape and the exception handler through the real servlet stack rather than trusting they wire up.
 *
 * <p>MOCK rather than RANDOM_PORT: MockMvc exercises the full filter chain, controller mapping,
 * message conversion and @RestControllerAdvice without binding a socket, which keeps it fast and
 * removes port flakiness from CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
abstract class AbstractWebIT extends AbstractRlsIT {
}
