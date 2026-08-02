package com.closemore.backend.controller;

import com.closemore.backend.auth.AuthenticationException;
import com.closemore.backend.rbac.RbacException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into HTTP responses. The Phase 2 deliverable the plan lists as "global exception
 * handling / RbacException to HTTP mapping".
 *
 * <p>The error envelope is {@code {"error": "..."}}, matching the legacy backend's jsonError so
 * existing frontend error handling keeps working.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** SQLSTATE for insufficient_privilege, which is what an RLS denial arrives as. */
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    public record ApiError(String error) {
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
        // Deliberately not logged at warn: failed logins are routine, and logging them at volume
        // with the attempted email is a privacy problem rather than a useful signal.
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(RbacException.class)
    public ResponseEntity<ApiError> handleRbac(RbacException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getMessage()));
    }

    /** A malformed or absent JSON body. Without this it would surface as a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ApiError("Malformed request body"));
    }

    /**
     * An RLS policy refusing a write.
     *
     * <p>This mapping is not obvious and is the reason the handler exists in this shape. SQLSTATE
     * 42501 falls inside Spring's BAD_SQL_GRAMMAR_CODES, so SQLStateSQLExceptionTranslator returns
     * BadSqlGrammarException - and that constructor is the one branch in the translator that drops
     * the underlying message. So an RLS denial arrives looking like a syntax error, with the reason
     * available only on the cause. Matching on the SQLSTATE is the only reliable test; the
     * exception type and message will both mislead.
     *
     * <p>Reported as 403 because it is a boundary being enforced, not a bug. Anything else with
     * that exception type genuinely is broken SQL and stays a 500.
     */
    @ExceptionHandler(BadSqlGrammarException.class)
    public ResponseEntity<ApiError> handleBadSqlGrammar(BadSqlGrammarException ex) {
        String sqlState = ex.getSQLException() == null ? null : ex.getSQLException().getSQLState();

        if (SQLSTATE_INSUFFICIENT_PRIVILEGE.equals(sqlState)) {
            log.warn("Row-level security refused a write: {}",
                    ex.getSQLException().getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiError("Not permitted"));
        }

        log.error("SQL error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("Internal server error"));
    }

    /** Last resort. Matches the legacy backend, which also returned a bare 500 with no detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("Internal server error"));
    }
}
