package com.closemore.backend.ingestion;

import java.time.OffsetDateTime;

/**
 * One thing that happened and that the AI pipeline should know about.
 *
 * <p>The shape is the contract with a consumer this repository does not own - a Lambda on AWS, an
 * OCI Function on the client's tenancy. Section 10 of the migration plan is explicit that the event
 * schema is language-agnostic and stays exactly as the Next.js backend emits it, so the field names
 * here are the JS ones, not what a Java developer would pick fresh.
 *
 * <p><b>tenant travels in the event.</b> The consumer runs outside this application with no request
 * context and no session variables, so it cannot derive the organisation from anything else. Leaving
 * it out is how a chunk of one customer's call transcript ends up tagged to another's.
 *
 * @param eventType  NOTE_CREATED, DOCUMENT_UPLOADED, RECORDING_UPLOADED
 * @param objectType the thing it happened to - Activity, Attachment
 * @param objectId   its primary key
 * @param parentType the deal or contact the object hangs off, so the consumer can tag the chunk
 * @param parentId   that parent's primary key
 * @param tenant     the organisation name - see above, this is not optional
 * @param userId     who caused it
 * @param occurredAt when, in UTC
 */
public record IngestionEvent(
        String eventType,
        String objectType,
        String objectId,
        String parentType,
        String parentId,
        String tenant,
        String userId,
        OffsetDateTime occurredAt
) {

    public static final String NOTE_CREATED = "NOTE_CREATED";
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";
    public static final String RECORDING_UPLOADED = "RECORDING_UPLOADED";
}
