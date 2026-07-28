package com.closemore.backend.mapper;

import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.domain.UserEntity;
import com.closemore.backend.dto.ContactResponse;
import com.closemore.backend.dto.UserResponse;

/**
 * Hand-written entity -> DTO mapping. Plain static methods rather than MapStruct/ModelMapper on
 * purpose for Phase 1: two entities, no reflection or annotation processing to reason about, and
 * the Password-drop on users is visible right here in code rather than hidden in a generated class.
 * Revisit if the mapping surface grows large enough in Phase 3 to justify a library.
 */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static UserResponse toUserResponse(UserEntity e) {
        // Password intentionally not copied - it has no field on UserResponse.
        return new UserResponse(
                e.getUserId(),
                e.getFirstName(),
                e.getLastName(),
                e.getEmail(),
                e.getRole(),
                e.getStatus(),
                e.getPhoneNumber(),
                e.getOrganizationName(),
                e.getResidentialAddress(),
                e.getOfficeAddress(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    public static ContactResponse toContactResponse(ContactEntity e) {
        return new ContactResponse(
                e.getContactId(),
                e.getFirstName(),
                e.getLastName(),
                e.getEmail(),
                e.getPhonePrimary(),
                e.getOrganizationName(),
                e.getContactType(),
                e.getSource(),
                e.getCreatedDate(),
                e.getOwnerId(),
                e.getAvatarDataUrl(),
                e.getOrgAvatarDataUrl(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
