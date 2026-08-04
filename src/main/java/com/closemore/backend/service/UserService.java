package com.closemore.backend.service;

import com.closemore.backend.domain.UserEntity;
import com.closemore.backend.dto.UserCreateRequest;
import com.closemore.backend.dto.UserResponse;
import com.closemore.backend.dto.UserUpdateRequest;
import com.closemore.backend.dto.PageResponse;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacException;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.rbac.Role;
import com.closemore.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The Users API - six endpoints, including the registration-approval workflow.
 *
 * <p><b>Class-level {@code @Transactional} is load-bearing.</b> Same reason as ContactService:
 * TenantContextAspect sets the RLS session variables inside the transaction, so a repository call
 * from a non-transactional context sees no tenant and RLS denies everything.
 *
 * <p><b>Tenant scoping is entirely RLS's job here.</b> Unlike contacts and deals there is no owner
 * column to filter on - a user's tenant IS {@code Organization_Name}, which V11's policy already
 * matches against {@code app.current_user_tenant}. So {@code findAll} genuinely means "everyone in
 * my organisation", and there is deliberately no ownership predicate in this class.
 *
 * <p><b>On {@code @Generated} and gotcha 17.</b> UserEntity maps Created_At and Updated_At with
 * {@code @Generated}, so Hibernate appends RETURNING to both its insert and its update. That is
 * only safe because the users policy USING clause is organisation-wide, so a row written here is
 * readable by its writer. It is safe for exactly as long as {@code create()} refuses to take an
 * organisation from the request - which is why it does.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private static final String OBJECT_TYPE = "User";

    private static final String STATUS_ACTIVE = "Active";
    private static final String STATUS_INACTIVE = "Inactive";
    private static final String STATUS_PENDING = "Pending_Approval";

    private static final Set<String> VALID_STATUSES =
            Set.of(STATUS_ACTIVE, STATUS_INACTIVE, STATUS_PENDING);

    /**
     * Sortable columns. Avatar_Data_URL is deliberately absent: it holds an inline base64 image,
     * and an ORDER BY on it would make Postgres collate megabytes per row. Password material is
     * absent for the obvious reason.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "userId", "firstName", "lastName", "email", "role", "status",
            "organizationName", "createdAt", "updatedAt");

    private final UserRepository userRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    // --- reads ------------------------------------------------------------------------------

    /** GET /api/v1/users - bare array, the default shape. */
    public List<UserResponse> listAll(Sort sort, String role, String status) {
        currentUserService.require();
        requireSortablePropertiesOnly(sort);

        // findAll(Sort), not findAll(Pageable.unpaged(sort)) - the latter silently drops the Sort.
        // See UserRepository and the fix in commit 89cbc6b.
        return userRepository.findAll(sort).stream()
                .filter(user -> matchesFilters(user, role, status))
                .map(DtoMapper::toUserResponse)
                .toList();
    }

    /** GET /api/v1/users?page=... - the opt-in paginated form. */
    public PageResponse<UserResponse> listPage(Pageable pageable, String role, String status) {
        currentUserService.require();
        requireSortablePropertiesOnly(pageable.getSort());

        // Filtering in the database rather than after the fact, because a post-filter on a page
        // returns fewer than `size` rows and makes totalElements a lie.
        if (role != null && status != null) {
            return PageResponse.of(
                    userRepository.findByRoleAndStatus(role, status, pageable),
                    DtoMapper::toUserResponse);
        }
        if (role != null) {
            return PageResponse.of(
                    userRepository.findByRole(role, pageable), DtoMapper::toUserResponse);
        }
        if (status != null) {
            return PageResponse.of(
                    userRepository.findByStatus(status, pageable), DtoMapper::toUserResponse);
        }
        return PageResponse.of(userRepository.findAll(pageable), DtoMapper::toUserResponse);
    }

    /**
     * GET /api/v1/users/pending - registrations awaiting approval. Admin only.
     *
     * <p>Its own endpoint rather than {@code ?status=Pending_Approval} because v5 inventories it as
     * one, and because the two differ in authorisation: the list is visible to any authenticated
     * user, this is not.
     */
    public List<UserResponse> listPending() {
        AuthenticatedUser user = currentUserService.require();
        rbacService.requireRole(user, Role.ADMIN);

        return userRepository.findByStatus(STATUS_PENDING, Sort.by("createdAt")).stream()
                .map(DtoMapper::toUserResponse)
                .toList();
    }

    // --- writes -----------------------------------------------------------------------------

    /** POST /api/v1/users - Admin creates a user directly. */
    public UserResponse create(UserCreateRequest request) {
        AuthenticatedUser actor = currentUserService.require();
        rbacService.requireRole(actor, Role.ADMIN);

        String role = requireValidRole(request.role());
        String status = request.status() == null ? STATUS_ACTIVE : requireValidStatus(request.status());

        UserEntity user = new UserEntity();
        user.setUserId(UUID.randomUUID().toString());
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email().toLowerCase(Locale.ROOT));
        user.setRole(role);
        user.setStatus(status);
        // From the caller's context, never the body. See UserCreateRequest.
        user.setOrganizationName(currentUserService.currentTenant());
        user.setPhoneNumber(request.phoneNumber());
        user.setResidentialAddress(request.residentialAddress());
        user.setOfficeAddress(request.officeAddress());

        UserEntity saved = userRepository.save(user);
        UserResponse response = DtoMapper.toUserResponse(saved);
        auditService.logCreate(actor, OBJECT_TYPE, saved.getUserId(), displayName(saved), response);
        return response;
    }

    /**
     * PUT /api/v1/users/{userId}.
     *
     * <p>Two privilege levels in one endpoint - see UserUpdateRequest. Anyone may edit their own
     * profile; email, role and status are Admin-only regardless of whose row it is, including the
     * Admin's own. That last part matters: without it, "only Admin can change role" would still let
     * a Sales_Rep promote themselves, since they own the row.
     */
    public UserResponse update(String userId, UserUpdateRequest request) {
        AuthenticatedUser actor = currentUserService.require();
        UserEntity user = load(userId);

        boolean isAdmin = actor.role() == Role.ADMIN;
        boolean isSelf = actor.userId().equals(user.getUserId());
        if (!isAdmin && !isSelf) {
            throw new RbacException(403, "You may only update your own profile");
        }

        boolean touchesPrivilegedFields =
                request.email() != null || request.role() != null || request.status() != null;
        if (touchesPrivilegedFields && !isAdmin) {
            throw new RbacException(403, "Only an Admin can change email, role or status");
        }

        UserResponse before = DtoMapper.toUserResponse(user);

        if (request.firstName() != null) {
            user.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName());
        }
        if (request.email() != null) {
            user.setEmail(request.email().toLowerCase(Locale.ROOT));
        }
        if (request.role() != null) {
            user.setRole(requireValidRole(request.role()));
        }
        if (request.status() != null) {
            user.setStatus(requireValidStatus(request.status()));
        }
        if (request.phoneNumber() != null) {
            user.setPhoneNumber(request.phoneNumber());
        }
        if (request.avatarDataUrl() != null) {
            user.setAvatarDataUrl(request.avatarDataUrl());
        }
        if (request.residentialAddress() != null) {
            user.setResidentialAddress(request.residentialAddress());
        }
        if (request.officeAddress() != null) {
            user.setOfficeAddress(request.officeAddress());
        }

        UserEntity saved = userRepository.save(user);
        UserResponse after = DtoMapper.toUserResponse(saved);
        auditService.logUpdate(actor, OBJECT_TYPE, userId, displayName(saved), before, after);
        return after;
    }

    /**
     * POST /api/v1/users/{userId}/approve - activate a pending registration. Admin only.
     *
     * <p>Refuses anything not in Pending_Approval. Approving an already-Active user is a no-op that
     * hides a mistake; approving an Inactive one would be a reactivation route, which v5 does not
     * inventory.
     */
    public UserResponse approve(String userId) {
        AuthenticatedUser actor = currentUserService.require();
        rbacService.requireRole(actor, Role.ADMIN);

        UserEntity user = load(userId);
        requirePending(user, "approved");

        UserResponse before = DtoMapper.toUserResponse(user);
        user.setStatus(STATUS_ACTIVE);
        UserEntity saved = userRepository.save(user);

        UserResponse after = DtoMapper.toUserResponse(saved);
        auditService.logUpdate(actor, OBJECT_TYPE, userId, displayName(saved), before, after);
        return after;
    }

    /**
     * POST /api/v1/users/{userId}/reject - delete a pending registration. Admin only.
     *
     * <p><b>The Pending_Approval check is what stops this becoming a delete-user endpoint.</b> v5
     * is explicit that no route removes an established user, and equally explicit that reject
     * deletes. Both hold only if this refuses every other status - otherwise the one route v5 says
     * does not exist is this one, reachable with any user id.
     *
     * <p>A pending user owns nothing, so there are no ON DELETE RESTRICT foreign keys to trip. That
     * is not true of an established user, which is the other reason this must stay narrow.
     */
    public void reject(String userId) {
        AuthenticatedUser actor = currentUserService.require();
        rbacService.requireRole(actor, Role.ADMIN);

        UserEntity user = load(userId);
        requirePending(user, "rejected");

        UserResponse before = DtoMapper.toUserResponse(user);
        String name = displayName(user);
        userRepository.delete(user);
        auditService.logDelete(actor, OBJECT_TYPE, userId, name, before);
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Loads a user, or 404.
     *
     * <p>404 rather than 403 for a user in another organisation: RLS makes the row invisible, so
     * this cannot tell the two apart - and that is the desired answer anyway. Distinguishing them
     * would confirm the existence of accounts in other tenants.
     */
    private UserEntity load(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " not found"));
    }

    private void requirePending(UserEntity user, String verb) {
        if (!STATUS_PENDING.equals(user.getStatus())) {
            throw new BadRequestException(
                    "Only a registration awaiting approval can be " + verb
                            + "; this account is " + user.getStatus());
        }
    }

    private static boolean matchesFilters(UserEntity user, String role, String status) {
        return (role == null || role.equals(user.getRole()))
                && (status == null || status.equals(user.getStatus()));
    }

    private static String requireValidRole(String role) {
        try {
            return Role.fromDbValue(role).dbValue();
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Unknown role '" + role + "'");
        }
    }

    private static String requireValidStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new BadRequestException("Unknown status '" + status + "'");
        }
        return status;
    }

    private static String displayName(UserEntity user) {
        return user.getFirstName() + " " + user.getLastName();
    }

    private static void requireSortablePropertiesOnly(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'");
            }
        }
    }
}
