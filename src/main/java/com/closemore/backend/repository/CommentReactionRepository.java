package com.closemore.backend.repository;

import com.closemore.backend.domain.CommentReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for comment reactions. Visibility is three hops up, via the comment's
 * task - see CommentReactionEntity.
 */
public interface CommentReactionRepository extends JpaRepository<CommentReactionEntity, String> {

    List<CommentReactionEntity> findByCommentId(String commentId);
}
