package com.aegispay.auth.api;

import com.aegispay.auth.api.AuthDtos.ChangePasswordRequest;
import com.aegispay.auth.api.AuthDtos.InternalUserView;
import com.aegispay.auth.api.AuthDtos.SessionResponse;
import com.aegispay.auth.api.AuthDtos.UpdateProfileRequest;
import com.aegispay.auth.api.AuthDtos.UserProfileResponse;
import com.aegispay.auth.service.RequestContext;
import com.aegispay.auth.service.UserService;
import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User profile, session and administration endpoints.
 *
 * <p>Every user-scoped handler derives the subject from
 * {@link CurrentUser#requireUserId()} — the validated token — and never from a path
 * variable or request field. Accepting a client-supplied user ID is the classic
 * broken-object-level-authorisation flaw: it would let any authenticated user read
 * or modify anyone else's account by changing one value.
 */
@RestController
@Tag(name = "Users", description = "Profile, sessions and user administration")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // ------------------------------------------------------------------- self

    @GetMapping("/api/users/me")
    @Operation(summary = "Get the authenticated user's profile")
    public ResponseEntity<UserProfileResponse> me() {
        return ResponseEntity.ok(userService.profile(CurrentUser.requireUserId()));
    }

    @PatchMapping("/api/users/me")
    @Operation(summary = "Update the authenticated user's profile")
    public ResponseEntity<UserProfileResponse> updateMe(@Valid @RequestBody UpdateProfileRequest request,
                                                        HttpServletRequest httpRequest) {
        return ResponseEntity.ok(userService.updateProfile(
                CurrentUser.requireUserId(), request, RequestContext.from(httpRequest)));
    }

    @PostMapping("/api/users/me/change-password")
    @Operation(summary = "Change the authenticated user's password",
            description = """
                    Requires the current password. On success every existing session is
                    revoked, so an attacker holding a stolen session cannot keep using it
                    after the owner changes their password.""")
    @ApiResponse(responseCode = "204", description = "Password changed; all sessions revoked")
    @ApiResponse(responseCode = "401", description = "Current password incorrect",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest httpRequest) {
        userService.changePassword(CurrentUser.requireUserId(), request, RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/users/me/sessions")
    @Operation(summary = "List the authenticated user's active sessions",
            description = "Lets a user spot and revoke a session they do not recognise.")
    public ResponseEntity<List<SessionResponse>> sessions() {
        return ResponseEntity.ok(userService.activeSessions(CurrentUser.requireUserId()));
    }

    // --------------------------------------------------------------- internal

    @GetMapping("/internal/users/{userId}")
    @Operation(summary = "Identity view for service-to-service calls",
            description = """
                    Consumed by fraud-service (account age and KYC level are model
                    features), notification-service (delivery addresses) and
                    analytics-service.

                    Requires ROLE_SERVICE. Returns only the attributes those consumers
                    need — a phone number is not included because nothing downstream has
                    a reason to read it.""")
    @PreAuthorize("hasAuthority('" + Roles.ROLE_SERVICE + "')")
    public ResponseEntity<InternalUserView> internalUser(@PathVariable String userId) {
        return ResponseEntity.ok(userService.internalView(userId));
    }

    // ---------------------------------------------------------- administration

    @GetMapping("/api/admin/users")
    @Operation(summary = "Search users",
            description = "Admin and analyst only. Matches on email or full name.")
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public ResponseEntity<PageResponse<UserProfileResponse>> searchUsers(
            @RequestParam(required = false) @Size(max = 120) String query,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userService.searchUsers(query, pageable));
    }

    @GetMapping("/api/admin/users/{userId}")
    @Operation(summary = "Get any user's profile")
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public ResponseEntity<UserProfileResponse> adminGetUser(@PathVariable String userId) {
        return ResponseEntity.ok(userService.profile(userId));
    }

    @PostMapping("/api/admin/users/{userId}/roles/{role}")
    @Operation(summary = "Grant a role")
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public ResponseEntity<Void> grantRole(@PathVariable String userId,
                                          @PathVariable String role,
                                          HttpServletRequest httpRequest) {
        userService.grantRole(CurrentUser.requireUserId(), userId, role, RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/admin/users/{userId}/roles/{role}")
    @Operation(summary = "Revoke a role")
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public ResponseEntity<Void> revokeRole(@PathVariable String userId,
                                           @PathVariable String role,
                                           HttpServletRequest httpRequest) {
        userService.revokeRole(CurrentUser.requireUserId(), userId, role, RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/users/{userId}/disable")
    @Operation(summary = "Disable an account",
            description = "Also revokes every active session, so a compromised account "
                    + "cannot continue transacting after being disabled.")
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public ResponseEntity<Void> disableUser(@PathVariable String userId,
                                            @RequestParam @NotBlank @Size(max = 256) String reason,
                                            HttpServletRequest httpRequest) {
        userService.disableUser(CurrentUser.requireUserId(), userId, reason,
                RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/users/{userId}/enable")
    @Operation(summary = "Re-enable an account")
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public ResponseEntity<Void> enableUser(@PathVariable String userId,
                                           HttpServletRequest httpRequest) {
        userService.enableUser(CurrentUser.requireUserId(), userId, RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
