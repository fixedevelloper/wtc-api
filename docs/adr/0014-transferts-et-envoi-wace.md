# ADR 0014 — Transferts et envoi Wace

- **Statut** : Accepté (mis en œuvre en phase 6) — applique l'[ADR 0005](0005-wace-seul-fournisseur.md) et la [spécification Wace](../design/wace-integration.md)
- **Date** : 2026-10-16

## Décisions

| Sujet | Choix | Raison |
|---|---|---|
| Création | **Une seule transaction SQL** : validation, recalcul du devis, débit du portefeuille, ligne du transfert, historique, ordre d'envoi dans la boîte d'envoi (`outbox_events`). **Aucun appel réseau** pendant ce temps | Si une étape échoue rien n'est débité ; si le processus meurt après le commit, l'ordre existe et sera traité |
| Pré-conditions | Compte actif, **KYC vérifié**, contacts complets selon **les exigences de Wace** (`WaceRules`), banque ou opérateur **configurés** (codes Wace), valeurs de référence connues, plafond par transfert respecté, devis inchangé (`expectedTotalDebit`) | Mieux vaut refuser avant de débiter que d'échouer chez Wace après |
| Idempotence | `Idempotency-Key` + empreinte du contenu par client ; mêmes clé et contenu = même transfert ; contenu différent = 422 ; 10 soumissions simultanées = 1 transfert, 1 débit (la perdante est annulée avec son débit) | Double clic, relance réseau |
| Instantané | Identités, destination, barème, taux et **valeurs Wace** figés à la création ; les numéros y sont **chiffrés** (contexte propre au transfert) | Modifier un contact après coup ne change ni ce qui est payé ni le reçu |
| Envoi | Travailleur (`PayoutProcessor`) : déclare l'expéditeur puis le bénéficiaire chez Wace (une fois, codes mémorisés dans `provider_party_refs`), **crée l'ordre**, puis **le confirme** (étape obligatoire de la spécification) | Séquence imposée par Wace |
| Tentative écrite avant l'appel | Une ligne `payout_attempts` pessimiste (« ambiguë ») est **validée avant** l'appel réseau | Si le processus meurt pendant l'appel, la trace interdit de recréer l'ordre |
| Issues d'une création | `OK` → suivi ; `REJECTED` (refus explicite, codes de validation/configuration de la spécification) → **échec + remboursement unique** ; `NOT_PROCESSED` (connexion impossible, authentification, limite de débit) → nouvel essai avec délai croissant, puis échec + remboursement après 5 essais (l'ordre n'a jamais existé) ; **`AMBIGUOUS` (délai, réponse illisible, code 1005, tout ce qui est incertain) → transfert bloqué, ni nouvel essai ni remboursement** | Un ordre ne se crée jamais deux fois ; on ne rembourse pas ce qui a peut-être été payé |
| Blocages (`payout_hold`) | `AMBIGUOUS_CREATE`, `AMOUNT_MISMATCH`, `PROVIDER_LOCKED`, `CONFIRM_FAILED` : visibles du back-office, **jamais résolus automatiquement** (sauf `PROVIDER_LOCKED` qui se lève si Wace débloque) | Décision humaine pour tout ce qui engage l'argent |
| Montant annoncé | À la création Wace renvoie le montant livré : s'il **diffère** du devis, l'ordre **n'est pas confirmé** et reste bloqué | Les taux de Wace ne sont pas interrogeables à l'avance (voir wace-integration.md) |
| Confirmation | Rejouable sans danger (déjà confirmé = succès) : nouvel essai avec délai, blocage `CONFIRM_FAILED` après 8 essais ; une confirmation perdue est remise en file par le suivi | Spécification : codes 204 et 1016 |
| Suivi | Pas de webhook : interrogation périodique de chaque ordre (`GET /transaction/status`). `PAID` → terminé ; `CANCEL` → échec + remboursement ; `LOCKED` → blocage conformité, **aucun remboursement** ; inconnu du fournisseur → blocage | Seul mécanisme que décrit la spécification |
| Remboursement | `FAILED → REFUNDED` en **une instruction conditionnelle** avec l'écriture du grand livre (référence unique `transfer:<id>:refund`) : impossible deux fois, même en concurrence | ADR 0004 |
| Remboursement manuel | `POST /admin/transfers/{id}/refund` (permission `TRANSFER_REFUND`, motif ≥ 5 caractères, audité) : sur un transfert `FAILED`, ou sur un transfert bloqué en `AMBIGUOUS_CREATE` **après vérification dans le back-office Wace qu'aucun ordre n'existe**. **Jamais** pour un ordre en cours chez Wace | Seule porte pour un ordre à l'issue inconnue |
| Rattachement manuel | `POST /admin/transfers/{id}/sync` avec `providerReference` : rattache l'ordre retrouvé chez Wace à un transfert bloqué en `AMBIGUOUS_CREATE` et reprend le suivi | Évite un remboursement à tort |
| Retrouver un ordre | Le **numéro du transfert est envoyé dans le commentaire** de l'envoi bancaire (reçu du bénéficiaire) ; l'envoi mobile n'a aucun champ équivalent | Seule trace côté Wace |
| Fournisseur choisi explicitement | `wtc.payment.provider` = `wace` ou `fake`, **sans défaut** : démarrage refusé sinon ; le faux fournisseur est pilotable (tests, développement) | Jamais de faux fournisseur par accident |
| Travailleurs | Planifiés (5 s / 30 s), plusieurs instances possibles (`SKIP LOCKED` + verrou de ligne), désactivables | Montée en charge sans doublon |

## Limites connues

- **L'adaptateur Wace n'a jamais parlé au vrai service.** Il est validé contre un faux serveur qui rejoue les exemples de la spécification (19 tests : statuts, codes, délais, masquage). Avant la production il faut un passage complet sur la **recette** de Wace.
- **Pas d'annulation d'ordre** dans la spécification : un ordre non confirmé (montant différent) reste chez Wace ; son sort (expiration, annulation) est à confirmer avec eux.
- **Liste incomplète des types de pièce** : seuls `PP`, `CNI`, `RCCM` sont acceptés (les autres types sont refusés à la création, pas devinés).
- **Code « payeur »** : obtenu par `POST /transaction/payercode` (section VII de la spécification, pays + service + devise). `POST /api/admin/reference/sync` l'importe pour chaque pays (service `B`, banque) et chaque opérateur mobile rapproché d'un service Wace par code court ou par nom ; les opérateurs sans correspondance sont **signalés, jamais devinés**. Les noms de champs suivent l'exemple de la spécification (`toCountry`, `toCurrency`), qui diffère de son tableau (`payoutCountry`, `CurrencyPayout`) : à vérifier sur la recette.
- **Frais de Wace** (`feeds`) : enregistrés (`transfers.provider_fee`) mais pas encore comptabilisés au grand livre (charge à poser au règlement, phase 9).
- **Réconciliation par liste** impossible : Wace n'expose aucun relevé de transactions ; on interroge chaque ordre. `POST /admin/reconciliation/runs` reste non implémenté.
- **Reçu PDF** (`GET /me/transfers/{id}/receipt`), **notifications** au client et **import des référentiels Wace** : phases suivantes.
- **Un échec du remboursement** (portefeuille clos) laisse le transfert en `FAILED` à traiter à la main.
