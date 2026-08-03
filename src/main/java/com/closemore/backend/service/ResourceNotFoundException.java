package com.closemore.backend.service;

/**
 * No row with that id is visible to this caller. Mapped to 404 by {@code ApiExceptionHandler}.
 *
 * <p><b>"Visible to this caller" is doing real work in that sentence.</b> Under RLS a row belonging
 * to another tenant, or to another Sales_Rep in the same tenant, does not come back from
 * {@code findById} at all - the policy filters it before Hibernate sees it. So "the row does not
 * exist" and "the row is forbidden" are indistinguishable here, and both arrive as this exception.
 *
 * <p>That is the outcome we want rather than a limitation to work around. Answering 403 for a row
 * that exists and 404 for one that does not turns the id space into an oracle: a Sales_Rep could
 * enumerate ids and learn exactly which contacts their colleagues own. 404 for both leaks nothing.
 * Pinned by a test.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
