# Intégrations partenaires

Statut établi par analyse du code (qui injecte quel service) et par les données de la base locale. **Aucun journal de production n'a été consulté** : la colonne « Décision » est une recommandation à confirmer avec `app:legacy-usage` et les tableaux de bord des partenaires.

Légende : **courant** = utilisé par le contrat conservé (routes `/v2`, `/v2/admin`, `/api`, commandes actives) ; **legacy** = utilisé seulement par des routes `/v1` (non portées) ; **mort** = aucune référence.

| Partenaire | Rôle | Classes Symfony | Statut | Preuves en base | Décision |
|---|---|---|---|---|---|
| **Wace Pay** (payout) | Envoi d'un transfert vers une banque (`api/v1/transaction/bank/create`) ou un wallet (`wallet/create`) ; consultation du statut (`transaction/status/{ref}`) | `WacepayV2Service` (envoi : `TransferService`, `ApiTransactionController`, commandes de synchronisation) ; `WacepayCurlService` / `WacePayService` (consultation du statut dans la commande active) ; `WacepayV22CurlService` | **courant** | `transaction.typeservice` = `WACEPAY`/`wace` (≈ 200 lignes) ; `operateur.gateway = WACEPAY` (1 264 lignes) ; `wace_data` (309) | **PORTER en priorité 1.** Un seul client Spring remplace les 4 classes |
| **Wace Pay-In** | Collecte d'un paiement mobile (`createPayment`) | `WacePayInService` ← `PayInController` (`POST /collecte/create`, **anonyme**) | courant (route à sécuriser) | aucune transaction typée | À DÉCIDER : exposer uniquement à un client authentifié |
| **Stripe** | Recharge par carte (Checkout) ; webhook | SDK appelé directement par `PrefundingController` ; `StripeService`/`CustomerService`/`ProductService` pour l'ancien flux | courant | `prefunding` : 2 lignes (statut `PENDING`), mode non vérifié | PORTER la création de session. **Le webhook ne crédite rien** (voir « Constat » ci-dessous) |
| **Telegram** | Messages à l'admin (nouvelle transaction, etc.) ; webhook de bot | `TelegramService`, `TelegramNotifyService`, `TelegramController` | courant | `user.chat_id` | PORTER (notifications) ; le webhook du bot n'a aucun secret |
| **WhatsApp (WABA)** | Confirmation d'envoi et de réception de fonds au client | `WabaService` ← `TransferService`, commande de synchronisation | courant | — | PORTER |
| **Mailjet** | Courriel d'initialisation d'un transfert (`sendInitMail`) | `Mailservice` ← `TransferService` | courant | — | PORTER (Mailjet API ou SMTP) |
| **PayDunya** | Paiement mobile (recharge et transfert) ; callbacks `auth/notifyurl/paydunya` et `.../notifyurlprefund/paydunya` | `PaydunyaService`, `PaydunnyaService` (2 classes quasi identiques) | legacy + callbacks encore routés | 13 + 16 transactions (`paydunya`/`PAYDUNYA`), 11 opérateurs | À DÉCIDER. Les callbacks n'ont **aucune signature** |
| **PayCI** | Paiement mobile ; callbacks `auth/notifyurl/ajax` et `.../payci` | `PayciService` | legacy + callbacks | ≈ 36 transactions (`payci`) | À DÉCIDER |
| **Agensic Pay** | Paiement mobile (Congo/RDC), vérification `getPayID` | `PaymentApiService` | legacy (+ commande, appel désactivé) | 26 transactions `AGENSICPAY`, 3 opérateurs | À DÉCIDER |
| **CinetPay** | Recharge ; callback `auth/notifyurl/cinetpay` (crédite le solde par pays sans vérification) | `CinetPayService` | legacy + callback | — | À DÉCIDER |
| **Maxicash** | Collecte de paiement (`makecollet/maxicash`) ; vérification TLS désactivée | `MaxicashService` | legacy | — | NE PAS PORTER sauf preuve d'usage |
| **Twilio Verify / Authy / Twilio SMS / Infobip** | OTP et SMS | `VerifyService`, `AuthyHelper`, `TwillosmsService`, `ClientInfobip` | legacy (`/auth/*` OTP sans appelant ; `authy_id` nul pour 96/96 utilisateurs) | — | NE PAS PORTER d'emblée ; reprendre si l'OTP est requis |
| **Google OAuth** | Connexion Google (`auth/google`) | `league/oauth2-google` | legacy, aucun appelant | — | NE PAS PORTER |
| **Flutterwave, AltMoney, FCM, WhatsApp (`WhassapService`)** | — | `FlutterwaveService`, `AtlmoneyService`, `PushFcmNotificationService`, `WhassapService` | **mort** (aucune référence) | — | SUPPRIMER |

## Constat : le crédit d'une recharge n'est pas défini dans le contrat courant

- `POST /v2/recharges` crée une recharge `PENDING` et une session Stripe Checkout. Le webhook `POST /webhook/stripe` vérifie la signature puis **ne fait rien** (`// Traiter la commande ici`).
- Les seuls chemins qui créditent `customer.balance` sont : `/v1/prefrundings/admin/validate`, `/v1/prefrundings/{id}/validate`, `/v1/statusscripe/{id}/{status}` (routes `/v1`, non portées, plus appelées par le web), le callback `notifyurlprefund/paydunya`, la commande `app:callback-notification` (appels Agensic/PayDunya désactivés) et `POST /v3/update-balance` (ajustement manuel admin).
- **Question à trancher avant la phase 5** : comment un client est-il réellement crédité aujourd'hui (ajustement manuel ? ancien portail ? autre application) ? Le nouveau backend doit définir ce flux explicitement (validation admin d'une recharge, webhook Stripe idempotent, etc.).

## Contraintes d'intégration à reproduire

- **Wace** : jeton obtenu par login à chaque appel (`authenticate()`), succès = `status == 2000`, **aucune nouvelle tentative sur un `POST`** de paiement, statuts de suivi `PAID` / `CANCELED` (autres valeurs vues en base : `PROCESSING`, `LOCKED`, `SUCCESS`, `FAILED`).
- Réponse partenaire vide ou nulle : l'ancien code **rembourse** (risque de double paiement en cas de délai d'attente ; décision à prendre, voir [business-rules.md](business-rules.md)).
- Les chemins de **callback** sont enregistrés chez les partenaires (ou générés par `generateUrl`) : les conserver tel quel jusqu'à reconfiguration, en alias des nouvelles routes `/api/webhooks/*`.
- Secrets (noms seulement, valeurs dans l'environnement) : `WACEPAY_URL/USERNAME/PASSWORD`, `WACEINPAY_URL/USERNAME/PASSWORD`, `STRIPE_SECRET_KEY` (+ secret de webhook, **actuellement écrit en dur dans le code**), `PAYDUNYA_*`, `PAYCIURL/PAYCIAPIKEY`, `CINET_*`, `MAXI_URL/MERCHANT_*`, `MJ_APIKEY_PUBLIC/PRIVATE`, `TELEGRAM_TOKEN/CHATID_ADMIN`, `WABA_URL/APIKEY/PHONE_ADMIN`. Le fichier `.env` actuel contient des valeurs réelles : **rotation recommandée** avant tout partage.
