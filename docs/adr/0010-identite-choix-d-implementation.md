# ADR 0010 — Identité : choix d'implémentation

- **Statut** : Accepté (mis en œuvre en phase 2) — précise l'[ADR 0006](0006-authentification-et-autorisation.md)
- **Date** : 2026-10-16

## Décisions

| Sujet | Choix | Raison |
|---|---|---|
| Hachage des mots de passe | **Argon2id** (16 Mio, 2 itérations, 1 thread). Les hachages bcrypt `$2y$` de Symfony restent acceptés et sont **remplacés à la première connexion réussie** | Les 100 % de hachages hérités sont bcrypt : aucune réinitialisation de masse n'est nécessaire à la bascule |
| Politique de mot de passe | NIST 800-63B : 10 à 128 caractères, refus des mots de passe courants, de l'adresse e-mail et des répétitions ; **aucune règle de composition** | La longueur protège mieux que « une majuscule et un chiffre » |
| Jeton d'accès | JWT **RS256**, 15 minutes, claims `roles`, `permissions`, `sid` (session) ; `kid` = empreinte de la clé | L'API vérifie sans appeler l'identité ; la clé publique suffit pour d'autres instances |
| Refresh token | Opaque (256 bits), **empreinte SHA-256 seule** en base, **rotation à chaque usage**, famille par session. Un jeton déjà échangé qui reparaît **révoque toute la famille** et laisse une alerte d'audit. Âge maximal d'une session : 90 jours | Un jeton volé ne survit pas à son premier usage légitime |
| Défi du second facteur | Jeton opaque à usage unique (5 minutes, 5 essais, persistants) et **non un JWT** | Ne peut pas être présenté comme jeton d'accès par erreur |
| TOTP | RFC 6238 (SHA-1, 6 chiffres, pas de 30 s, tolérance ±1). **Anti-rejeu** : le dernier pas accepté est mémorisé par utilisateur et mis à jour par une instruction conditionnelle atomique | Un code observé ne sert pas deux fois |
| Secret TOTP | **AES-256-GCM** applicatif, contexte authentifié `user-mfa:<id>` | Une fuite de la base ne livre pas les secrets ; un secret copié chez un autre utilisateur ne se déchiffre pas |
| Personnel sans 2FA | Jeton **sans aucun droit** (`enrollment_only`), valable seulement pour activer la 2FA | Impossible d'administrer sans second facteur, sans bloquer la première connexion |
| Verrouillage | 5 échecs consécutifs (mot de passe ou code 2FA) → 15 minutes ; pendant le verrou, le mot de passe n'est même pas vérifié (pas d'oracle). Compteur mis à jour en **une seule instruction** | Résiste aux tentatives concurrentes |
| Compte inconnu | Même réponse, même coût de calcul (hachage factice) qu'un mauvais mot de passe | Pas d'énumération par le temps de réponse |
| Débit | Bucket4j en mémoire : par e-mail et par IP (connexion), par IP (inscription, renouvellement), par e-mail et IP (réinitialisation), par utilisateur (actions sensibles). Réponse `429` + `Retry-After` | Freine le bourrage d'identifiants |
| Liens à usage unique | Réinitialisation (1 h) et invitation du personnel (3 j), empreinte seule en base ; un nouveau lien invalide les précédents ; **la politique de mot de passe est vérifiée avant de consommer le lien** ; toutes les sessions sont fermées à l'usage | Un mot de passe refusé ne brûle pas le lien |
| Remise des liens | **Outbox** (`outbox_events`), jeton chiffré AES-GCM ; le module de notification (phase 8) enverra l'e-mail | Pas d'envoi pour une action annulée, pas de lien en clair en base |
| Gestion du personnel | Seul `SUPER_ADMIN` possède `USER_MANAGE` ; **seul un super-administrateur accorde ou retire les rôles d'administration** ; on ne se modifie pas soi-même ; l'auteur doit être **actif** ; au moins un super-administrateur actif subsiste (**lignes verrouillées** pour résister à deux suspensions simultanées) ; toute modification est auditée et ferme les sessions du compte | Évite l'escalade de privilèges et le verrouillage total |
| Premier compte | `WTC_BOOTSTRAP_SUPER_ADMIN_EMAIL` / `_PASSWORD` au démarrage, **sans effet dès qu'un super-administrateur existe** | Sans lui personne ne pourrait créer de comptes ; ce n'est pas une porte de réinitialisation |
| Erreurs d'authentification | `401` pour les erreurs de connexion et de défi ; **`422` (`MFA_CODE_REJECTED`) pour un mauvais code sur une route déjà authentifiée** | Un `401` sur une route à jeton ferait croire au client que son jeton est mauvais |

## Limites connues (à connaître, pas à corriger maintenant)

1. **Un compte suspendu garde un jeton d'accès valable jusqu'à 15 minutes** (JWT sans état). Les opérations d'administration vérifient l'état du compte en base ; les opérations d'argent (phases 5 à 7) devront le faire aussi. Les refresh tokens, eux, sont révoqués immédiatement.
2. **Les permissions d'un jeton sont celles du moment de sa délivrance** : un retrait de rôle s'applique au plus tard 15 minutes plus tard (immédiatement après le prochain renouvellement).
3. **Le limiteur de débit est local à l'instance.** Avec plusieurs instances, la limite effective est multipliée par leur nombre ; les verrouillages de compte, eux, sont en base et exacts. À remplacer par un magasin partagé (Redis) avant de passer à l'échelle.
4. L'inscription renvoie `409` si l'adresse existe déjà (imposé par le contrat actuel) : elle permet de tester l'existence d'une adresse. Elle est limitée par IP ; un parcours « vérifier l'e-mail d'abord » supprimerait cette fuite.
5. Pas de vérification d'e-mail ni de téléphone à l'inscription (absente du contrat et de l'application actuelle).
6. Pas de codes de secours si le téléphone TOTP est perdu : un super-administrateur réinitialise la 2FA (`POST /api/admin/users/{id}/mfa-reset`).
7. `PATCH /api/me` et `PUT /api/me/photo` sont dans le contrat mais pas encore implémentés : ils relèvent du module client (phase 3).
