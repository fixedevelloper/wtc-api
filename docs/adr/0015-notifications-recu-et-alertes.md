# ADR 0015 — Notifications, reçu PDF et alertes d'exploitation

Statut : accepté (phase 8).

## Contexte
Le client doit être prévenu (transfert enregistré, payé, remboursé ; identité vérifiée ; réinitialisation de mot de passe), l'équipe alertée quand un humain doit intervenir, et le client doit pouvoir télécharger un reçu.

## Décisions
1. **Aucun envoi dans une transaction métier.** Le métier publie un événement dans `outbox_events` (`EventPublisher`, même transaction que le changement) ; le module `notification` le traduit (`EventBridge`) en lignes `notification_outbox` ; `NotificationDispatcher` les envoie. Une panne SMTP/WhatsApp/Telegram ne peut jamais annuler ni retarder un transfert.
2. **Livraison au moins une fois, sans doublon voulu** : clé `dedupe_key` unique par événement et canal ; réservation `SKIP LOCKED` avec échéance repoussée de 5 min ; reprise exponentielle (30 s, 1 min… plafond 3 h) puis `FAILED` après `max-attempts` (6).
3. **Fournisseur de chaque canal choisi explicitement, sans défaut** (`wtc.notification.{email,whatsapp,telegram}.provider` = réel | `fake` | `disabled`), comme pour le paiement. `disabled` : messages `SKIPPED`.
4. **Canaux** : courriel SMTP (`spring.mail.*`), WhatsApp Business Cloud API (messages *modèles* approuvés par Meta : `wtc_transfer_created`, `wtc_transfer_completed`, `wtc_transfer_refunded`), Telegram (alertes équipe uniquement).
5. **Secrets** : les jetons de lien (réinitialisation, invitation) restent chiffrés dans la file et ne sont déchiffrés qu'à l'envoi ; les charges utiles n'ont ni numéro de compte ni pièce d'identité.
6. **Reçu PDF** (`GET /api/me/transfers/{id}/receipt`) : généré à la demande (OpenPDF) d'après l'instantané figé du transfert, numéros masqués, réservé au propriétaire (404 sinon), `no-store`.
7. **Surveillance** (`OperationsWatcher`, chaque minute) : les requêtes du runbook, exécutées par la machine — transferts bloqués, événements ou notifications en échec, écarts de solde, transferts en cours > 1 h, échecs non remboursés, ajustements en attente > 2 jours — annoncées une fois (une fois par jour si elles durent) sur le chat Telegram d'administration.

## Conséquences
- Les textes sont en français uniquement ; les modèles WhatsApp doivent être créés et approuvés chez Meta avec exactement ces noms et ces paramètres (voir `NotificationTemplates`).
- Livraison « au moins une fois » : un doublon est possible si le processus meurt entre l'envoi et l'enregistrement.
- Les rappels de KYC expiré et les rapports sont de la phase 9.
