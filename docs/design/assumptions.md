# Hypothèses retenues pour la phase 0b

La phase 0b a démarré avant que toutes les décisions soient prises. Les **valeurs par défaut** ci-dessous ont servi à concevoir le schéma, le contrat d'API et les ADR. **Chacune peut être corrigée** : la dernière colonne indique ce qui change.

| # | Question | Valeur retenue | Si la réponse diffère |
|---|---|---|---|
| 1 | Fonctions héritées sans usage client (`analysemarge`, `employe`, `cash_pickup`, `crypto_monnaie`, `partner`) | **Abandonnées.** `analysemarge` (178 lignes de reporting de marge) pourrait être remplacée par un rapport calculé depuis le grand livre | Ajouter les tables et routes correspondantes ; le reporting de marge se calcule sans table dédiée |
| 2 | Moteur de base de données | **PostgreSQL 16** | Repasser en MySQL : perdre les contraintes d'exclusion (barèmes) et le DDL transactionnel, à compenser dans le code |
| 3 | Canaux de notification et recharges | **E-mail, WhatsApp et Telegram conservés** comme canaux (pas des « partenaires de paiement »). Recharges = **Wace Pay-In + preuve manuelle** ; la carte (Stripe) disparaît | Si la carte est requise : un second adaptateur derrière `PayInGateway` |
| 4 | Spécification Wace | **Reçue le 2026-10-16** (v2.0.0, PDF) et analysée : [wace-integration.md](wace-integration.md). Elle ne décrit **ni webhook ni clé d'idempotence** ; les opérations `x-pending` seront revues en phase 6. **Reste à obtenir** : accès à la recette, procédure de liste blanche d'IP, listes de codes incomplètes | Sans accès à la recette, la phase 6 ne peut pas être validée de bout en bout |
| 5 | Anciennes versions d'Android | **Mise à jour forcée** avant la bascule (ADR 0008) | Couche de compatibilité temporaire `/v2/*` : environ 2 semaines de plus |
| 6 | Devises | **Portefeuilles multi-devises**, un par client et par devise. L'ancien système tient déjà des soldes par pays (48 soldes non nuls dans 9 devises) | Mono-devise : impossible sans décision explicite sur ces soldes (voir « Découvertes de données ») |
| 7 | Conformité | Hypothèse minimale : KYC obligatoire pour envoyer, pièces conservées en stockage privé, journal d'audit immuable. **Aucune exigence LCB-FT ou de durée de conservation n'est supposée** | Ajouter seuils, déclarations, conservation et rapports réglementaires |
| 8 | Infrastructure | Conteneurs Docker, stockage objet compatible S3 (MinIO déjà présent en local) | Adapter le déploiement (phase 1) ; sans effet sur le contrat |
| 9 | Équipe et échéance | 2 à 3 développeurs, 16 à 20 semaines | Voir le plan par phases |
| 10 | Langue des identifiants | **Anglais** (ADR 0007) | Traduire le contrat et le schéma (coût élevé : à décider maintenant) |

## Hypothèses ajoutées en phase 3 (détail : [ADR 0011](../adr/0011-clients-kyc-et-stockage.md))

| # | Question | Valeur retenue | Si la réponse diffère |
|---|---|---|---|
| 11 | Règle « KYC vérifié » | Une pièce d'identité approuvée et valable suffit ; selfie et justificatif de domicile facultatifs | Ajouter les pièces exigées à `KycPolicy` (une fonction pure, testée) |
| 12 | Formats de pièces | JPEG, PNG, PDF ; 8 Mio | Ajouter un format = ajouter sa signature à `FileTypes` |
| 13 | Changement d'e-mail | Non pris en charge tant qu'il n'y a pas de vérification par lien | Prévoir un parcours « confirmer par lien envoyé à la nouvelle adresse » |
| 15 | Quelle zone tarifaire pour les frais ? | La zone du **pays de destination**, devise du portefeuille source | Passer à la zone du pays de l'expéditeur : un paramètre de `PricingService.quote` |
| 16 | Arrondis | Frais demi supérieur ; montant reçu vers le bas ; unité = décimales ISO de la devise | Autre règle = `FeeCalculator` / `FxConverter` (fonctions pures testées) |
| 17 | Quel est le portefeuille « principal » d'un client ? | Celui de la **devise de son pays de résidence** ; les autres s'obtiennent par `?currency=` | Une préférence enregistrée par le client, ou une devise unique imposée |
| 18 | Qui peut envoyer ? | Un client **actif** dont le KYC est **vérifié** ; contacts complets selon Wace | Assouplir `WaceRules` ou la règle KYC |
| 14 | Identité vérifiée modifiable ? | Non (`KYC_LOCKED`), correction par le support | Autoriser et repasser le client en `PENDING` |

