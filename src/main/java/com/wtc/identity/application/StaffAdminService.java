package com.wtc.identity.application;

import com.wtc.identity.application.port.AuthNotifier;
import com.wtc.identity.application.port.OneTimeTokenStore;
import com.wtc.identity.application.port.OneTimeTokenStore.Purpose;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.RefreshTokenStore;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.User;
import com.wtc.identity.domain.UserStatus;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.audit.AuditTrail.ActorType;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.security.Tokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gestion des comptes du personnel (permission {@code USER_MANAGE}).
 *
 * <p>Garde-fous : on ne se modifie pas soi-même ; seul un super-administrateur accorde ou retire
 * les rôles d'administration ; il reste toujours au moins un super-administrateur actif ; toute
 * modification est auditée et ferme les sessions du compte concerné.
 */
@Service
public class StaffAdminService {

    private static final Duration INVITATION_TTL = Duration.ofDays(3);
    private static final Set<Role> PRIVILEGED = EnumSet.of(Role.ADMIN, Role.SUPER_ADMIN);

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final OneTimeTokenStore oneTimeTokens;
    private final PasswordHasher hasher;
    private final AuthNotifier notifier;
    private final AuditTrail audit;
    private final Clock clock;
    private final JsonMapper json;

    StaffAdminService(
            UserRepository users,
            RefreshTokenStore refreshTokens,
            OneTimeTokenStore oneTimeTokens,
            PasswordHasher hasher,
            AuthNotifier notifier,
            AuditTrail audit,
            Clock clock,
            JsonMapper json) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.oneTimeTokens = oneTimeTokens;
        this.hasher = hasher;
        this.notifier = notifier;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    public record Page(List<User> content, long total) {
        public Page {
            content = List.copyOf(content);
        }
    }

