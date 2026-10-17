# Vue d'ensemble de l'API

Générée depuis [`api/openapi.yaml`](../../api/openapi.yaml) — **100 opérations**, base `/api`. Ne pas modifier à la main : `python3 tools/api_docs.py`.

Légende : **Accès** = qui peut appeler ; **Idem.** = en-tête `Idempotency-Key` obligatoire ; **Origine** = *existante* (remplace une route Symfony) ou *nouvelle*.

## Authentification

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| POST | `/api/auth/register` | Créer un compte client | publique |  | existante |
| POST | `/api/auth/login` | Se connecter (client ou back-office) | publique |  | existante |
| POST | `/api/auth/mfa/verify` | Valider le second facteur | publique |  | nouvelle |
| POST | `/api/auth/refresh` | Renouveler les jetons (rotation du refresh token) | publique |  | existante |
| POST | `/api/auth/logout` | Révoquer la famille de refresh tokens | connecté |  | nouvelle |
| POST | `/api/auth/password-reset/request` | Demander une réinitialisation | publique |  | nouvelle |
| POST | `/api/auth/password-reset/confirm` | Définir un nouveau mot de passe | publique |  | nouvelle |

## Mon compte

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| GET | `/api/me` | Profil de l'utilisateur connecté | connecté |  | existante |
| PATCH | `/api/me` | Modifier mon profil | connecté |  | existante |
| PUT | `/api/me/password` | Changer mon mot de passe | connecté |  | existante |
| PUT | `/api/me/photo` | Définir ma photo de profil | connecté |  | existante |
| POST | `/api/me/mfa/enrollment` | Démarrer l'activation de la 2FA (TOTP) | connecté |  | nouvelle |
| POST | `/api/me/mfa/confirmation` | Confirmer l'activation de la 2FA | connecté |  | nouvelle |
| DELETE | `/api/me/mfa` | Désactiver la 2FA (interdit au personnel) | connecté |  | nouvelle |
| GET | `/api/me/wallet` | Mon portefeuille | connecté |  | nouvelle |
| GET | `/api/me/wallet/entries` | Relevé de mon portefeuille | connecté |  | nouvelle |
| GET | `/api/me/kyc` | État de ma vérification d'identité | connecté |  | nouvelle |
| POST | `/api/me/kyc/documents` | Déposer un document KYC | connecté |  | existante |
| GET | `/api/me/kyc/documents/{documentId}` | Un document KYC (avec lien de téléchargement signé) | connecté |  | nouvelle |

## Portefeuille

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| GET | `/api/me/wallets` | Tous mes portefeuilles (un par devise) | connecté |  | nouvelle |

## Expéditeurs

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| GET | `/api/me/senders` | Mes expéditeurs | connecté |  | existante |
| POST | `/api/me/senders` | Créer un expéditeur | connecté |  | existante |
| GET | `/api/me/senders/{senderId}` | Un expéditeur | connecté |  | existante |
| PUT | `/api/me/senders/{senderId}` | Modifier un expéditeur | connecté |  | existante |
| DELETE | `/api/me/senders/{senderId}` | Archiver un expéditeur | connecté |  | nouvelle |

## Bénéficiaires

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| GET | `/api/me/beneficiaries` | Mes bénéficiaires | connecté |  | existante |
| POST | `/api/me/beneficiaries` | Créer un bénéficiaire | connecté |  | existante |
| GET | `/api/me/beneficiaries/{beneficiaryId}` | Un bénéficiaire | connecté |  | existante |
| PUT | `/api/me/beneficiaries/{beneficiaryId}` | Modifier un bénéficiaire | connecté |  | existante |
| DELETE | `/api/me/beneficiaries/{beneficiaryId}` | Archiver un bénéficiaire | connecté |  | nouvelle |
| GET | `/api/me/beneficiaries/{beneficiaryId}/destinations` | Comptes et portefeuilles d'un bénéficiaire | connecté |  | nouvelle |
| POST | `/api/me/beneficiaries/{beneficiaryId}/destinations` | Ajouter un compte ou un portefeuille | connecté |  | nouvelle |
| PUT | `/api/me/beneficiaries/{beneficiaryId}/destinations/{destinationId}` | Modifier une destination | connecté |  | nouvelle |
| DELETE | `/api/me/beneficiaries/{beneficiaryId}/destinations/{destinationId}` | Archiver une destination | connecté |  | nouvelle |

