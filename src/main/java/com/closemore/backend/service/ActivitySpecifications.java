package com.closemore.backend.service;

import com.closemore.backend.domain.ActivityEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Criteria fragments for the activity list endpoint: filter by parent, type and date range.
 *
 * <p>Each method returns a predicate or null, and null contributes nothing when combined - so an
 * absent filter costs no SQL rather than becoming a tautology the planner has to see through.
 *
 * <p><b>There is no visibility fragment here, and that is the difference from DealSpecifications.</b>
 * The activities policy authorises a row through four alternative routes - logger, Admin, Executive,
 * or owner of the parent deal or contact - and two of those require joining to a table whose own
 * policy is already filtering. Reproducing that in Criteria would be a large amount of code whose
 * only job is to agree with the database, and any drift between the two would narrow what a user can
 * see with no error anywhere. RLS is left to do it alone; the service still checks authorship before
 * a write, which is the half that matters for defence in depth.
 */
public final class ActivitySpecifications {

    private ActivitySpecifications() {
    }

    /**
     * Both halves of the parent discriminator, or neither.
     *
     * <p>Filtering on the id alone would be a bug rather than a convenience: ids come from different
     * tables with no foreign key between them, so a contact id and a deal id can collide and the
     * caller would get somebody else's history mixed into theirs.
     */
    public static Specification<ActivityEntity> hasParent(String parentType, String parentId) {
        if (blank(parentType) || blank(parentId)) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("parentObjectType"), parentType),
                cb.equal(root.get("parentObjectId"), parentId));
    }

    public static Specification<ActivityEntity> hasType(String activityType) {
        return blank(activityType)
                ? null
                : (root, query, cb) -> cb.equal(root.get("activityType"), activityType);
    }

    /**
     * Date range on {@code Log_Date}.
     *
     * <p><b>That column is TEXT, not a date.</b> These comparisons are therefore lexicographic, and
     * they give the right answer only because the values are ISO-8601 ({@code 2026-02-01}), where
     * string order and chronological order coincide. Any row written in another format - a
     * {@code 01/02/2026} arriving from an import, say - will sort and filter nonsensically without
     * failing. Changing the column type is a schema change and out of scope for a like-for-like
     * port; this comment is here so the constraint is not rediscovered as a bug.
     */
    public static Specification<ActivityEntity> loggedFrom(String fromDate) {
        return blank(fromDate)
                ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("logDate"), fromDate);
    }

    /** Inclusive, matching the half-open-is-surprising principle: a caller naming a date expects it included. */
    public static Specification<ActivityEntity> loggedTo(String toDate) {
        return blank(toDate)
                ? null
                : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("logDate"), toDate);
    }

    /**
     * Combines the fragments, skipping the nulls.
     *
     * <p>Written out rather than chained with {@code Specification.where(...).and(...)} because that
     * chain returns a specification whose {@code toPredicate} can itself return null when every part
     * is absent, and Spring Data treats a null specification and a specification-returning-null
     * differently depending on the call site. An explicit conjunction of whatever survived removes
     * the question.
     */
    @SafeVarargs
    public static Specification<ActivityEntity> allOf(Specification<ActivityEntity>... parts) {
        return (root, query, cb) -> {
            Predicate combined = cb.conjunction();
            for (Specification<ActivityEntity> part : parts) {
                if (part == null) {
                    continue;
                }
                Predicate predicate = part.toPredicate(root, query, cb);
                if (predicate != null) {
                    combined = cb.and(combined, predicate);
                }
            }
            return combined;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
