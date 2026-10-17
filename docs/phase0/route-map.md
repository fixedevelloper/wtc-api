# Carte cible des routes (proposition à valider)

Base unique **`/api`**, sans numéro de version. Les routes du client connecté sont sous **`/api/me`** : l'identité vient du jeton, il n'y a plus d'identifiant de client dans l'URL ni dans le corps (ce qui supprime à la source la faille de propriété). Le back-office est sous **`/api/admin`** (rôle `ROLE_ADMIN`).

Colonnes : *Anciennes routes* = routes Symfony fusionnées dans la nouvelle ; *Appelants* = Android, web, partenaire ; *Décision* = voir [routes.csv](routes.csv).

## Authentification

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| POST | `/api/auth/login` | `POST /auth/login`<br>`POST /auth/logincustomer`<br>`POST /auth/signin`<br>`POST /auth/v3/login`<br>`POST /v2/auth/login` | android, web | PORTER, À DÉCIDER |
| POST | `/api/auth/otp/resend` | `POST /auth/resendotp/{id}`<br>`POST /auth/resendtoken/{id}` | — | À DÉCIDER |
| POST | `/api/auth/otp/verify` | `POST /auth/verifyotp/{id}/{code}` | — | À DÉCIDER |
| POST | `/api/auth/pin-login` | `POST /auth/login_pin` | — | À DÉCIDER |
| POST | `/api/auth/refresh` | `POST /auth/refresh` | — | PORTER |
| POST | `/api/auth/register` | `POST /auth/register` | android | PORTER |

## Client connecté

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| GET | `/api/me` | `GET /v2/profile/{id}` | android | PORTER |
| PATCH | `/api/me` | `POST /v2/profile` | android | PORTER |
| GET | `/api/me/beneficiaries` | `GET /v2/beneficiaries/{id}` | android | PORTER |
| POST | `/api/me/beneficiaries` | `POST /v2/beneficiaries` | android | PORTER |
| GET | `/api/me/beneficiaries/options` | `GET /v2/all_beneficiaries/{id}` | android | PORTER |
| GET | `/api/me/beneficiaries/{beneficiaryId}` | `GET /v2/beneficiaries/{id}/one` | android | PORTER |
| PUT | `/api/me/beneficiaries/{beneficiaryId}` | `POST /v2/beneficiaries/update/{id}` | android | PORTER |
| POST | `/api/me/kyc-documents` | `POST /` | — | À DÉCIDER |
| PUT | `/api/me/password` | `POST /v2/change_password` | android | PORTER |
| PUT | `/api/me/photo` | `POST /auth/changephoto` | — | À DÉCIDER |
| PUT | `/api/me/pin` | `POST /auth/save_pin` | — | À DÉCIDER |
| GET | `/api/me/recharges` | `GET /v2/recharges/{id}` | android | PORTER |
| POST | `/api/me/recharges` | `POST /v2/recharges` | android | PORTER |
| POST | `/api/me/recharges/proof` | `POST /v2/recharges/upload` | android | PORTER |
| GET | `/api/me/senders` | `GET /v2/senders/{id}` | android | PORTER |
| POST | `/api/me/senders` | `POST /v2/senders` | android | PORTER |
| GET | `/api/me/senders/options` | `GET /v2/all_senders/{id}` | android | PORTER |
| GET | `/api/me/senders/{senderId}` | `GET /v2/senders/{id}/{sender_id}/one` | android | PORTER |
| PUT | `/api/me/senders/{senderId}` | `POST /v2/sender/update`<br>`POST /v2/senders/update/{id}` | android | PORTER |
| GET | `/api/me/transactions` | `GET /v2/transactions/{id}` | android | PORTER |
| POST | `/api/me/transactions/bank` | `POST /api/transactions/bank`<br>`POST /v2/transferts/bank` | android | PORTER, À DÉCIDER |
| GET | `/api/me/transactions/latest` | `GET /v2/last_transactions/{id}`<br>`GET /v2/last_transactions` | android | PORTER |
| POST | `/api/me/transactions/mobile` | `POST /api/transactions/mobil`<br>`POST /v2/transferts/mobile` | android | PORTER, À DÉCIDER |
| GET | `/api/me/transactions/{transactionId}` | `GET /v2/transactions/detail/{id}` | android | PORTER |

## Référentiels

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| GET | `/api/countries` | `GET /api/countries`<br>`GET /auth/countries`<br>`GET /v2/countries` | android, web | PORTER, À DÉCIDER |
| GET | `/api/countries/{countryId}/banks` | `GET /api/banklists/{id}`<br>`GET /v2/banklists/{id}` | android, web | PORTER |
| GET | `/api/countries/{countryId}/cities` | `GET /v2/cities/{id}` | android, web | PORTER |
| GET | `/api/countries/{countryId}/exchange-rate` | `GET /api/tauxechanges/{id}`<br>`GET /v2/tauxechanges/{id}` | android, web | PORTER |
| GET | `/api/countries/{countryId}/mobile-operators` | `GET /api/operatorslists/{id}`<br>`GET /v2/operatorslists/{id}` | android, web | PORTER |
| GET | `/api/countries/{countryId}/payment-operators` | `GET /v2/operators/{id}` | android | PORTER |
| GET | `/api/countries/{isoCode}/cities` | `GET /api/cities/{code}/codeiso`<br>`GET /v2/cities/{code}/codeiso` | — | PORTER |

