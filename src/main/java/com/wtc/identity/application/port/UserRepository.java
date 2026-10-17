package com.wtc.identity.application.port;

import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.User;
import com.wtc.identity.domain.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserRepository {

    record NewUser(
            String email, String passwordHash, String displayName, String phone, Set<Role> roles) {
        public NewUser {
            roles = Set.copyOf(roles);
        }
    }

    /** Page de résultats pour l'administration. */
    record UserPage(List<User> content, long total) {
        public UserPage {
            content = List.copyOf(content);
        }
    }

    Optional<User> findByEmail(String email);

    Optional<User> findByPublicId(UUID publicId);

    Optional<User> findById(long id);

    boolean emailExists(String email);

    boolean phoneExists(String phone);

    long insert(NewUser user);

    /** Au moins un compte (quel que soit son statut) porte-t-il ce rôle ? */
    boolean anyWithRole(Role role);

    /**
     * Incrémente le compteur d'échecs ; verrouille le compte au seuil. Retourne l'état après mise à
     * jour.
     */
    User recordFailedLogin(long userId, int maxAttempts, Duration lockDuration, Instant now);

    void recordSuccessfulLogin(long userId, Instant now);

    /**
     * Lève le verrouillage et remet le compteur d'échecs à zéro (sans toucher à la dernière
     * connexion).
     */
    void clearLock(long userId);

    void updatePasswordHash(long userId, String hash, Instant now);

    void setDisplayName(long userId, String displayName);

    void setStatus(long userId, UserStatus status);

    void setPhotoKey(long userId, String key);

    /** Aligne le nom d'affichage et le téléphone sur la fiche client. */
    void updateContact(long userId, String displayName, String phone);

    boolean phoneUsedByAnother(String phone, long userId);

    void replaceRoles(long userId, Set<Role> roles);

    Set<String> permissionsOf(Set<Role> roles);

    // Second facteur
    void savePendingMfaSecret(long userId, byte[] encryptedSecret);

    Optional<byte[]> findMfaSecret(long userId);

    void enableMfa(long userId);

    void clearMfa(long userId);

    /**
     * Mémorise le pas TOTP de façon atomique ; faux si ce pas (ou un plus récent) a déjà été
     * utilisé.
     */
    boolean claimMfaStep(long userId, long step);

    UserPage searchStaff(String search, int page, int size, String sortField, boolean ascending);

    /**
     * Compte les super-administrateurs actifs en verrouillant leurs lignes jusqu'à la fin de la
     * transaction : deux modifications simultanées se sérialisent, la seconde voit le résultat de
     * la première.
     */
    long lockAndCountActiveSuperAdmins();
}