## Transferts

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| POST | `/api/me/transfers/quote` | Calculer frais, taux et total avant d'envoyer | connecté |  | existante |
| POST | `/api/me/transfers` | Envoyer de l'argent (banque ou mobile) | connecté | oui | existante |
| GET | `/api/me/transfers` | Mes transferts | connecté |  | existante |
| GET | `/api/me/transfers/{transferId}` | Détail d'un transfert | connecté |  | existante |
| GET | `/api/me/transfers/{transferId}/receipt` | Reçu PDF | connecté |  | nouvelle |

## Recharges

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| GET | `/api/me/top-ups` | Mes recharges | connecté |  | existante |
| POST | `/api/me/top-ups` | Recharger par mobile money (Wace Pay-In) | connecté | oui | existante |
| POST | `/api/me/top-ups/proof` | Recharger avec une preuve de paiement | connecté |  | existante |
| GET | `/api/me/top-ups/{topUpId}` | Une recharge | connecté |  | nouvelle |

## Référentiels

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| GET | `/api/countries` | Pays | publique |  | existante |
| GET | `/api/countries/{countryCode}/cities` | Villes d'un pays | publique |  | existante |
| GET | `/api/countries/{countryCode}/banks` | Banques d'un pays | connecté |  | existante |
| GET | `/api/countries/{countryCode}/mobile-operators` | Opérateurs mobiles d'un pays | connecté |  | existante |
| GET | `/api/reference/transfer-purposes` | Motifs de transfert | connecté |  | existante |
| GET | `/api/reference/sources-of-funds` | Origines des fonds | connecté |  | existante |
| GET | `/api/reference/relationships` | Liens avec le bénéficiaire | connecté |  | existante |
| GET | `/api/meta/client-requirements` | Version minimale des applications | publique |  | nouvelle |
| GET | `/api/docs/openapi.yaml` | Contrat OpenAPI de cette API | publique |  | nouvelle |
| GET | `/api/web-tokens/{token}` | Lire un jeton web (service WhatsApp) | clé de service |  | existante |
| POST | `/api/web-tokens/{token}/consume` | Consommer un jeton web (service WhatsApp) | clé de service |  | existante |

## Administration