    public record Update(String displayName, Set<Role> roles, UserStatus status) {
        public Update {
            roles = roles == null ? null : Set.copyOf(roles);
        }
    }

    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional(readOnly = true)
    public Page list(String search, int page, int size, String sortField, boolean ascending) {
        String term = search == null || search.isBlank() ? null : search.trim();
        UserRepository.UserPage result = users.searchStaff(term, page, size, sortField, ascending);
        return new Page(result.content(), result.total());
    }

    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public User create(UUID actorId, String email, String displayName, Set<Role> roles) {
        User actor = actor(actorId);
        Set<Role> staffRoles = requireStaffRoles(roles);
        requirePrivilegeToGrant(actor, staffRoles);
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.emailExists(normalized)) {
            throw new DomainException(
                    ErrorCode.EMAIL_ALREADY_REGISTERED, "Cette adresse e-mail est déjà utilisée.");
        }
        long id;
        try {
            // Mot de passe aléatoire que personne ne connaît : le compte ne s'ouvre qu'après le
            // lien d'invitation.
            id =
                    users.insert(
                            new UserRepository.NewUser(
                                    normalized,
                                    hasher.hash(Tokens.random()),
                                    displayName.trim(),
                                    null,
                                    staffRoles));
        } catch (DuplicateKeyException race) {
            throw new DomainException(
                    ErrorCode.EMAIL_ALREADY_REGISTERED, "Cette adresse e-mail est déjà utilisée.");
        }
        User created = users.findById(id).orElseThrow();
        Instant now = clock.instant();
        String raw = Tokens.random();
        Instant expiresAt = now.plus(INVITATION_TTL);
        oneTimeTokens.create(id, Purpose.PASSWORD_RESET, Tokens.sha256(raw), expiresAt);
        notifier.staffInvited(created, raw, expiresAt);
        audit.record(
                actor.id(),
                actor.email(),
                ActorType.USER,
                "STAFF_CREATED",
                "USER",
                created.publicId().toString(),
                null,
                snapshot(created));
        return created;
    }

    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public User update(UUID actorId, UUID targetId, Update change) {
        User actor = actor(actorId);
        User target = staffTarget(actor, targetId);
        String before = snapshot(target);
        Instant now = clock.instant();

        Set<Role> newRoles =
                change.roles() == null ? target.roles() : requireStaffRoles(change.roles());
        UserStatus newStatus = change.status() == null ? target.status() : change.status();
        if (change.roles() != null) {
            // Accorder ou retirer un rôle d'administration exige d'être super-administrateur.
            Set<Role> changed = EnumSet.noneOf(Role.class);
            changed.addAll(target.roles());
            changed.addAll(newRoles);
            Set<Role> retained = EnumSet.noneOf(Role.class);
            retained.addAll(target.roles());
            retained.retainAll(newRoles);
            changed.removeAll(retained);
            requirePrivilegeToGrant(actor, changed);
        }
        boolean losesSuperAdmin =
                target.roles().contains(Role.SUPER_ADMIN)
                        && target.status() == UserStatus.ACTIVE
                        && (!newRoles.contains(Role.SUPER_ADMIN) || newStatus != UserStatus.ACTIVE);
        if (losesSuperAdmin && users.lockAndCountActiveSuperAdmins() <= 1) {
            throw new DomainException(
                    ErrorCode.LAST_SUPER_ADMIN,
                    "Il doit rester au moins un super-administrateur actif.");
        }

        if (change.displayName() != null) {
            users.setDisplayName(target.id(), change.displayName().trim());
        }
        if (change.roles() != null) {
            users.replaceRoles(target.id(), newRoles);
        }
        if (change.status() != null) {
            users.setStatus(target.id(), newStatus);
        }
        if (change.roles() != null || newStatus != UserStatus.ACTIVE) {
            refreshTokens.revokeAllOfUser(target.id(), now);
        }
        User updated = users.findById(target.id()).orElseThrow();
        audit.record(
                actor.id(),
                actor.email(),
                ActorType.USER,
                "STAFF_UPDATED",
                "USER",
                target.publicId().toString(),
                before,
                snapshot(updated));
        return updated;
    }

    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public void resetMfa(UUID actorId, UUID targetId) {
        User actor = actor(actorId);
        User target = staffTarget(actor, targetId);
        users.clearMfa(target.id());
        refreshTokens.revokeAllOfUser(target.id(), clock.instant());
        audit.record(
                actor.id(),
                actor.email(),
                ActorType.USER,
                "MFA_RESET",
                "USER",
                target.publicId().toString(),
                null,
                null);
    }

    // ------------------------------------------------------------------ règles

    private User staffTarget(User actor, UUID targetId) {
        if (actor.publicId().equals(targetId)) {
            throw new DomainException(
                    ErrorCode.SELF_MODIFICATION_FORBIDDEN,
                    "Vous ne pouvez pas modifier votre propre accès.");
        }
        User target =
                users.findByPublicId(targetId)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.USER_NOT_FOUND,
                                                "Utilisateur introuvable."));
        if (!target.isStaff()) {
            throw new DomainException(
                    ErrorCode.USER_NOT_FOUND,
                    "Utilisateur introuvable."); // les clients ne se gèrent pas ici
        }
        if (target.roles().stream().anyMatch(PRIVILEGED::contains)
                && !actor.roles().contains(Role.SUPER_ADMIN)) {
            throw new DomainException(
                    ErrorCode.ACCESS_DENIED,
                    "Seul un super-administrateur gère les administrateurs.");
        }
        return target;
    }

    private static void requirePrivilegeToGrant(User actor, Set<Role> roles) {
        if (roles.stream().anyMatch(PRIVILEGED::contains)
                && !actor.roles().contains(Role.SUPER_ADMIN)) {
            throw new DomainException(
                    ErrorCode.ACCESS_DENIED,
                    "Seul un super-administrateur accorde les rôles d'administration.");
        }
    }

    private static Set<Role> requireStaffRoles(Set<Role> roles) {
        Set<Role> staff = EnumSet.noneOf(Role.class);
        roles.stream().filter(Role::isStaff).forEach(staff::add);
        if (staff.isEmpty()) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Au moins un rôle du personnel est requis.",
                    List.of(
                            new FieldViolation(
                                    "roles",
                                    "StaffRoleRequired",
                                    "Choisissez SUPPORT, ADMIN ou SUPER_ADMIN.")));
        }
        return staff;
    }

    /**
     * L'auteur doit exister <b>et être actif</b> : un compte suspendu garde un jeton d'accès
     * valable jusqu'à son expiration (au plus 15 minutes), il ne doit pas pouvoir agir sur les
     * autres comptes pendant ce délai.
     */
    private User actor(UUID actorId) {
        User actor =
                users.findByPublicId(actorId)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.UNAUTHENTICATED, "Compte introuvable."));
        if (actor.status() != UserStatus.ACTIVE) {
            throw new DomainException(ErrorCode.ACCESS_DENIED, "Ce compte n'est pas actif.");
        }
        return actor;
    }

    private String snapshot(User user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("email", user.email());
        view.put("displayName", user.displayName());
        view.put("status", user.status().name());
        view.put("roles", user.roles().stream().map(Role::name).sorted().toList());
        view.put("mfaEnabled", user.mfaEnabled());
        return json.writeValueAsString(view);
    }
}
