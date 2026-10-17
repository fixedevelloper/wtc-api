# Guide de migration des frontends

Généré depuis le contrat OpenAPI (`x-replaces`) et les appels réels des clients. Ne pas modifier à la main : `python3 tools/api_docs.py`.

## Changements transverses (à traiter une fois, dans la couche réseau)

| Sujet | Avant | Après |
|---|---|---|
| Base d'URL | `/v2/...`, `/v2/admin/...`, `/auth/...` | `/api/...` (une seule base, sans version) |
| Identité | identifiant du client dans l'URL ou le corps (`/v2/profile/{id}`, `customer_id`) | **jeton uniquement** : routes `/api/me/...` |
| Enveloppe de réponse | `{status, code, message, data}` | objet direct ; listes paginées `{page, size, totalElements, totalPages, content[]}` |
| Erreurs | `{status:"error", error:{details}}` | `application/problem+json` : `{type, title, status, detail, code, errors[]}` ; se brancher sur `code` |
| Noms de champs | snake_case, fautes historiques (`acount_number`, `relaction`, `rounting_number`) | camelCase, orthographe corrigée |
| Montants | nombres JSON ; frais et total calculés par le client | chaînes décimales + devise ; **le serveur fait foi** (`POST /api/me/transfers/quote`) |
| Dates | formats variés (`"D d,M,y h:i"`, `Y-m-d`) | ISO-8601 UTC |
| Pagination | `page` à partir de 1 / `offset` selon la route | `page` à partir de 0, `size` ≤ 100, `sort=champ,asc` |
| Authentification | JWT 1 h sans renouvellement | JWT court + **refresh token à rotation** ; 2FA pour le personnel |
| Transferts / recharges | aucun contrôle de rejeu | en-tête **`Idempotency-Key`** obligatoire (UUID généré par l'application) |
| Mises à jour forcées | — | `GET /api/meta/client-requirements` au démarrage |

## Android

33 appels déclarés dans `ApiService.kt`. Les appels de `PayLinkApi.kt` (`api/paylinks`, `bills`, `withdraw`, `PAYLINK_BASE_URL`) relèvent d'un autre microservice et ne changent pas.

| Appel actuel | Remplacé par |
|---|---|
| POST `/auth/logincustomer` | `POST /api/auth/login` |
| POST `/auth/register` | `POST /api/auth/register` |
| GET `/v2/all_beneficiaries/{id}` | `GET /api/me/beneficiaries` |
| GET `/v2/all_senders/{id}` | `GET /api/me/senders` |
| GET `/v2/banklists/{id}` | `GET /api/countries/{countryCode}/banks` |
| POST `/v2/beneficiaries` | `POST /api/me/beneficiaries` |
| POST `/v2/beneficiaries/update/{id}` | `PUT /api/me/beneficiaries/{beneficiaryId}` |
| GET `/v2/beneficiaries/{id}` | `GET /api/me/beneficiaries` |
| GET `/v2/beneficiaries/{id}/one` | `GET /api/me/beneficiaries/{beneficiaryId}` |
| POST `/v2/change_password` | `PUT /api/me/password` |
| GET `/v2/cities/{id}` | `GET /api/countries/{countryCode}/cities` |
| GET `/v2/countries` | `GET /api/countries` |
| GET `/v2/last_transactions` | `GET /api/me/transfers` |
| GET `/v2/operators/{id}` | `GET /api/countries/{countryCode}/mobile-operators` |
| GET `/v2/operatorslists/{id}` | `GET /api/countries/{countryCode}/mobile-operators` |
| POST `/v2/profile` | `PATCH /api/me` |
| GET `/v2/profile/{id}` | `GET /api/me` |
| POST `/v2/recharges` | `POST /api/me/top-ups` |
| POST `/v2/recharges/upload` | `POST /api/me/top-ups/proof` |
| GET `/v2/recharges/{id}` | `GET /api/me/top-ups` |
| POST `/v2/senders` | `POST /api/me/senders` |
| POST `/v2/senders/update/{id}` | `PUT /api/me/senders/{senderId}` |
| GET `/v2/senders/{id}` | `GET /api/me/senders` |
| GET `/v2/senders/{id}/{sender_id}/one` | `GET /api/me/senders/{senderId}` |
| GET `/v2/tauxechanges/{id}` | `POST /api/me/transfers/quote`<br>`POST /api/admin/transfers/quote` |
| GET `/v2/transactions/detail/{id}` | `GET /api/me/transfers/{transferId}` |
| GET `/v2/transactions/{id}` | `GET /api/me/transfers` |
| POST `/v2/transferts/bank` | `POST /api/me/transfers` |
| POST `/v2/transferts/mobile` | `POST /api/me/transfers` |
| GET `/v2/wace_data` | `GET /api/reference/transfer-purposes`<br>`GET /api/reference/sources-of-funds`<br>`GET /api/reference/relationships` |
| GET `/wace_origin_fond.json` | `GET /api/reference/sources-of-funds` |
| GET `/wace_raison_to_send.json` | `GET /api/reference/transfer-purposes` |
| GET `/wace_relaction.json` | `GET /api/reference/relationships` |

## Web (back-office Next.js)

32 appels recensés (`Constant.ts` et composants).

| Appel actuel | Remplacé par |
|---|---|
| GET `/v2/admin/all_beneficiaries/{id}` | `GET /api/admin/parties` |
| GET `/v2/admin/all_senders/{id}` | `GET /api/admin/parties` |
| GET `/v2/admin/audits` | `GET /api/admin/audit-logs` |
| GET `/v2/admin/beneficiaries` | `GET /api/admin/parties` |
| GET `/v2/admin/beneficiaries/{id}/one` | `GET /api/admin/parties/{partyId}` |
| POST `/v2/admin/beneficiaries/{id}/update` | `PUT /api/admin/parties/{partyId}` |
| GET `/v2/admin/connexions` | `GET /api/admin/login-audits` |
| GET `/v2/admin/customers` | `GET /api/admin/customers` |
| GET `/v2/admin/customers/senders/{id}` | `GET /api/admin/parties` |
| GET `/v2/admin/customers/{id}` | `GET /api/admin/customers/{customerId}` |
| GET `/v2/admin/grilletarifaires` | `GET /api/admin/fee-schedules` |
| POST `/v2/admin/grilletarifaires` | `POST /api/admin/fee-schedules` |
| GET `/v2/admin/recharges` | `GET /api/admin/top-ups` |
| GET `/v2/admin/senders` | `GET /api/admin/parties` |
| GET `/v2/admin/senders/{id}/one` | `GET /api/admin/parties/{partyId}` |
| POST `/v2/admin/senders/{id}/update` | `PUT /api/admin/parties/{partyId}` |
| GET `/v2/admin/transactions` | `GET /api/admin/transfers` |
| POST `/v2/admin/transactions/bank` | `POST /api/admin/transfers` |
| GET `/v2/admin/transactions/detail/{id}` | `GET /api/admin/transfers/{transferId}` |
| POST `/v2/admin/update-balance` | `POST /api/admin/balance-adjustments` |
| GET `/v2/admin/users` | `GET /api/admin/users` |
| GET `/v2/admin/webtokens` | `GET /api/admin/web-tokens` |
| POST `/v2/admin/webtokens` | `POST /api/admin/web-tokens` |
| GET `/v2/admin/zones` | `GET /api/admin/pricing-zones` |
| POST `/v2/admin/zones` | `POST /api/admin/pricing-zones` |
| POST `/v2/auth/login` | `POST /api/auth/login` |
| GET `/v2/banklists/{id}` | `GET /api/countries/{countryCode}/banks` |
| GET `/v2/cities/{id}` | `GET /api/countries/{countryCode}/cities` |
| GET `/v2/countries` | `GET /api/countries` |
| GET `/v2/operatorslists/{id}` | `GET /api/countries/{countryCode}/mobile-operators` |
| GET `/v2/tauxechanges/{id}` | `POST /api/me/transfers/quote`<br>`POST /api/admin/transfers/quote` |
| GET `/v2/wace_data` | `GET /api/reference/transfer-purposes`<br>`GET /api/reference/sources-of-funds`<br>`GET /api/reference/relationships` |

## Écrans et fonctions nouveaux côté front

- **Web** : validation des ajustements de solde (double validation), revue des documents KYC, réconciliation Wace, gestion des comptes du personnel, activation de la 2FA, saisie d'un transfert avec justificatif de paiement hors plateforme.
- **Android** : devis avant envoi, gestion des destinations d'un bénéficiaire (compte bancaire ou portefeuille mobile), relevé du portefeuille, documents KYC, recharge Wace Pay-In, réinitialisation du mot de passe.
