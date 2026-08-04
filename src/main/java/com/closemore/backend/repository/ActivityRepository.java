package com.closemore.backend.repository;

import com.closemore.backend.domain.ActivityEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * Spring Data JPA repository for activities (notes, calls, meetings).
 *
 * <p>Tenancy comes from RLS plus the session variables set per transaction, not from anything here.
 * The activities policy is the widest in the schema: an activity is visible to its logger, to
 * Admin/Executive, and to the owner of the deal or contact it hangs off. Note what is NOT in that
 * list - deal team membership. A team member can open a deal and see none of its activity history.
 * That is the original's behaviour, pinned by a test rather than fixed.
 *
 * <p><b>JpaSpecificationExecutor because the list endpoint filters on parent, type and a date
 * range</b>, any of which may be absent. As derived methods that is eight combinations; as JPQL with
 * ":param IS NULL" guards it depends on Hibernate inferring the type of a null parameter, which it
 * does not always do. See ActivitySpecifications.
 *
 * <p>findByParentObjectTypeAndParentObjectId is kept because the deal detail and story endpoints
 * want exactly that query and nothing else - a specification there would be ceremony. Both halves of
 * the discriminator are needed: ids from different tables could collide, and there is no foreign key
 * to stop them.
 *
 * <p>The Sort overload exists for the same reason as EventLogRepository's: a list read without an
 * explicit ORDER BY comes back in whatever order Postgres finds convenient, which is stable enough
 * in testing to look correct and free to change under load.
 */
public interface ActivityRepository
        extends JpaRepository<ActivityEntity, String>, JpaSpecificationExecutor<ActivityEntity> {

    List<ActivityEntity> findByParentObjectTypeAndParentObjectId(String parentObjectType,
                                                                 String parentObjectId);

    List<ActivityEntity> findByParentObjectTypeAndParentObjectId(String parentObjectType,
                                                                 String parentObjectId,
                                                                 Sort sort);

    List<ActivityEntity> findByLoggedByUserId(String loggedByUserId);
}