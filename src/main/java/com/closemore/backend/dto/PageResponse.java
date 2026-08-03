package com.closemore.backend.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The list envelope, returned only when a caller asks for a page.
 *
 * <p><b>This is opt-in, not the default.</b> A list endpoint called without {@code ?page=} returns a
 * bare JSON array, exactly as the Next.js backend does today. Supplying {@code ?page=} switches the
 * response to this envelope. That asymmetry is deliberate and was a project decision, not a
 * convenience: Section 3 of the migration plan commits to not changing response shapes, and a client
 * that receives an object where it expected an array does not get an error - it gets an empty screen
 * with nothing wrong on the server side. The mobile app makes that worse, since a bad release there
 * is gated by store review.
 *
 * <p>So clients adopt pagination per screen, on their own schedule, by adding a query parameter.
 * Nothing breaks on the day this ships.
 *
 * <p><b>Why not return Spring Data's {@code Page} directly.</b> {@code PageImpl} has no serialisation
 * contract - Boot logs a warning saying so - and its JSON has changed shape between versions. This
 * record is ours, so it changes when we decide it does.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    /** Maps a repository Page of entities into a response page of DTOs. Entities are never serialised. */
    public static <E, D> PageResponse<D> of(Page<E> page, Function<E, D> toDto) {
        return new PageResponse<>(
                page.getContent().stream().map(toDto).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
