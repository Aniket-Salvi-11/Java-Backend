package com.closemore.backend.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default publisher: writes the event to the log and stops there.
 *
 * <p><b>Not a placeholder to be embarrassed about.</b> It is the correct implementation for local
 * development, for CI, and for any environment with no queue attached - which today is all of them,
 * because the SQS and OCI Queue implementations belong with the deployment work rather than with the
 * endpoint port. Wiring a real queue client here now would mean adding a vendor SDK to pom.xml for
 * code nothing can exercise, and a pom change invalidates the Maven cache and provokes the 429s from
 * Maven Central that gotcha 11 records.
 *
 * <p>Logging at INFO rather than DEBUG is deliberate. During cutover somebody will need to answer
 * "is the backend emitting ingestion events at all", and the answer should be visible without
 * turning up log levels on a running system.
 *
 * <p>Selected by {@code closemore.ingestion.publisher=logging}, which is also the default when the
 * property is absent. An SQS or OCI implementation added later carries the same annotation with a
 * different value and this bean steps aside.
 */
@Component
@ConditionalOnProperty(name = "closemore.ingestion.publisher",
        havingValue = "logging", matchIfMissing = true)
public class LoggingIngestionEventPublisher implements IngestionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingIngestionEventPublisher.class);

    @Override
    public void publish(IngestionEvent event) {
        log.info("Ingestion event (not dispatched - logging publisher active): "
                        + "type={} object={}:{} parent={}:{} tenant={} user={} at={}",
                event.eventType(), event.objectType(), event.objectId(),
                event.parentType(), event.parentId(), event.tenant(),
                event.userId(), event.occurredAt());
    }
}
