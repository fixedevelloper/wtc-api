package com.wtc.identity.application;

import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.PasswordPolicy;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.User;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.audit.AuditTrail.ActorType;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crée le tout premier super-administrateur d'un environnement neuf (sans lui, personne ne pourrait
 * créer de comptes du personnel). N'a aucun effet dès qu'un super-administrateur existe, quel que
 * soit son statut : ce n'est pas une porte dérobée de réinitialisation. Le compte devra activer son
 * second facteur dès la première connexion.
 */
@Service
public class SuperAdminBootstrap {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final AuditTrail audit;

    SuperAdminBootstrap(UserRepository users, PasswordHasher hasher, AuditTrail audit) {
        this.users = users;
        this.hasher = hasher;
        this.audit = audit;
    }

    /**
     * @return vrai si le compte a été créé, faux si un super-administrateur existait déjà
     */
    @Transactional
    public boolean ensureSuperAdmin(String rawEmail, String password) {
        if (users.anyWithRole(Role.SUPER_ADMIN)) {
            return false;
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        PasswordPolicy.requireStrong(password, email, "password");
        long id =
                users.insert(
                        new UserRepository.NewUser(
                                email,
                                hasher.hash(password),
                                "Super-administrateur",
                                null,
                                Set.of(Role.SUPER_ADMIN)));
        User created = users.findById(id).orElseThrow();
        audit.record(
                null,
                "bootstrap",
                ActorType.SYSTEM,
                "SUPER_ADMIN_BOOTSTRAPPED",
                "USER",
                created.publicId().toString(),
                null,
                null);
        return true;
    }
}
