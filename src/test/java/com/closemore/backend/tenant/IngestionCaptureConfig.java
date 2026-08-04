package com.closemore.backend.tenant;

import com.closemore.backend.ingestion.IngestionEvent;
import com.closemore.backend.ingestion.IngestionEventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replaces the logging publisher with one that records what it was handed, so tests can assert on
 * outbound ingestion events.
 *
 * <p><b>{@code @Primary} rather than a mock of the gateway.</b> The thing under test is
 * {@link com.closemore.backend.ingestion.IngestionEventGateway}'s after-commit registration - that a
 * rolled-back transaction publishes nothing. Mocking the gateway would remove exactly the behaviour
 * the tests exist to check; substituting only the terminal publisher leaves the whole chain intact.
 *
 * <p><b>Shared between ActivityApiIT and AttachmentApiIT deliberately.</b> Adding a bean definition
 * changes the Spring context cache key, so a class that declares one gets its own context. Both
 * classes naming THIS config, with the same property overrides, means they share a single extra
 * context between them rather than starting one each. The Spring context cache keys on the full set
 * of configuration, so any divergence in either annotation silently doubles the cost.
 */
@TestConfiguration
class IngestionCaptureConfig {

    static final List<IngestionEvent> PUBLISHED = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    IngestionEventPublisher capturingPublisher() {
        return PUBLISHED::add;
    }
}
