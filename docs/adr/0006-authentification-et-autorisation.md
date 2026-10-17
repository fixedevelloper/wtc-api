# ADR 0006 — Authentification et autorisation

> **Mise en œuvre** : voir l'[ADR 0010](0010-identite-choix-d-implementation.md) pour les choix retenus en phase 2 et leurs limites connues.

- **Statut** : Proposé (basé sur les valeurs par défaut de [assumptions.md](../design/assumptions.md), à confirmer)
- **Date** : 2026-10-16

## Contexte
Les jetons durent 1 heure sans renouvellement ; le refresh token est stocké en clair et jamais tourné ; les rôles sont des listes JSON mal formées en base ; le personnel se connecte sans second facteur ; plusieurs routes critiques sont anonymes.

## Décision
- JWT **RS256** de courte durée (10 à 15 minutes) ; **refresh token à rotation** (famille par appareil, détection de rejeu, stockage haché) ; déconnexion = révocation de la famille.
- **RBAC par permissions** (`TRANSFER_REFUND`, `BALANCE_ADJUST_APPROVE`…) rattachées à quatre rôles : `CUSTOMER`, `SUPPORT`, `ADMIN`, `SUPER_ADMIN`. Chaque opération d'administration déclare sa permission dans le contrat.
- **2FA (TOTP) obligatoire pour tout le personnel** ; secret chiffré en base.
- Mots de passe : les hash bcrypt `$2y$` existants sont repris tels quels (compatibles Spring) et ré-encodés en Argon2id à la connexion suivante ; politique de longueur ; verrouillage temporaire après échecs ; limitation de débit (Bucket4j) ; jamais de fuite d'existence de compte.
- Journaux : `login_audit` (connexions) et `audit_log` immuable (avant/après).
- Pièces KYC et justificatifs : stockage objet privé, liens signés de courte durée, chiffrement applicatif des numéros de pièce.
- Cible de sécurité : **OWASP ASVS niveau 2** et OWASP API Security Top 10.

## Conséquences
+ Réduit fortement l'impact d'un jeton volé ; les actions sensibles exigent deux personnes.
− Complexité accrue côté clients (renouvellement de jeton, enrôlement 2FA) : couverte par les clients générés.
− Les clés de signature doivent être gérées (rotation, stockage en secret).

## Alternatives écartées
Sessions serveur (incompatible avec le mobile) ; jetons longs sans rotation (risque de vol).
