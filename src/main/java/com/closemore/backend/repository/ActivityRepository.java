package com.closemore.backend.repository;

import com.closemore.backend.domain.ActivityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for activities.
 *
 * <p>findByParentObjectTypeAndParentObjectId is the natural timeline query - "everything logged
 * against this deal" - and is the shape the polymorphic parent forces: both halves of the
 * discriminator are needed, because a Deal id and a Contact id could theoretically collide.
 *
 * <p>Standard tenancy caveat: RLS filters these, not the queries themselves.
 */
public interface ActivityRepository extends JpaRepository<ActivityEntity, String> {

    List<ActivityEntity> findByParentObjectTypeAndParentObjectId(String parentObjectType,
                                                                 String parentObjectId);

    List<ActivityEntity> findByLoggedByUserId(String loggedByUserId);
}
