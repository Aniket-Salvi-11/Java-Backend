package com.closemore.backend.ingestion;

/**
 * Where ingestion events go. One method, and no vendor type anywhere in the signature.
 *
 * <p><b>This interface is the whole point of the abstraction, and it exists now rather than later
 * on purpose.</b> Migration Plan v4 records that Section 4's stack was hard-wired to AWS while the
 * first client runs on Oracle Cloud, and that Phase 2's instruction to "port the SQS publish logic"
 * was effectively an instruction to hardwire it. Naming this interface before the first caller
 * exists costs a file. Retrofitting it after twelve resource groups call an SQS client directly
 * costs every one of those call sites.
 *
 * <p>Implementations expected: AWS SQS, OCI Queue or Streaming, and the logging no-op that ships
 * today. Which one runs is a configuration choice, not a code change - see
 * {@code closemore.ingestion.publisher} in application.yml.
 *
 * <p><b>Implementations must not throw.</b> Every caller is inside a business transaction that has
 * already committed by the time this runs, so there is nothing left to roll back: a failure here can
 * only turn a successful save into an error the user sees for no reason. Log and swallow. A missed
 * ingestion event costs a stale answer from the copilot; a failed save costs the rep their work.
 */
public interface IngestionEventPublisher {

    void publish(IngestionEvent event);
}
