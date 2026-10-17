# Authentification, autorisation et sécurité

## Contrat à reproduire (pour que les sessions existantes survivent au basculement)

| Élément | Valeur actuelle | Conséquence pour Spring |
|---|---|---|
| Jeton d'accès | JWT **RS256**, clé RSA 2048 bits, en-tête `typ: JWT` | `oauth2-resource-server` avec la **même clé publique** ; la clé privée est partagée pendant la cohabitation (hors dépôt, en secret) |
| Claims | `username` (email de l'utilisateur), `iat`, `exp` | Convertisseur : l'identité vient de `username`, pas de `sub` |
| Durée | 3 600 s (access) ; refresh token aléatoire de 64 caractères hexadécimaux, 7 jours, stocké en clair dans `refresh_token` | Hacher le refresh token, rotation à chaque usage |
| Transmission | `Authorization: Bearer …` uniquement | — |
| Mots de passe | **bcrypt `$2y$`** pour 96 utilisateurs sur 96 | `BCryptPasswordEncoder` lit `$2y$` : aucun reset nécessaire |
| Rôles | colonne JSON `user.roles`. Valeurs vues : `["ROLE_CUSTOMER"]` (86), `["ROLE_ADMIN"]` (2), `["ROLE_USER"]` (3), `[["ROLE_USER"]]` (3, **liste imbriquée**), `[["ROLE_CUSTOMER"]]` (1), `["ROLE_SUPER"]` (1, probablement `ROLE_SUPER_ADMIN` mal saisi) | Parseur tolérant (aplatit les listes imbriquées) + script de nettoyage des données ; `ROLE_SUPER_ADMIN` ⊃ `ROLE_ADMIN` |
| Sessions | aucune (stateless) | `SessionCreationPolicy.STATELESS` |
| CORS | `*` sur toutes les zones, sans cookies | Restreindre aux domaines des frontends |

Les rôles d'accès sont ceux de `security.yaml` : `/v2/admin` et `/v3` exigent `ROLE_ADMIN`, `/v2` exige un utilisateur authentifié sauf pays et villes (publics).

## Règles d'autorisation métier (à porter telles quelles)

- **Propriété des données** (`AccountAccess`) : un client n'accède qu'à son compte titulaire, à ses expéditeurs (`customer.customeralt` = id du titulaire), à ses bénéficiaires (`contactcustomer.customer` = titulaire), aux transactions de ses expéditeurs, à ses recharges. L'admin n'est pas limité. Dans la nouvelle API (`/api/me/...`), l'identité vient du jeton : plus d'identifiant client dans l'URL.
- Un transfert exige : expéditeur du client connecté, bénéficiaire du client connecté, solde suffisant (débit atomique).
- `ROLE_ADMIN` : lecture et modification de toutes les données du back-office, ajustement de solde, création de jetons web.

## Flux de connexion actuels à fusionner en un seul `POST /api/auth/login`

| Appelant | Route actuelle | Particularités à conserver |
|---|---|---|
| Android | `POST /auth/logincustomer` | Réponse `{token, id, customer_id, email, name, roles, photoURL, otpSend, emailVerified, verification}` ; `verification` = pièce d'identité non expirée |
| Web admin | `POST /auth/v3/login` (alias `/v2/auth/login`) | Écrit un `login_audit` (succès/échec, IP) ; renvoie `roles` |
| Aucun connu | `POST /auth/signin` (`V2\AuthController`) | Renvoie `tokens {access_token, refresh_token}` |

À corriger au passage (défauts de l'ancienne implémentation) : le message d'erreur révèle si l'email existe et **répète le mot de passe saisi** (`Resource $password not work`) ; aucune limitation de tentatives (le `rate_limiter` « login » est déclaré mais jamais appliqué) ; le refresh token n'est ni tourné ni révoqué.

## Routes anonymes dangereuses héritées (à ne pas reproduire telles quelles)

| Route | Problème | Traitement proposé |
|---|---|---|
| `POST /collecte/create` | Déclenche un pay-in Wace sans authentification ; `callback_url` choisie par l'appelant (SSRF) | Authentifier, liste blanche d'hôtes pour le callback |
| `POST /` (`uploadKYC`) | Dépôt de pièces KYC sans authentification | Authentifier, stockage privé |
| `POST /auth/changephoto` | Modifie la photo de n'importe quel utilisateur (id dans le corps) et écrit un fichier dans `public/uploads` | Authentifier (`PUT /api/me/photo`), contrôle du type |
| `POST /auth/save_pin`, `POST /auth/login_pin` | Définit ou vérifie le PIN de n'importe quel client ; PIN en clair (3 utilisateurs, 6 chiffres) | Reprendre seulement si le PIN est voulu : authentifier, hacher, limiter les essais |
| `POST /auth/webhook/telegram` | Aucun secret vérifié | Header `X-Telegram-Bot-Api-Secret-Token` |
| `/auth/notifyurl/*` | Callbacks de paiement sans signature | Signatures partenaires, idempotence, contrôle montant/référence |
| `POST /webhook/stripe` | Secret de webhook **écrit en dur** dans le code ; ne traite rien | Secret en variable d'environnement ; traitement idempotent |
| `GET|POST /auth/testapitransaction/ajax` | Route de test appelant PayCI | Supprimer |

## Observabilité de la sécurité à reprendre

`login_audit` (connexions), `user_action_log` (actions admin, par exemple `update_balance`), journal `legacy_api` (outil de migration Symfony, non repris).
