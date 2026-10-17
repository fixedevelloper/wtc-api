# Glossaire

Vocabulaire commun entre le métier, le contrat d'API, le schéma et le code. Les identifiants techniques sont en anglais ; la colonne « Ancien terme » aide à lire le code et les données Symfony.

| Terme métier | Identifiant technique | Définition | Ancien terme |
|---|---|---|---|
| Client | `customer` | Titulaire d'un compte WTC. Il détient les portefeuilles et possède ses expéditeurs et bénéficiaires. Peut être une personne (`INDIVIDUAL`) ou une entreprise (`BUSINESS`) | `customer` avec `customeralt` nul |
| Utilisateur | `user` | Identité de connexion (client ou personnel). Un client peut ne pas avoir d'accès (fiche créée par le back-office) | `user` |
| Partie | `party` | Personne ou entreprise intervenant dans un transfert, avec un rôle | `customer` (expéditeur), `contactcustomer` |
| Expéditeur | `party` de rôle `SENDER` | Personne pour le compte de qui le client envoie de l'argent (souvent le client lui-même) | `customer` avec `customeralt` renseigné |
| Bénéficiaire | `party` de rôle `BENEFICIARY` | Personne qui reçoit l'argent | `contactcustomer` |
| Destination de versement | `payout_destination` | Compte bancaire ou portefeuille mobile d'un bénéficiaire | champs `bank*` de `contactcustomer` |
| Portefeuille | `wallet` | Solde d'un client dans une devise | `customer.balance`, `balance_customer` |
| Grand livre | `ledger` | Registre immuable d'écritures à double entrée qui explique tous les soldes | — |
| Écriture | `ledger_entry` | Débit ou crédit d'un compte pour un montant | — |
| Compte de grand livre | `ledger_account` | Compte d'un portefeuille client ou de la plateforme (compensation, frais, ajustements) | — |
| Transfert | `transfer` | Envoi d'argent d'un expéditeur vers une destination, via Wace | `transaction` |
| Devis | `quote` | Calcul serveur des frais, du taux et du total avant l'envoi | calcul fait par le client |
| Frais | `fee` | Montant prélevé en plus du montant envoyé, issu du barème | `fraisenvoi` |
| Barème de frais | `fee_schedule` | Pourcentage par tranche de montant, par zone tarifaire, daté | `grilletarifaire` |
| Zone tarifaire | `pricing_zone` | Groupe de pays partageant un barème et des taux | `zone` |
| Taux de change | `exchange_rate` | Valeur d'une devise dans une autre à une date | `tauxechange` |
| Total débité | `total_debit` | Montant envoyé + frais, retiré du portefeuille | `montanttotal` |
| Recharge | `top_up` | Crédit du portefeuille (Wace Pay-In, preuve manuelle validée) | `prefunding` |
| Ajustement de solde | `balance_adjustment` | Correction manuelle d'un solde, avec double validation | `update-balance` |
| Envoi (payout) | `payout` | Ordre de paiement émis vers Wace pour verser le bénéficiaire | création chez Wace |
| Encaissement (pay-in) | `payin` | Collecte d'un paiement mobile auprès d'un client via Wace | `WacePayIn` |
| Tentative d'envoi | `payout_attempt` | Un appel à Wace avec son résultat (accepté, refusé, ambigu, erreur) | — |
| Réponse ambiguë | `AMBIGUOUS` | Délai d'attente ou réponse vide de Wace : on ne sait pas si l'ordre a été pris en compte | — |
| Réconciliation | `reconciliation` | Rapprochement quotidien de nos transferts avec ceux de Wace, écarts à résoudre | — |
| Outbox | `outbox_event` | Événement écrit dans la même transaction que le débit puis émis par un travailleur | — |
| Idempotence | `Idempotency-Key` | Garantie qu'un rejeu de la même demande ne produit pas deux effets | — |
| KYC | `kyc` | Vérification d'identité : documents déposés puis validés ou rejetés par le personnel | `isverify`, `piecefacefile` |
| Type de transfert | `party_type_pair` | `P2P`, `P2B`, `B2P`, `B2B` selon que l'expéditeur et le bénéficiaire sont des personnes (P) ou des entreprises (B) | `business_type` |
| Motif, origine des fonds, lien | `reference_values` | Libellés exigés par Wace, dépendant du type de transfert | `wace_data` (`raison`, `origin_fonds`, `relaction`) |
| Jeton web | `web_token` | Lien à usage unique qui pré-remplit un transfert (canal WhatsApp) | `web_token` |
| Personnel | `staff` | Utilisateurs avec un rôle d'administration (`SUPPORT`, `ADMIN`, `SUPER_ADMIN`) | |

## Statuts d'un transfert

| Statut | Sens |
|---|---|
| `CREATED` | Demande validée, portefeuille débité, ordre pas encore placé dans l'outbox |
| `PAYOUT_PENDING` | Ordre dans l'outbox, en attente d'émission vers Wace |
| `PROCESSING` | Ordre accepté (ou réponse ambiguë) ; le résultat final est attendu de Wace |
| `COMPLETED` | Wace a confirmé le paiement |
| `FAILED` | Wace a refusé ou annulé ; remboursement dû |
| `REFUNDED` | Portefeuille recrédité (une seule fois) |
| `CANCELLED` | Annulé avant envoi, ou statut historique non résolu après migration |

## Conventions de nommage

- Tables : `snake_case` au pluriel ; colonnes : `snake_case` ; énumérations : `UPPER_SNAKE_CASE`.
- API : `camelCase` ; routes en `kebab-case` ; collections au pluriel ; sous-ressources imbriquées une seule fois.
- Identifiants exposés : UUID. Références lisibles : `WTC-AAAA-NNNNNN`.
