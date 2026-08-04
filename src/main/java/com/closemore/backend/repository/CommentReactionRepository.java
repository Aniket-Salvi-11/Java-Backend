package com.closemore.backend.repository;

import com.closemore.backend.domain.CommentReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for comment reactions. Visibility is three hops up, via the comment's
 * task - see CommentReactionEntity.
 */
public interface CommentReactionRepository extends JpaRepository<CommentReactionEntity, String> {

    List<CommentReactionEntity> findByCommentId(String commentId);

    /**
     * Every reaction across a set of comments, in one query.
     *
     * <p>The comment list endpoint rolls reactions up per comment. Fetching them per comment would
     * mean one query per comment on a screen that already issued one for the list - the classic N+1,
     * invisible at three comments and painful at forty.
     */
    List<CommentReactionEntity> findByCommentIdIn(Collection<String> commentIds);

    /**
     * The toggle lookup: has this user already reacted to this comment with this emoji?
     *
     * <p>All three parts are needed. Matching on comment and user alone would make a second emoji
     * replace the first rather than sit beside it, which is not what any chat client does.
     */
    Optional<CommentReactionEntity> findByCommentIdAndUserIdAndEmoji(String commentId,
                                                                    String userId,
                                                                    String emoji);
}
