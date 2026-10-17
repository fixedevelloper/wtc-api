# ADR 0008 — Bascule unique et traitement des anciennes applications

- **Statut** : Proposé (basé sur les valeurs par défaut de [assumptions.md](../design/assumptions.md), à confirmer)
- **Date** : 2026-10-16

## Contexte
Le schéma change et la base n'est plus partagée ; les frontends sont modifiés ; le volume est faible. Un étranglement route par route n'est ni nécessaire ni possible sans double écriture entre deux schémas différents.

## Décision
1. Construire le nouveau système et le valider en **recette** avec une copie de production migrée par l'ETL (répétée autant que nécessaire, avec rapports de réconciliation : comptes, soldes, nombre et totaux de transactions).
2. **Bascule** : gel des écritures sur Symfony (fenêtre de quelques heures), ETL final, vérifications, changement de DNS, publication simultanée du web et d'Android.
3. Symfony reste disponible en **lecture seule** quelques semaines (retour arrière, audit, litiges).
4. **Anciennes versions d'Android** : avant la bascule, une version intermédiaire affiche « mise à jour requise » ; à défaut, une **couche de compatibilité** temporaire `/v2/*` (module séparé, supprimé à échéance). Choix à confirmer selon le parc d'appareils.
5. Plan de retour arrière écrit et répété : tant que Symfony est en lecture seule et que l'ETL est rejouable, le retour est possible dans la fenêtre de bascule.

## Conséquences
+ Pas de double écriture ni de synchronisation permanente entre schémas.
− Une fenêtre d'indisponibilité courte est inévitable ; elle doit être annoncée.
− Les transferts en cours au moment du gel doivent être traités (attendre leur terminaison ou les migrer en `PROCESSING` avec leur référence Wace).

## Alternatives écartées
Migration progressive avec proxy (nécessite deux sources de vérité simultanées) ; bascule sans répétition (risque inacceptable pour des soldes).
