# Intégration Wace Pay — lecture de la spécification officielle

Source : *WACEPAY INTEGRATION API SERVICE SPECIFICATION*, version 2.0.0 du 10/02/2025 (22 pages, fournie le 2026-10-16). Ce document en fait la synthèse et **liste ce qu'elle change dans nos hypothèses**. Les décisions qui en découlent seront prises en phase 6 ; rien ici n'est encore implémenté.

## 1. Ce que dit la spécification

**Connectivité.** HTTPS en JSON, tous les horodatages en **UTC**. L'accès se fait par **VPN site à site ou par liste blanche d'adresses IP** : l'adresse de sortie de nos serveurs doit être déclarée à Wace. Un environnement de **recette (staging)** existe avant le passage en production.

**Authentification.** `POST /login` avec `email` et `password` du partenaire → `access_token` (Bearer) valable **3600 s** ; il accompagne tous les autres appels (`Authorization: Bearer …`).

**Services** : envoi vers mobile (*wallet*), vers banque, retrait en espèces (*cash pickup*).

| Étape | Appel | Remarques |
|---|---|---|
| Créer l'expéditeur | `POST /sender/create` | Retourne un `Code` (la référence unique de l'expéditeur chez Wace) |
| Créer le bénéficiaire | `POST /beneficiary/create` | Exige le `sender_code` ; retourne un `Code` |
| Code du payeur (mobile) | `POST /transaction/payercode` | `toCountry`, `payoutService` (ex. `MTN`), `toCurrency` → `PayerCode` |
| Services d'un pays | `POST /transaction/payouts/services` | pays + devise → `ServiceCode` (`B` = banque, `WV` = Wave…) |
| Banques d'un pays | `POST /transaction/bank/list` | `iso2` + `payercode` → `BankID`, `BankCode`, `BankName` |
| Envoi mobile | `POST /transaction/wallet/create` | `payerCode`, `amountToPaid`, `senderCode`, `beneficiaryCode`, `fromCurrency`, `mobileReceiveNumber`, `fromCountry`, `originFund`, `reason`, `relation` |
| Envoi banque | `POST /transaction/bank/create` | + `bankAccount`, `bankName`, `bankId`, `bankSwCode`, `bankBranch`, `comment` (≤ 160 car., imprimé sur le reçu du bénéficiaire) |
| Retrait espèces | `POST /transaction/cash/create` (+ `collection_code_by_country`) | P2P seulement (code 1008) |
| **Confirmer** | `POST /transaction/confirm` | `reference` : **étape obligatoire** après la création ; déjà confirmé → code 1016 |
| Statut | `GET /transaction/status/{ref}` | voir ci-dessous |
| Référentiels | `GET /transaction/relation`, `GET /transaction/origin_fund/{type}`, `GET /transaction/reason/{type}` | `type` ∈ b2b, p2p, b2p, p2b ; réponses `{clé: libellé}` |
| Solde du partenaire | `GET /account/balance` | `Agency`, `Balance`, `Currency` (XAF dans l'exemple) |

**Statuts d'une transaction** : `PENDING`, `WAITING CONFIRMATION`, `PROCESSING` (« ne peut plus être annulée »), `PAID`, `CANCEL`, `LOCKED` (blocage conformité : contacter Wace).

**Codes de réponse** (champ `status`) : 2000 succès ; 200 traitée, 201 créée, 202 en attente de confirmation, 203 annulée (« vous pouvez réessayer »), 204 *à confirmer* (« vous pouvez réessayer la confirmation »), 205 en cours, 206 bloquée. **Codes d'erreur** : 1001 à 1017 (paramètres, autorisation, validation, erreur système 1005, expéditeur/bénéficiaire/référence introuvables, transaction déjà confirmée 1016…), 2001 à 2015 (devise, pays, taux non configuré 2010, **frais non configurés 2011**, relation 2012, motif 2013, origine des fonds 2014, service 2015), 3001 à 3003 (type de pièce), 6001 à 6003.

**Exemple de réponse de création (mobile)** : `reference` = `WPPX…`, `amount_receive` 1080, `feeds` 350, `amount_to_send` 1080, `total_amount_paid` 1430 — **Wace facture au partenaire ses propres frais** (`feeds`).

## 2. Ce que cela change pour nous

### Bloquant ou structurant

1. **Pas de webhook.** La spécification ne décrit aucun appel de retour : le statut s'obtient **uniquement par interrogation** (`GET /transaction/status`). L'opération `POST /webhooks/wace`, sa signature et la table `webhook_events` n'ont aucun fondement dans ce document. À moins que Wace ne confirme un mécanisme de callback hors de ce document, **le suivi par interrogation périodique devient le mécanisme principal** (et non plus un filet de sécurité).
2. **Pas de clé d'idempotence ni de référence fournie par le partenaire.** La référence (`WPPX…`) n'existe qu'**après** la création. Si la réponse se perd, on ne peut pas savoir si l'ordre a été créé. Seul l'envoi banque accepte un `comment` de 160 caractères qui revient dans la réponse (`reference_transaction`) : **y placer notre référence** permet de retrouver l'ordre ; l'envoi mobile **n'a aucun champ équivalent**. → *aucune nouvelle tentative automatique d'une création* (déjà acté dans l'ADR 0005) ; une réponse ambiguë se règle par vérification manuelle ou par le solde, jamais par rejeu.
3. **Création puis confirmation** : un transfert passe par `créé chez Wace` → `confirmé` → `payé`. La machine d'états du transfert (ERD) doit gagner l'étape **« en attente de confirmation »** ; la confirmation, elle, est **rejouable** (codes 204 et 1016).
4. **Expéditeur et bénéficiaire doivent exister chez Wace** avant tout envoi, avec leur `Code`. Notre schéma n'a nulle part où stocker ces codes : il faut une table de **références fournisseur** par contact (migration en phase 6). Les contacts d'un client sont donc créés chez Wace **au premier envoi** (pas à la saisie).
5. **Adresse IP à déclarer / VPN** : prérequis d'infrastructure à lancer tôt (délai chez Wace, pas chez nous).
6. **Pas d'API de devis ni de taux.** On ne peut pas demander à Wace « combien recevra le bénéficiaire ». Le montant reçu ne se connaît qu'**à la création** (`amount_receive`). Notre devis (phase 4) repose sur des taux saisis par l'administration qui **doivent être alignés sur ceux de Wace** ; à la création on **compare** `amount_receive` à notre calcul et on **signale tout écart** avant la confirmation. Les frais de Wace (`feeds`) sont un **coût** pour la plateforme à enregistrer (compte de charges du grand livre), distinct des frais facturés au client.