## Découvertes de données qui imposent une décision (détail dans [data-migration-mapping.md](data-migration-mapping.md))

1. **Soldes par pays (`balance_customer`)** : 48 soldes non nuls dans 9 devises (dont un code invalide, `£` ; `XOF` et `XAF` sont deux devises distinctes et valides, à ne jamais fusionner). Ils sont migrés en portefeuilles par devise ; il faut **confirmer qu'ils représentent de l'argent réel** et non des données de test.
2. **Un solde principal négatif** (`customer.balance`) : le schéma l'interdit. Proposition : migrer à 0 et lister le cas pour décision (créance ou erreur).
3. **359 transactions « en validation / en attente » depuis plus de 90 jours** : le client a été débité mais l'issue est inconnue. Proposition : les migrer en `CANCELLED` avec leur statut d'origine, **sans remboursement automatique**, et produire un état pour traitement manuel.
4. **278 transactions masquées** (`isdelete`) : migrées avec `hidden_at`, jamais supprimées.
5. **1 158 transactions sur 1 231 sont enregistrées au nom du titulaire**, pas d'un expéditeur : l'ETL crée un expéditeur à partir de la fiche du titulaire.
6. **Téléphones** : 96 sans « + », 15 vides ; **dates de naissance** : 7 sur 20 hors format ISO. Normalisation au mieux, valeur d'origine conservée dans `legacy_attributes`.
7. **Libellés Wace** : les listes dépendent du type de transfert (P2P : 15 motifs ; P2B/B2P/B2B : 20) et contiennent des valeurs parasites (`1`, `200`). Elles seront réimportées depuis Wace plutôt que migrées.
8. **Rôles** : `[["ROLE_USER"]]` (listes imbriquées), `ROLE_SUPER` (probablement `ROLE_SUPER_ADMIN`) ; seuls 2 comptes ont `ROLE_ADMIN`.

## Notifications (phase 8)

19. Les canaux courriel, WhatsApp et Telegram sont conservés ; le client est prévenu par courriel et WhatsApp (s'il a un téléphone), l'équipe par Telegram. Textes en français uniquement.
20. Le reçu PDF est généré à la demande, jamais stocké.
21. Conservation : jetons de rafraîchissement 30 j après échéance, jetons à usage unique 7 j, événements traités 60 j, notifications envoyées 90 j ; audit et connexions conservés sans limite en attendant une décision (RGPD / obligations de conservation).
22. Le rappel d'expiration d'une pièce part 30 jours avant l'échéance ; une pièce échue retire la vérification d'identité du client.

## Migration (phase 10)

23. `ROLE_SUPER` → `ADMIN` (moindre privilège) en attendant confirmation ; la RDC est en XAF dans l'ancienne base (devise réelle CDF/USD) : à confirmer.
24. Les transferts migrés sont financés hors plateforme (`OFFLINE_RECEIPT`) : leurs soldes sont dans les soldes d'ouverture.
25. Les taux de change de l'ancienne base ne sont pas repris (non lus par l'ancien code) ; les tranches de frais le sont, datées du jour de la bascule, une devise unique par zone étant requise.

## Ce qui n'est pas décidé

- Les valeurs exactes de la politique de remboursement sur annulation Wace (proposition : remboursement unique, idempotent, sur statut confirmé uniquement).
- La durée de vie des jetons, les durées de conservation, les seuils de montant : valeurs initiales dans `platform_settings` (`transfer.max_amount_per_transfer` à 5 000 000 XAF à confirmer).
- La fréquence de la réconciliation et du suivi des transferts (en production actuelle : inconnue).
