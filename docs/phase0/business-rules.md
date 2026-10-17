# Règles métier à reproduire

Extraites du code Symfony actuel (`TransferService`, `BalanceService`, `FeeCalculator`, `AccountAccess`, `PartyMapper`, contrôleurs `/v2`). Chaque règle est couverte par un test existant (colonne « Test ») qui devient sa spécification exécutable dans Spring.

## Modèle de personnes

- **Titulaire** : ligne `customer` avec `customeralt` nul ; peut avoir un compte (`compte_id` → `user`). 113 en base, dont 80 avec compte. Porte le solde (`customer.balance`).
- **Expéditeur** : ligne `customer` avec `customeralt` = id du titulaire, sans compte (26). Un transfert est enregistré **au nom de l'expéditeur**, et c'est le titulaire qui est débité.
- **Bénéficiaire** : ligne `contactcustomer` dont `customer` est le titulaire.
- Type de personne `P` (physique) ou `B` (entreprise). Type métier d'une transaction : `P2P`, `P2B`, `B2P`, `B2B` selon expéditeur → bénéficiaire.

## Transfert (`TransferService`) — Test : `TransferTest`, `OwnershipTest`

1. Champs obligatoires : `sender_id`, `beneficiary_id`, `amount`, `rate`, `total_amount`, `wallet`, `origin_fond`, `motif`, `relaction`, `country_id`, `city_id`, `operator_id`, et le numéro de compte (`acount_number`, **avec la faute de frappe envoyée par Android**, ou `account_number`).
2. Montants : décimaux à 2 chiffres au plus, `amount > 0`, `rate ≥ 0`, `|total_amount − (amount + rate)| ≤ 0,01`. Le débit est `amount + rate` calculé côté serveur.
3. Propriété : l'expéditeur a pour titulaire l'utilisateur connecté, le bénéficiaire appartient au même titulaire (sinon 403).
4. Solde suffisant, débit **atomique** : `UPDATE customer SET balance = ROUND(COALESCE(balance,0) − x, 2) WHERE id = ? AND COALESCE(balance,0) ≥ x` ; 0 ligne modifiée = « Solde insuffisant ».
5. Transfert **bancaire** : l'expéditeur et le bénéficiaire doivent avoir tous leurs champs d'identité (type de compte, adresse, date de naissance, pièce, expiration, etc.).
6. La création de la transaction et le débit sont dans **une seule transaction SQL**.
7. Numéro de transaction : 12 chiffres tirés avec un générateur sécurisé, vérifiés uniques ; `numerotransaction = "WTC" + jjmmaaaa + numéro` ; pour un transfert bancaire `numeroidentifiant = numerotransaction`, pour un transfert mobile `numeroidentifiant = numéro`. Fuseau `Africa/Brazzaville`.
8. Appel du partenaire (Wace) : succès = `status == 2000` ; on enregistre `partenairenumero` et le statut passe à `validation`. Échec : statut `echoue` **et remboursement une seule fois** (`UPDATE … WHERE status = 'en validation'` : si 0 ligne modifiée, aucun crédit).
9. Notifications (WhatsApp, mail) **après** le succès, sans jamais le faire échouer.
10. Frais : tranche de la grille `grilletarifaire` de la zone du pays (`trancheA < montant ≤ trancheB`), `frais` = pourcentage (4 à 10 en base), arrondi au centime, demi supérieur. Contrôle strict si `TRANSFER_ENFORCE_GRID_FEE=1` (désactivé par défaut car on ignore si les frais Android suivent exactement la grille).
11. Transfert saisi par un admin (`/api/admin/transactions/bank`) : mêmes validations, **sans débit ni remboursement**.

### Décisions métier à prendre avant le portage

- **Réponse vide ou nulle du partenaire** : l'ancien code rembourse. Si le partenaire a en réalité traité l'ordre (délai d'attente), on rembourse à tort. Proposition : ne rembourser que sur refus explicite, laisser `en validation` sinon, et réconcilier par la tâche de suivi.
- **Annulation côté Wace** (`CANCELED`) : la commande de suivi passe la transaction à `echoue` **sans remboursement**. Proposition : rembourser une seule fois, de façon idempotente, comme pour le refus immédiat.

## Statuts

- **Transaction** (colonne texte libre) : `en validation` → `validation` (acceptée par le partenaire) → `effectue` (payée) ou `echoue` ; `en attente` (ancien flux). La base contient aussi les vocabulaires de partenaires : `SUCCESS` (30), `PAID` (75), `FAILED` (5), `CANCELED`, `PROCESSING`, `LOCKED`. **Mapper tolérant** (stocker la chaîne, interpréter les valeurs connues).
- **Recharge (`prefunding`)** : `PENDING`, `ACCEPTED`, `PROCESSING`, `CANCEL`, `WAITING_PROOF`, `DELETE`.
- **Jeton web** : `pending`, `used`, `expired` (verrou pessimiste lors de la consommation).
- **Compte client** : `isverify` bascule « Verified / Not verified » (vue admin : `ACCEPTED` / `PENDING`) ; la pièce d'identité périmée est signalée au login (`verification`).

## Taux et frais (`GET …/exchange-rate`)

Réponse : `rate` (pourcentage de la tranche pour le montant `amount`, ou 0), `taux_xaf_usd` (`configuration.tauxplatform`, dernier enregistrement), `taux_country` (`tauxechange.montant` de la zone du pays). Le **client calcule lui-même** frais et total.

## Recharges

- Création par carte (Stripe Checkout, mode `STRIPE`, `numero` de 8 caractères hexadécimaux) ou par preuve (mode au choix, fichier image).
- Le **crédit du solde** n'est pas défini dans le contrat courant (voir [integrations.md](integrations.md#constat--le-crédit-dune-recharge-nest-pas-défini-dans-le-contrat-courant)).
- Ajustement manuel admin : `add` crédite, `sub` débite avec contrôle de solde, montant > 0, action journalisée.

## Soldes

`customer.balance` (DOUBLE) est le solde utilisé par tous les flux courants. `balance_customer.solde` (un solde par pays, 54 lignes) n'est lu et écrit que par des routes `/v1` : **à ne pas porter** sauf preuve d'usage.

## Formats de réponse à conserver pendant la migration des frontends

- Succès : `{ "status": "success", "code": 200, "message": "OK", "data": … }` ; erreur : `{ "status": "error", "code": …, "message": …, "error": { "details": … } }`.
- Les valeurs nulles sont **omises** des objets JSON (les clients Android déclarent `phone`, `status`, `mode`, `date` non nullables : envoyer des chaînes vides).
- Dates : `"Y-m-d"` pour `date`, `"D d,M,y h:i"` pour `datetransaction` côté client, `"Y-m-d H:i:s"` côté admin ; montants en nombres JSON.
- Les frontends étant modifiés, ces formats pourront être unifiés, mais **uniquement après** la comparaison à l'identique (golden master).
