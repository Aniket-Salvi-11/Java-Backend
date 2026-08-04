package com.closemore.backend.ingestion;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What services actually call. Holds an event until the transaction that produced it has committed,
 * then hands it to the configured {@link IngestionEventPublisher}.
 *
 * <p><b>Publishing inside the transaction is the bug this class exists to prevent.</b> A service
 * saves a note, publishes NOTE_CREATED, and then something later in the same method fails - an RLS
 * refusal on the audit write, a constraint violation, a validation error on a second entity. The
 * database rolls back and the note never existed. But the event has already gone: the pipeline
 * transcribes, embeds and stores a chunk for a note nobody can see, and it will sit in the vector
 * store answering questions forever, because nothing ever tells the consumer to remove it.
 *
 * <p>Rolling that back is not possible from here - the queue is a different system. Not sending it
 * in the first place is.
 *
 * <p><b>The failure is one-directional and that is the right trade.</b> If the commit succeeds and
 * the dispatch then fails, the row exists without an ingestion event, so the copilot's answer is
 * stale until the next save on that object. That is recoverable and invisible to correctness. The
 * other direction is not.
 *
 * <p>When there is no active transaction the event is dispatched immediately. That path should not
 * occur from the services - every one of them is {@code @Transactional} - but silently dropping
 * events would be a far worse response to an unexpected call than sending them.
 */
@Component
@RequiredArgsConstructor
public class IngestionEventGateway {

    private static final Logger log = LoggerFactory.getLogger(IngestionEventGateway.class);

    private final IngestionEventPublisher publisher;

    public void publishAfterCommit(IngestionEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(event);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(event);
            }
        });
    }

    /**
     * Dispatches, and never lets a failure escape.
     *
     * <p>By the time this runs the business transaction has committed. Throwing here cannot undo
     * that; it can only turn a successful save into an error the user sees, for a reason that has
     * nothing to do with what they asked for.
     */
    private void dispatch(IngestionEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish ingestion event {} for {}:{} - the row is committed and "
                            + "the event is lost. The copilot will hold a stale view of this object "
                            + "until the next save on it.",
                    event.eventType(), event.objectType(), event.objectId(), ex);
        }
    }
}
