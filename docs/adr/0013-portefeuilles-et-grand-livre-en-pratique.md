# ADR 0013 — Portefeuilles et grand livre : mise en œuvre

- **Statut** : Accepté (mis en œuvre en phase 5) — applique l'[ADR 0004](0004-argent-et-grand-livre.md)
- **Date** : 2026-10-16

## Décisions

| Sujet | Choix | Raison |
|---|---|---|
| Porte d'entrée unique | `LedgerService.post(PostingRequest)` : **tout** mouvement d'argent passe par là (recharge, débit et remboursement d'un transfert, ajustement). Aucun autre code ne touche `wallets.balance` ni `ledger_*` | Une seule règle, un seul endroit à auditer |
| Atomicité | La transaction du grand livre, ses écritures et les soldes sont écrits **dans la transaction SQL de l'appelant** : le débit d'un transfert et sa création réussissent ou échouent ensemble. **Un échec doit faire annuler la transaction de l'appelant** (ne jamais l'attraper pour continuer) | Pas d'écriture orpheline |
| Idempotence | `reference` unique (`INSERT … ON CONFLICT DO NOTHING`) : rejeu identique = résultat initial (`replayed`) ; même référence avec un autre contenu = `LEDGER_CONFLICT`. Testé avec 8 soumissions simultanées : une seule exécution | Les rejeux (réseau, relance de tâche) ne créditent ni ne débitent deux fois |
| Solde jamais négatif | Débit **conditionnel en base** (`balance >= montant AND status = 'ACTIVE'`) plus contrainte `CHECK`. Testé : 25 débits de 100 simultanés sur 1 000 → exactement 10 passent | Sûr sous concurrence, sans verrou applicatif |
| Pas d'interblocage | Les portefeuilles d'une transaction sont mis à jour par **identifiant croissant**. Testé : 60 virements croisés concurrents | Deux transferts opposés ne se bloquent pas |
| États d'un portefeuille | `ACTIVE` : tout ; `FROZEN` : reçoit mais ne dépense pas ; `CLOSED` : rien | Gel conservatoire sans perdre d'argent entrant |
| Équilibre | Vérifié avant d'écrire (par devise, `PostingRules`) puis par la base au COMMIT ; les écritures sont **immuables** (déclencheurs) | Défense en profondeur |
| Comptes de la plateforme | Un par devise, **créés à la demande** : `PLATFORM_FEE_REVENUE` (XAF, historique) et `PLATFORM_FEE_REVENUE.EUR`… | Le schéma n'en seedait que pour XAF |
| Devises | Un portefeuille par client et par devise, créé à la première lecture. Le **portefeuille principal** est celui de la devise du pays de résidence (**hypothèse 17**) ; `?currency=` choisit un autre | Contrat d'origine mono-portefeuille, modèle multi-devises |
| Ajustements manuels | Demande (`BALANCE_ADJUST_REQUEST`) puis approbation par **une autre personne** (`BALANCE_ADJUST_APPROVE`) : règle appliquée dans le service **et** par contrainte de la base. Rien n'est écrit avant l'approbation ; refuser exige un motif ; un débit supérieur au solde ne s'approuve pas (la demande reste en attente) | Aucun mouvement manuel par une seule personne |
| Idempotence des demandes | `Idempotency-Key` conservée avec l'empreinte du contenu : même clé + même contenu = même demande ; même clé + contenu différent = 422 | Double clic, relance réseau |
| Devis | `QuoteService` (module transfert) : devise = portefeuille principal, barème = pays de destination, `sufficientFunds` = solde ≥ total à débiter. Un devis **ne réserve rien** : seule la création du transfert (phase 6) débite, de façon atomique | Le serveur fait foi, pas de fonds bloqués par un simple calcul |

## Limites connues

- **Aucune écriture d'ouverture** : les soldes migrés de l'ancien système seront posés par l'ETL (`OPENING_BALANCE`) ; en attendant, tous les portefeuilles démarrent à zéro.
- **Un devis peut devenir faux** entre son calcul et la création du transfert (taux ou barème modifié, solde dépensé). La création **recalcule** (phase 6) et refuse si le total diffère de `expectedTotalDebit`.
- **Plafonds par transfert, par jour, par client** : non appliqués ici (phase 6, paramètre `transfer.max_amount_per_transfer`).
- **Les comptes de la plateforme n'ont pas de solde matérialisé** : leur solde se calcule par la somme des écritures (rapport de marge, phase 9).
- **Pas de portefeuille créé à l'inscription** : il apparaît à la première lecture ou au premier mouvement.
- **Lecture qui écrit** : `GET /me/wallet` crée le portefeuille manquant (idempotent, sans effet financier).