| Méthode | Route | Résumé | Accès | Idem. | Origine |
|---|---|---|---|---|---|
| GET | `/api/admin/customers` | Clients | `CUSTOMER_READ` |  | existante |
| GET | `/api/admin/customers/{customerId}` | Fiche client | `CUSTOMER_READ` |  | existante |
| PATCH | `/api/admin/customers/{customerId}` | Suspendre, réactiver ou clôturer un client | `CUSTOMER_WRITE` |  | nouvelle |
| GET | `/api/admin/parties` | Expéditeurs et bénéficiaires de tous les clients | `CUSTOMER_READ` |  | existante |
| GET | `/api/admin/parties/{partyId}` | Un expéditeur ou bénéficiaire | `CUSTOMER_READ` |  | existante |
| PUT | `/api/admin/parties/{partyId}` | Corriger un expéditeur ou bénéficiaire | `CUSTOMER_WRITE` |  | existante |
| GET | `/api/admin/customers/{customerId}/wallet` | Portefeuille d'un client | `CUSTOMER_READ` |  | nouvelle |
| GET | `/api/admin/customers/{customerId}/kyc` | Documents KYC d'un client | `KYC_REVIEW` |  | nouvelle |
| POST | `/api/admin/customers/{customerId}/kyc/documents/{documentId}/review` | Valider ou rejeter un document KYC | `KYC_REVIEW` |  | existante |
| GET | `/api/admin/transfers` | Tous les transferts | `TRANSFER_READ` |  | existante |
| POST | `/api/admin/transfers` | Saisir un transfert pour un client | `TRANSFER_CREATE` | oui | existante |
| POST | `/api/admin/transfers/quote` | Devis d'un transfert saisi pour un client | `TRANSFER_CREATE` |  | existante |
| GET | `/api/admin/transfers/{transferId}` | Détail d'un transfert (tentatives d'envoi, historique) | `TRANSFER_READ` |  | existante |
| POST | `/api/admin/transfers/{transferId}/sync` | Interroger Wace sur l'état du transfert | `TRANSFER_REFUND` |  | nouvelle |
| POST | `/api/admin/transfers/{transferId}/refund` | Rembourser un transfert échoué | `TRANSFER_REFUND` |  | nouvelle |
| GET | `/api/admin/top-ups` | Toutes les recharges | `TOPUP_REVIEW` |  | existante |
| POST | `/api/admin/top-ups/{topUpId}/approve` | Valider une recharge par preuve (crédit du portefeuille) | `TOPUP_REVIEW` |  | existante |
| POST | `/api/admin/top-ups/{topUpId}/reject` | Rejeter une recharge par preuve | `TOPUP_REVIEW` |  | nouvelle |
| GET | `/api/admin/balance-adjustments` | Ajustements de solde | `BALANCE_ADJUST_REQUEST` |  | nouvelle |
| POST | `/api/admin/balance-adjustments` | Demander un ajustement de solde | `BALANCE_ADJUST_REQUEST` | oui | existante |
| POST | `/api/admin/balance-adjustments/{adjustmentId}/approve` | Approuver (personne différente de l'initiateur) | `BALANCE_ADJUST_APPROVE` |  | nouvelle |
| POST | `/api/admin/balance-adjustments/{adjustmentId}/reject` | Rejeter un ajustement | `BALANCE_ADJUST_APPROVE` |  | nouvelle |
| GET | `/api/admin/users` | Comptes du personnel | `USER_MANAGE` |  | existante |
| POST | `/api/admin/users` | Créer un compte du personnel | `USER_MANAGE` |  | nouvelle |
| PATCH | `/api/admin/users/{userId}` | Modifier rôles ou statut | `USER_MANAGE` |  | nouvelle |
| POST | `/api/admin/users/{userId}/mfa-reset` | Réinitialiser la 2FA d'un compte | `USER_MANAGE` |  | nouvelle |
| GET | `/api/admin/audit-logs` | Journal d'audit | `AUDIT_READ` |  | existante |
| GET | `/api/admin/login-audits` | Journal des connexions | `AUDIT_READ` |  | existante |
| GET | `/api/admin/pricing-zones` | Zones tarifaires | `REFERENCE_WRITE` |  | existante |
| POST | `/api/admin/pricing-zones` | Créer une zone tarifaire | `REFERENCE_WRITE` |  | existante |
| GET | `/api/admin/fee-schedules` | Barèmes de frais | `REFERENCE_WRITE` |  | existante |
| POST | `/api/admin/fee-schedules` | Ajouter une tranche (datée) | `REFERENCE_WRITE` |  | existante |
| PATCH | `/api/admin/fee-schedules/{feeScheduleId}` | Clôturer une tranche | `REFERENCE_WRITE` |  | nouvelle |
| GET | `/api/admin/exchange-rates` | Taux de change | `REFERENCE_WRITE` |  | nouvelle |
| POST | `/api/admin/exchange-rates` | Publier un taux | `REFERENCE_WRITE` |  | nouvelle |
| GET | `/api/admin/web-tokens` | Jetons web | `TRANSFER_CREATE` |  | existante |
| POST | `/api/admin/web-tokens` | Créer un jeton web | `TRANSFER_CREATE` |  | existante |
| GET | `/api/admin/reconciliation/runs` | Réconciliations avec Wace | `RECONCILIATION_RUN` |  | nouvelle |
| POST | `/api/admin/reconciliation/runs` | Lancer une réconciliation ⚠ Wace | `RECONCILIATION_RUN` |  | nouvelle |
| GET | `/api/admin/reconciliation/runs/{runId}` | Détail d'une réconciliation | `RECONCILIATION_RUN` |  | nouvelle |
| POST | `/api/admin/reconciliation/items/{itemId}/resolve` | Résoudre un écart | `RECONCILIATION_RUN` |  | nouvelle |
| POST | `/api/admin/reference/sync` | Importer banques, opérateurs et libellés depuis Wace | `REFERENCE_WRITE` |  | nouvelle |
| GET | `/api/admin/settings` | Paramètres de la plateforme | `REFERENCE_WRITE` |  | nouvelle |
| PUT | `/api/admin/settings/{key}` | Modifier un paramètre | `REFERENCE_WRITE` |  | nouvelle |
| GET | `/api/admin/customers/{customerId}/wallets` | Portefeuilles d'un client | `CUSTOMER_READ` |  | nouvelle |
| GET | `/api/admin/customers/{customerId}/wallet/entries` | Relevé du portefeuille d'un client | `CUSTOMER_READ` |  | nouvelle |
