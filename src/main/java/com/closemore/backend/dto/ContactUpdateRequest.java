package com.closemore.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for PUT /api/v1/contacts/{id}.
 *
 * <p>A full replace, not a patch: PUT means the body is the new state of the resource, so every
 * required field must be present. A partial update would be PATCH, which the Next.js backend does not
 * expose and this port does not add.
 *
 * <p>Neither contactId nor ownerId appears here. The id comes from the path - accepting it in the
 * body too creates the question of what to do when they disagree, and every answer is a bug waiting
 * to happen. Ownership transfer is a distinct operation with its own authorisation question and is
 * not part of editing a contact's details.
 *
 * <p>createdDate is likewise absent: it records when the contact was first created and is not
 * editable.
 */
public record ContactUpdateRequest(
        @NotBlank(message = "firstName is required") @Size(max = 200) String firstName,
        @NotBlank(message = "lastName is required") @Size(max = 200) String lastName,
        @NotBlank(message = "email is required") @Email(message = "email must be a valid address")
        @Size(max = 320) String email,
        @NotBlank(message = "phonePrimary is required") @Size(max = 60) String phonePrimary,
        @NotBlank(message = "organizationName is required") @Size(max = 300) String organizationName,
        @NotBlank(message = "contactType is required") @Size(max = 80) String contactType,
        @NotBlank(message = "source is required") @Size(max = 120) String source,
        @Size(max = 2_000_000, message = "avatarDataUrl is too large") String avatarDataUrl,
        @Size(max = 2_000_000, message = "orgAvatarDataUrl is too large") String orgAvatarDataUrl
) {
}
