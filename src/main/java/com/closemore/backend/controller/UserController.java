package com.closemore.backend.controller;

import com.closemore.backend.dto.UserCreateRequest;
import com.closemore.backend.dto.UserResponse;
import com.closemore.backend.dto.UserUpdateRequest;
import com.closemore.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Users API - six endpoints, including the registration-approval workflow.
 *
 * <p>Path is {@code /api/v1/users}, following the versioning decision recorded in
 * docs/HANDOFF.md. Note that Migration Plan v5's inventory says {@code /api/users}; the divergence
 * is deliberate, applies to every resource group, and is listed as an open item for the frontend
 * and mobile teams.
 *
 * <p><b>Ordering of the mappings matters.</b> {@code /pending} is declared before
 * {@code /{userId}}... except there is no {@code GET /{userId}} in this controller, which removes
 * the ambiguity entirely. v5 does not inventory a single-user read and the project decision adds
 * one only to Contacts, Products and Pipelines. If one is ever added here, it must be declared
 * after {@code /pending} or Spring will route {@code /users/pending} to it with
 * {@code userId = "pending"}.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * GET /api/v1/users
     *
     * <p>Bare array by default, envelope when {@code page} or {@code size} is present - the project
     * decision, applied here even though v5 lists Users as a paginating endpoint. One convention
     * across twelve groups beats a faithful reproduction of the JS backend's inconsistency.
     *
     * <p>Default sort is two keys. {@code lastName} alone leaves ties in whatever order Postgres
     * returns them, which is free to differ between the query for page 1 and the query for page 2 -
     * so a user could appear on both pages or neither. {@code userId} makes the ordering total.
     */
    @GetMapping
    public Object list(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "role", required = false) String role,
            @RequestParam(name = "status", required = false) String status,
            @PageableDefault(size = 25,
                    sort = {"lastName", "userId"},
                    direction = Sort.Direction.ASC) Pageable pageable) {

        boolean paginationRequested = page != null || size != null;

        return paginationRequested
                ? userService.listPage(pageable, role, status)
                : userService.listAll(pageable.getSort(), role, status);
    }

    /**
     * GET /api/v1/users/pending - registrations awaiting approval. Admin only.
     *
     * <p>Always a bare array. This is an Admin's work queue, not a browsing surface, and it is
     * bounded by how many people have signed up since the last review.
     */
    @GetMapping("/pending")
    public List<UserResponse> pending() {
        return userService.listPending();
    }

    /** POST /api/v1/users - Admin only. */
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        UserResponse created = userService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * PUT /api/v1/users/{userId}
     *
     * <p>Own profile, or anyone if Admin - and email, role and status are Admin-only whoever owns
     * the row. See UserService.update.
     */
    @PutMapping("/{userId}")
    public UserResponse update(@PathVariable String userId,
                               @Valid @RequestBody UserUpdateRequest request) {
        return userService.update(userId, request);
    }

    /** POST /api/v1/users/{userId}/approve - Admin only. */
    @PostMapping("/{userId}/approve")
    public UserResponse approve(@PathVariable String userId) {
        return userService.approve(userId);
    }

    /**
     * POST /api/v1/users/{userId}/reject - Admin only.
     *
     * <p>204 and no body: the registration is gone, and returning the deleted row invites a client
     * to treat it as still present. Only ever applies to a Pending_Approval account - see
     * UserService.reject for why that restriction is load-bearing.
     */
    @PostMapping("/{userId}/reject")
    public ResponseEntity<Void> reject(@PathVariable String userId) {
        userService.reject(userId);
        return ResponseEntity.noContent().build();
    }
}
