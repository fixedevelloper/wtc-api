# ADR 0012 — Tarification et taux de change

- **Statut** : Accepté (mis en œuvre en phase 4)
- **Date** : 2026-10-16

## Décisions

| Sujet | Choix | Raison |
|---|---|---|
| Où vit le moteur | Module `reference` (`PricingService`), appelé par le transfert pour les devis **et** pour figer les montants à la création | Une seule règle de calcul, jamais dupliquée dans un client |
| Zone tarifaire | Celle du **pays de destination** ; la devise du barème est celle du **portefeuille source** (hypothèse 15) | Le barème suit le corridor ; l'ancien code lisait « la zone du pays » |
| Tranche | `min < montant ≤ max` : la frontière appartient à la tranche basse, jamais aux deux (contrainte d'exclusion en base) | Pas d'ambiguïté ni de chevauchement |
| Frais | `montant × pourcentage / 100 + frais fixe`, arrondi **demi supérieur** à l'unité de la devise (XAF/XOF : 0 décimale, EUR/USD : 2) | Règle de l'ancien code, étendue aux devises sans centimes |
| Montant reçu | `montant × taux`, arrondi **vers le bas** ; refusé s'il serait nul | On ne promet jamais au bénéficiaire plus que le taux ne donne ; un aller-retour ne crée jamais d'argent (testé) |
| Taux | Dernier taux en vigueur (direct, sinon **inverse** de la paire opposée, 20 chiffres significatifs). Un taux n'est **jamais modifié** : un nouveau prend le relais à sa date d'effet. Les taux futurs sont ignorés | Historique exact des devis |
| Barèmes | Jamais supprimés ni antidatés ; on y met fin (`effectiveTo`) pour les raccourcir seulement | Les transferts passés référencent leur tranche |
| Paramètres | Seuls les paramètres existants changent, chacun avec un **validateur de type** ; toute modification journalise l'ancienne et la nouvelle valeur | Un plafond ne devient pas un texte |
| Permission | `REFERENCE_WRITE` (rôle `ADMIN` et `SUPER_ADMIN`) pour tout ce qui précède | Voir l'ADR 0006 |

## Conséquence de la spécification Wace (voir [wace-integration.md](../design/wace-integration.md))

Wace n'expose **aucun devis ni taux**. Nos taux (`exchange_rates`) sont saisis par l'administration et **doivent être alignés** sur ceux de Wace : à la création d'un ordre, le montant reçu renvoyé par Wace (`amount_receive`) sera comparé à notre calcul et tout écart signalé avant confirmation (phase 6). Les frais de Wace (`feeds`) sont un **coût** de la plateforme, distinct des frais facturés au client.

## Limites connues

- **Les routes de devis HTTP** (`POST /api/me/transfers/quote`, `POST /api/admin/transfers/quote`) ne sont **pas encore exposées** : leur réponse contient le solde disponible et « fonds suffisants », qui viennent du portefeuille (phase 5). Le moteur est livré et testé ; les routes arrivent avec le portefeuille.
- **`POST /api/admin/reference/sync`** (import des motifs, relations et origines depuis Wace) dépend de l'intégration (phase 6) ; les listes se lisent déjà.
- **Plafond par transfert** (`transfer.max_amount_per_transfer`, en devise par défaut) : paramètre validé et modifiable, **appliqué à la création des transferts** (phase 6), pas dans le devis.
- **Triangulation de devises** non prévue : une paire sans taux direct ni inverse est refusée (`EXCHANGE_RATE_UNAVAILABLE`) plutôt que devinée via une devise pivot.
- **Un taux ne s'annule pas** : une erreur de saisie se corrige par un nouveau taux immédiat ; les devis émis entre-temps l'ont utilisée.
