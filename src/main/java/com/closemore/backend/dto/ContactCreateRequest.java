package com.closemore.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for POST /api/v1/contacts.
 *
 * <p>A separate type from {@link ContactResponse} on purpose. Binding straight onto the entity lets
 * a caller set any column it happens to have a setter for - including Owner_ID, Created_At and the
 * primary key. This record names exactly the fields a client may supply, so the rest cannot be
 * reached however the request is shaped.
 *
 * <p><b>ownerId is nullable and normally ignored.</b> It defaults to the authenticated user, and only
 * an Admin may set it to someone else. A Sales_Rep who supplies another user's id is silently given
 * their own - not an error, because RLS would refuse the insert anyway and a 403 here would leak
 * which user ids exist.
 *
 * <p>contactId is absent by design: the server generates it. Letting a client choose a primary key
 * invites collisions and lets one tenant probe another's id space by watching which inserts fail.
 *
 * <p>The length caps are not schema constraints - the columns are unbounded TEXT. They are here
 * because Avatar_Data_URL holds base64 images inline, and without a ceiling one request can store a
 * row large enough to make every later list response slow. See the migration plan's avatar item.
 */
public record ContactCreateRequest(
        @NotBlank(message = "firstName is required") @Size(max = 200) String firstName,
        @NotBlank(message = "lastName is required") @Size(max = 200) String lastName,
        @NotBlank(message = "email is required") @Email(message = "email must be a valid address")
        @Size(max = 320) String email,
        @NotBlank(message = "phonePrimary is required") @Size(max = 60) String phonePrimary,
        @NotBlank(message = "organizationName is required") @Size(max = 300) String organizationName,
        @NotBlank(message = "contactType is required") @Size(max = 80) String contactType,
        @NotBlank(message = "source is required") @Size(max = 120) String source,
        @Size(max = 40) String createdDate,
        String ownerId,
        @Size(max = 2_000_000, message = "avatarDataUrl is too large") String avatarDataUrl,
        @Size(max = 2_000_000, message = "orgAvatarDataUrl is too large") String orgAvatarDataUrl
) {
}