## Référentiels (suite)

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| GET | `/api/reference/wace-services` | `GET /v2/wace_data` | android, web | PORTER |

## Paiements entrants

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| POST | `/api/payins` | `POST /collecte/create` | — | À DÉCIDER |

## Jetons web

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| GET | `/api/web-tokens/{token}` | `GET /api/verify-token/{token}` | — | PORTER |
| POST | `/api/web-tokens/{token}/consume` | `POST /api/consume-token/{token}` | — | PORTER |

## Back-office

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| GET | `/api/admin/audit-logs` | `GET /v2/admin/audits` | web | PORTER |
| GET | `/api/admin/beneficiaries` | `GET /v2/admin/beneficiaries` | web | PORTER |
| GET | `/api/admin/beneficiaries/{beneficiaryId}` | `GET /v2/admin/beneficiaries/{id}/one` | web | PORTER |
| PUT | `/api/admin/beneficiaries/{beneficiaryId}` | `POST /v2/admin/beneficiaries/{id}/update` | web | PORTER |
| GET | `/api/admin/customers` | `GET /v2/admin/customers` | web | PORTER |
| GET | `/api/admin/customers/{customerId}` | `GET /v2/admin/customers/{id}` | web | PORTER |
| POST | `/api/admin/customers/{customerId}/balance-adjustments` | `POST /v2/admin/update-balance` | web | PORTER |
| GET | `/api/admin/customers/{customerId}/beneficiaries` | `GET /v2/admin/all_beneficiaries/{id}` | web | PORTER |
| GET | `/api/admin/customers/{customerId}/senders` | `GET /v2/admin/customers/senders/{id}` | web | PORTER |
| GET | `/api/admin/fee-grids` | `GET /v2/admin/grilletarifaires` | web | PORTER |
| POST | `/api/admin/fee-grids` | `POST /v2/admin/grilletarifaires` | web | PORTER |
| GET | `/api/admin/login-audits` | `GET /v2/admin/connexions` | web | PORTER |
| GET | `/api/admin/recharges` | `GET /v2/admin/recharges` | web | PORTER |
| GET | `/api/admin/senders` | `GET /v2/admin/senders` | web | PORTER |
| GET | `/api/admin/senders/options` | `GET /v2/admin/all_senders/{id}` | web | PORTER |
| GET | `/api/admin/senders/{senderId}` | `GET /v2/admin/senders/{id}/one` | web | PORTER |
| PUT | `/api/admin/senders/{senderId}` | `POST /v2/admin/senders/{id}/update` | web | PORTER |
| GET | `/api/admin/transactions` | `GET /v2/admin/transactions` | web | PORTER |
| POST | `/api/admin/transactions/bank` | `POST /v2/admin/transactions/bank` | web | PORTER |
| GET | `/api/admin/transactions/{transactionId}` | `GET /v2/admin/transactions/detail/{id}` | web | PORTER |
| GET | `/api/admin/users` | `GET /v2/admin/users` | web | PORTER |
| GET | `/api/admin/web-tokens` | `GET /v2/admin/webtokens` | web | PORTER |
| POST | `/api/admin/web-tokens` | `POST /v2/admin/webtokens` | web | PORTER |
| GET | `/api/admin/zones` | `GET /v2/admin/zones` | web | PORTER |
| POST | `/api/admin/zones` | `POST /v2/admin/zones` | web | PORTER |

## Webhooks partenaires

| Méthode | Nouvelle route | Anciennes routes | Appelants | Décision |
|---|---|---|---|---|
| POST | `/api/webhooks/cinetpay` | `GET /auth/notifyurl/cinetpay`<br>`POST /auth/notifyurl/cinetpay` | partenaire(callback) | PORTER |
| POST | `/api/webhooks/payci` | `GET /auth/notifyurl/ajax`<br>`POST /auth/notifyurl/ajax` | partenaire(callback) | PORTER |
| POST | `/api/webhooks/payci-prefund` | `GET /auth/notifyurl/payci`<br>`POST /auth/notifyurl/payci` | partenaire(callback) | PORTER |
| POST | `/api/webhooks/paydunya` | `GET /auth/notifyurl/paydunya`<br>`POST /auth/notifyurl/paydunya` | partenaire(callback) | PORTER |
| POST | `/api/webhooks/paydunya-prefund` | `GET /auth/notifyurlprefund/paydunya`<br>`POST /auth/notifyurlprefund/paydunya` | partenaire(callback) | PORTER |
| POST | `/api/webhooks/stripe` | `POST /webhook/stripe` | partenaire(callback) | PORTER |
| POST | `/api/webhooks/telegram` | `POST /auth/webhook/telegram` | — | À DÉCIDER |