### Données manquantes dans notre modèle (à ajouter en phase 6)

| Exigence Wace | Notre modèle aujourd'hui |
|---|---|
| Expéditeur : adresse, ville, sexe M/F, civilité (`Maried`, `Single`, `Others`), nationalité, date de naissance, **date d'expiration de la pièce**, **PPE (`pep` 0/1)** obligatoires | `pep` absent ; adresse, ville, nationalité facultatives |
| Entreprise : `RCCM` obligatoire comme type de pièce (3001), date d'immatriculation, type de société (SARL, SAS…) | `business_type` libre, pas de contrôle RCCM |
| Bénéficiaire : adresse, ville, téléphone, pièce obligatoires ; date de naissance « selon la destination » | tous facultatifs |
| Codes de type de pièce (`PP`, `CNI`, `RCCM`, …) | texte libre (`id_document_type`) ; **liste complète non fournie** (« … ») |
| Banque : `bankId` **et** `bankSwCode` (dans l'exemple égal au `BankCode`) et `bankBranch` obligatoires | `swift_code` facultatif, pas de agence |
| Mobile : `PayerCode` à obtenir par appel | `mobile_operators.wace_payer_code` existe |
| Relation, motif, origine des fonds : **libellés textuels** de Wace (« Family Maintainance », « Loan »…) | `reference_values.wace_code` existe |

Ces contrôles stricts n'ont pas à bloquer la **saisie** d'un contact (l'ancien code les imposait déjà **à l'envoi** : règle 5 des règles métier) mais doivent bloquer la **création du transfert**.

### Mapping des codes d'erreur (proposition pour la phase 6)

| Famille | Codes | Traitement |
|---|---|---|
| Authentification | 1002 | Renouveler le jeton une fois ; sinon alerte, ordre non envoyé, **pas de remboursement automatique tant qu'aucun ordre n'a été créé** (rien n'a été débité côté Wace) |
| Validation / configuration | 1001, 1003, 1011, 1017, 2001 à 2015, 3001 à 3003, 6001 à 6003 | Refus **explicite** : le transfert échoue, remboursement unique et idempotent |
| `1005` (erreur système) | « réessayez ; au bout de 3 fois contactez le support » | **Ne pas rejouer une création** (point 2) ; réessayer seulement les appels sans effet (login, statut, confirmation) |
| 1016 | déjà confirmé | Succès idempotent |
| `203` (annulée), `CANCEL` | « vous pouvez réessayer » | Échec confirmé → remboursement unique ; on ne recrée pas l'ordre automatiquement |
| `LOCKED` / `206` | conformité | **Aucun remboursement** ; file de traitement manuel |

## 2 bis. Ce qui est implémenté (phase 6)

**Code payeur** : section VII de la spécification (`POST /transaction/payercode`) ; le service bancaire est `B` dans la liste `payouts/services`. L'import (`POST /api/admin/reference/sync`) récupère ces codes, la liste des banques (`bank/list`) et les libellés (relations, origines des fonds, motifs). Une première version de ce document affirmait à tort que la spécification n'expliquait pas comment obtenir le code payeur.

Adaptateur `WacePayoutGateway` (login avec jeton mis en cache, création d'expéditeur et de bénéficiaire, ordres bancaire et mobile, confirmation, statut, solde), orchestration par `PayoutProcessor`, règles décrites dans l'[ADR 0014](../adr/0014-transferts-et-envoi-wace.md). Les points 1 à 6 ci-dessus y sont traités ; la **recette** reste indispensable pour valider les formats réels. `POST /webhooks/wace` a été **retiré du contrat** (aucun webhook dans la spécification).

## 3. Ce qui reste à obtenir de Wace

1. Identifiants et URL de la **recette**, et la procédure de **liste blanche d'IP / VPN**.
2. Existe-t-il un **callback** ou une **clé d'idempotence** non documentés ? Sinon confirmer la règle de rapprochement (point 2).
3. La **liste complète** des types de pièce (`idType`), des services (`ServiceCode`), des valeurs de civilité.
4. Les **règles de frais et de taux** de Wace par corridor, et le moyen de les connaître avant l'envoi (point 6).
5. Le **délai** habituel entre `WAITING CONFIRMATION`, `PROCESSING` et `PAID`, pour fixer la cadence d'interrogation.
6. Le format exact des montants (`"1080.000XAF"` dans le statut, nombre dans la création) : l'analyse devra être tolérante.
7. Les limites d'appels (cadence maximale) et le comportement en cas de dépassement.
