# ADR 0005 — Wace Pay comme seul fournisseur de paiement

- **Statut** : Proposé, amendé le 2026-10-16 (voir l'addendum)
- **Date** : 2026-10-16

## Contexte
Le code actuel intègre une dizaine de partenaires (Stripe, PayDunya, PayCI, Agensic, CinetPay, Maxicash…), dont la plupart ne sont plus utilisés par les clients courants. Les callbacks n'ont aucune signature et les crédits ne sont pas idempotents.

## Décision
- **Wace Pay** assure l'**envoi** (virement bancaire, portefeuille mobile) et l'**encaissement** (Pay-In mobile money). Aucun autre fournisseur de paiement n'est repris.
- Le domaine parle à deux **ports** (`PayoutGateway`, `PayInGateway`) ; l'adaptateur Wace est dans `infrastructure`. Un **faux adaptateur** sert aux tests ; un second fournisseur pourra être ajouté sans toucher au domaine.
- **Pattern outbox** : l'ordre Wace est écrit dans `outbox_events` dans la même transaction que le débit, puis émis par un travailleur. Aucun appel réseau dans une transaction de base de données.
- **Aucune nouvelle tentative automatique d'un ordre de paiement.** Une réponse ambiguë (délai, réponse vide) laisse le transfert en `PROCESSING` ; la **réconciliation** (webhook, interrogation, rapprochement quotidien) tranche. Le remboursement n'est autorisé que sur un statut confirmé, une seule fois.
- Webhooks : signature vérifiée avant tout traitement, événements dédoublonnés (`webhook_events`), journal des échanges avec masquage.
- La spécification officielle de Wace (statuts, callbacks, signatures, sandbox) est un **prérequis** des phases d'implémentation (`x-pending` dans le contrat).

## Conséquences
+ Surface d'intégration réduite à un partenaire, comportement financier prévisible.
− Dépendance à un seul fournisseur : la continuité de service repose sur Wace (à traiter dans le plan de reprise).
− Les recharges par carte (Stripe) disparaissent ; seuls Wace Pay-In et la preuve manuelle subsistent.

## Addendum du 2026-10-16 — après lecture de la spécification officielle

La spécification (v2.0.0, voir [wace-integration.md](../design/wace-integration.md)) **infirme ou précise** plusieurs points ci-dessus : **aucun webhook** n'est décrit (le suivi par interrogation devient le mécanisme principal, la signature des webhooks et la table `webhook_events` restent sans objet tant que Wace n'en documente pas) ; la création d'un ordre est suivie d'une **confirmation** obligatoire ; il n'existe **ni clé d'idempotence ni référence fournie par le partenaire** (l'envoi banque accepte un `comment` de 160 caractères qui peut porter notre référence, pas l'envoi mobile) ; expéditeurs et bénéficiaires doivent être **créés chez Wace** avant l'envoi ; **aucune API de devis** : le montant reçu n'est connu qu'à la création. La règle « aucune nouvelle tentative automatique d'une création » est **confirmée et renforcée**.

## Alternatives écartées
Conserver plusieurs fournisseurs « au cas où » (code mort et risques de sécurité sans bénéfice démontré).
