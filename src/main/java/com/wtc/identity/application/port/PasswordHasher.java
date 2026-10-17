package com.wtc.identity.application.port;

public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);

    /**
     * Hachage hérité (bcrypt migré) : à remplacer par Argon2id à la prochaine connexion réussie.
     */
    boolean needsRehash(String storedHash);

    /**
     * Fait le même travail qu'une vérification réelle, pour ne pas distinguer « compte inconnu »
     * par le temps de réponse.
     */
    void burnTime(String rawPassword);
}
