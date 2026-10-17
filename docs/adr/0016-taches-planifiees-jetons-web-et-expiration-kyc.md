# ADR 0016 — Tâches planifiées, jetons web et expiration KYC

Statut : accepté (phase 9).

## Décisions
1. **Tâches idempotentes, sans verrou distribué.** Chaque tâche (`ScheduledTasks`) est une instruction ou une suite d'instructions qui, rejouée, ne trouve plus rien à faire (`UPDATE … RETURNING`, `DELETE`). Plusieurs instances, un redémarrage ou une exécution manquée sont sans conséquence ; la table `shedlock` du schéma initial reste inutilisée. Les doublons de rappel éventuels sont écartés par les clés d'unicité des notifications. Arrêt global : `WTC_TASKS_ENABLED=false`.
2. **Expiration KYC** (toutes les heures) : les documents approuvés dont `expires_on` est dépassée passent à `EXPIRED`, le statut KYC du client est recalculé par la politique existante (`KycPolicy`) — un client sans autre pièce valide ne peut plus envoyer — et un événement `kyc.expired` prévient le client par courriel. Un rappel `kyc.expiring` est émis 30 jours avant l'échéance, une fois par document.
3. **Jetons web** (WhatsApp) : jeton aléatoire de 256 bits, seule l'empreinte SHA-256 est stockée, affiché une seule fois à la création. Consommation sous verrou de ligne (`FOR UPDATE`) : un seul appel réussit, les autres reçoivent 409. Un jeton échu est présenté `EXPIRED` dès la lecture, avant le passage de la tâche.
4. **Clé de service** `X-Service-Key` (`WTC_SERVICE_KEY`) pour `/api/web-tokens/**` : comparée par empreinte en temps constant ; sans clé configurée les routes sont fermées ; un jeton d'accès (même administrateur) n'ouvre pas ces routes (403).
5. **Nettoyage** (toutes les 6 h) : jetons de rafraîchissement échus depuis 30 j, jetons à usage unique depuis 7 j, événements traités depuis 60 j, notifications envoyées ou ignorées depuis 90 j. Les échecs, l'audit, les connexions et le grand livre ne sont **jamais** purgés.
6. **Synthèse quotidienne** sur Telegram à partir de 07:00 UTC (veille, UTC) : transferts créés/payés/remboursés, volume et frais par devise, nouveaux clients, pièces et ajustements en attente. Une fois par jour grâce à la clé `report:{date}`.

## Conséquences
- Pas de rapports téléchargeables ni de tableaux de bord : la synthèse Telegram est le seul rapport ; le contrat n'en décrit aucun.
- La réconciliation avec Wace (liste des ordres) n'est pas planifiable : Wace n'offre aucune API de liste.
- Les durées de conservation sont des valeurs initiales (hypothèse 21).
