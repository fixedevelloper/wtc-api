# Exploitation — mémo de la phase 1

## Démarrer en local

```bash
cp .env.example .env            # puis éditer DATABASE_PASSWORD et coller .local/encryption.key dans WTC_SECURITY_ENCRYPTION_KEY
tools/dev-keys.sh               # clés RSA et clé de chiffrement de développement dans .local/ (ignoré par git)
docker compose up -d postgres   # base seule (pour lancer l'API depuis l'IDE ou ./mvnw spring-boot:run)
docker compose --profile app up --build   # base + API dans Docker
```

L'API répond sur `http://localhost:8080`. La supervision est sur le port **8081** (non publié par `compose.yaml`).

## Déployer avec Docker (image construite par GitHub Actions)

**Ce que fait la CI** (`.github/workflows/ci.yml`, un seul job) : à chaque push sur `main` (ou lancement manuel), elle construit l'image, la pousse dans **GitHub Container Registry** (`ghcr.io/<propriétaire>/wtc-api` avec les tags `latest` et `<sha du commit>`), puis se connecte en SSH au serveur, tire l'image et relance l'API. `up -d --wait` fait échouer le job si l'API ne devient pas « healthy » en 180 s. Elle ne lance **ni tests ni analyses** : `./mvnw verify` se lance à la main avant de pousser.

**Mise en place une seule fois.**

1. GitHub > *Settings* > *Environments* > `production` > secrets : `SERVER_HOST`, `SERVER_PORT`, `SERVER_USER`, `SERVER_SSH_KEY` (clé privée dédiée au déploiement).
2. Sur le serveur, dans `/opt/wtc` (propriétaire : l'utilisateur SSH, membre du groupe `docker`) :

```bash
cp compose.prod.yaml .env.prod.example .    # depuis le dossier deploy/ du dépôt
cp .env.prod.example .env.prod              # remplir TOUTES les valeurs ; WTC_IMAGE=ghcr.io/<propriétaire>/wtc-api:latest
chmod 600 .env.prod
mkdir -p secrets && cp <clé RSA PKCS#8> secrets/jwt-private.pem && chmod 644 secrets/jwt-private.pem
docker compose --env-file .env.prod -f compose.prod.yaml up -d      # premier démarrage (la CI tire l'image elle-même)
```

3. Le paquet GHCR est privé par défaut : la CI se connecte au registre avec le jeton du job, valable le temps du déploiement et fermé ensuite. Un `docker compose pull` manuel sur le serveur demande donc d'abord un `docker login ghcr.io` (jeton personnel `read:packages`).

**À savoir**

- L'API n'écoute qu'en `127.0.0.1:8080` (changer avec `WTC_HOST_PORT` dans `.env.prod` si un autre service occupe déjà 8080 : `sudo ss -ltnp | grep :8080`) : un reverse proxy TLS (Caddy, Nginx, Traefik) doit être placé devant. Le port de supervision 8081 n'est jamais publié.
- PostgreSQL n'est joignable que depuis le réseau interne du compose. Flyway applique les migrations au démarrage.
- Le stockage objet est **externe** (S3 ou équivalent) ; le bucket est créé par l'infrastructure, jamais par l'application.
- **Retour arrière** : mettre un tag `<sha>` précédent dans `WTC_IMAGE` (`.env.prod`) puis `docker compose … pull api && docker compose … up -d api`. Les migrations Flyway ne reculent pas : une migration déjà appliquée doit rester compatible avec la version précédente.
- `WTC_PAYMENT_PROVIDER` et les canaux de notification ne doivent jamais valoir `fake`. Déclarer l'IP de sortie du serveur à Wace avant le premier transfert réel (voir « Mise en service du fournisseur »).
- Sauvegarder ensemble la base, le bucket et `WTC_SECURITY_ENCRYPTION_KEY` (à part).

## Reverse proxy nginx et certificat TLS (certbot)

Pré-requis : un enregistrement DNS `A` du domaine vers l'IP du serveur (`dig +short <domaine>`), les ports 80 et 443 ouverts. Dans les commandes, remplacer `DOMAINE` (ex. `wtc.api.digitagateway.com`) et `vous@exemple.com`. Le fichier `deploy/nginx/wtc-api.conf` est un vhost HTTP classique : `certbot --nginx` y ajoute lui-même le HTTPS et la redirection.

```bash
sudo apt install -y nginx certbot python3-certbot-nginx

# 1. vhost HTTP (un seul fichier par domaine)
sudo sed 's/api.example.com/DOMAINE/g' deploy/nginx/wtc-api.conf | sudo tee /etc/nginx/sites-available/DOMAINE.conf >/dev/null
sudo ln -sf /etc/nginx/sites-available/DOMAINE.conf /etc/nginx/sites-enabled/DOMAINE.conf
sudo nginx -t && sudo systemctl reload nginx

# 2. certificat Let's Encrypt + HTTPS + redirection : certbot modifie le fichier ci-dessus
sudo certbot --nginx -d wtc.api.digitagateway.com

# 3. le renouvellement est automatique (minuteur systemd de certbot, deux fois par jour) : le vérifier une fois
sudo certbot renew --dry-run
curl -I https://DOMAINE/api/meta/client-requirements      # doit répondre 200
```

- **IP du client (sécurité)** : l'API prend la première adresse de `X-Forwarded-For` comme IP du client (journal de connexion, limiteur de débit, verrouillage). Le proxy doit donc **écraser** cet en-tête (`proxy_set_header X-Forwarded-For $remote_addr;`, comme dans `wtc-api.conf`) et **jamais** le compléter avec `$proxy_add_x_forwarded_for` : sinon un client envoie `X-Forwarded-For: 9.9.9.9` et l'API enregistre 9.9.9.9 (vérifié), ce qui contourne les limites par IP. Pour la même raison, le port de l'API ne doit pas être joignable sans passer par le proxy.
- **Après certbot, le fichier du serveur contient le HTTPS** : pour modifier la configuration, éditer le fichier de `/etc/nginx/sites-available/` ; ne pas le réécraser avec la copie du dépôt (les lignes de certificat et la redirection disparaîtraient).
- **Un seul vhost par domaine** : deux fichiers qui déclarent le même `server_name` (`sites-enabled/` et `conf.d/`) provoquent des blocs ignorés ; les repérer avec `sudo nginx -T | grep -n "server_name DOMAINE"`.
- **Port 8080 déjà pris** par un autre service (`sudo ss -ltnp | grep :8080`) : mettre `WTC_HOST_PORT=8090` dans `.env.prod` et adapter les deux `proxy_pass` de nginx.
- **Renouvellement** : certbot renouvelle seul à 30 jours de l'échéance (validation par nginx, port 80) et recharge nginx. Contrôler de temps en temps avec `sudo certbot certificates`.
- **`unknown directive …` ou autre erreur de `nginx -t`** : certbot lance `nginx -t` avant tout et s'arrête si la configuration est invalide. Corriger le fichier (`sudo nginx -t` donne la ligne), puis relancer.
- **Si `/etc/letsencrypt/live/DOMAINE/` existe déjà** (clé ou CSR générés à la main lors d'un essai), certbot refuse de créer le certificat : supprimer ce dossier d'essai (`sudo rm -rf /etc/letsencrypt/live/DOMAINE`).
- **Échec de validation** : vérifier que le DNS pointe bien vers ce serveur, que le port 80 est joignable depuis l'extérieur (pare-feu du serveur et du fournisseur) et que `server_name` est bien le domaine demandé.
- La ligne `X-Robots-Tag "noindex, nofollow"` convient à une recette ; la retirer pour la production.
- Un certificat d'une autre autorité ou auto-signé (essais uniquement : Android le refuse) se place dans `/etc/letsencrypt/live/DOMAINE/` sous les noms `fullchain.pem` (certificat **puis** chaîne intermédiaire) et `privkey.pem` (droits `600`, jamais dans le dépôt), puis se référence dans le vhost (`ssl_certificate`, `ssl_certificate_key`) ; vérifier que la clé correspond : `openssl x509 -in fullchain.pem -noout -pubkey | sha256sum` et `openssl pkey -in privkey.pem -pubout | sha256sum` doivent donner la même empreinte.

## Variables d'environnement

| Variable | Rôle | Défaut |
|---|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | Connexion PostgreSQL | URL locale ; **aucun mot de passe par défaut** |
| `DATABASE_POOL_SIZE` | Taille du pool | 10 |
| `WTC_SECURITY_JWT_PRIVATE_KEY_LOCATION` | Clé privée RSA (PEM PKCS#8) qui **signe** les jetons ; la clé publique en est déduite. Une des deux clés est **obligatoire** : sans clé l'application ne démarre pas | — |
| `WTC_SECURITY_JWT_PUBLIC_KEY_LOCATION` | Clé publique RSA (PEM) qui **vérifie** les jetons (instance qui ne signe pas) | déduite de la privée |
| `WTC_SECURITY_ENCRYPTION_KEY` | Clé AES-256 (Base64, 32 octets, `openssl rand -base64 32`) qui chiffre les secrets stockés. **Obligatoire** | — |
| `WTC_SECURITY_JWT_ACCESS_TTL`, `_REFRESH_TTL`, `_REFRESH_MAX_AGE` | Durées : jeton d'accès, refresh token, âge maximal d'une session | 15m, 14d, 90d |
| `WTC_STORAGE_ENDPOINT`, `_ACCESS_KEY`, `_SECRET_KEY`, `_BUCKET` | Stockage objet S3/MinIO des pièces KYC et photos. **Obligatoires** : sans eux l'application ne démarre pas | bucket `wtc-private` |
| `WTC_STORAGE_PUBLIC_ENDPOINT` | Hôte des liens signés remis aux clients, s'il diffère de l'endpoint interne | endpoint |
| `WTC_STORAGE_AUTO_CREATE_BUCKET` | Crée le bucket s'il manque (**développement seulement**) | false |
| `WTC_PAYMENT_PROVIDER` | **Obligatoire** : `wace` (réel) ou `fake` (développement et tests uniquement, jamais en production) | — |
| `WTC_WACE_BASE_URL`, `WTC_WACE_EMAIL`, `WTC_WACE_PASSWORD` | Accès à l'API Wace (obligatoires avec `wace`). **L'adresse IP de sortie des serveurs doit être déclarée à Wace (liste blanche) ou un VPN établi** | — |
| `WTC_PAYMENT_WORKER_ENABLED` | Active les travailleurs d'envoi et de suivi (désactiver sur les instances qui ne doivent pas envoyer) | true |
| `WTC_BOOTSTRAP_SUPER_ADMIN_EMAIL`, `_PASSWORD` | Crée le premier super-administrateur d'un environnement neuf (voir ci-dessous) | vide |
| `WTC_SECURITY_JWT_ISSUER` | Émetteur (`iss`) attendu | `wtc-api` |
| `CORS_ALLOWED_ORIGINS` | Origines autorisées (liste séparée par des virgules) | aucune |
| `PORT`, `MANAGEMENT_PORT` | Ports public et de supervision | 8080, 8081 |
| `TRACING_SAMPLING_PROBABILITY` | Part des requêtes tracées (0 à 1) | 0.0 |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | Collecteur OpenTelemetry | non défini |
| `SPRING_PROFILES_ACTIVE` | `dev` (logs détaillés) ou `prod` (logs JSON) | `prod` dans l'image |

Les secrets (mot de passe de base, clés) passent **uniquement** par l'environnement ou des fichiers montés ; jamais dans le dépôt ni dans l'image.

## Supervision (port 8081, réseau interne uniquement)

| URL | Usage |
|---|---|
| `/actuator/health/liveness` | Sonde de vie (redémarrer le conteneur si KO) |
| `/actuator/health/readiness` | Sonde de disponibilité, vérifie la base (retirer de la rotation si KO) |
| `/actuator/prometheus` | Métriques (latences HTTP, JVM, pool de connexions) |

Sur le port public, **tout** `/actuator/*` répond 401 ou 404 (vérifié : même `health` et `info`) ; les sondes ne sont joignables que sur le port 8081.

## Journaux et corrélation

- Chaque réponse porte `X-Request-Id` (repris du client s'il est valide, sinon généré). Il figure dans le champ `requestId` des erreurs `problem+json` et dans chaque ligne de log (format JSON en production).
- Pour un incident : demander à l'utilisateur le `requestId` de l'erreur, puis le chercher dans les logs.
- Les erreurs 5xx sont journalisées côté serveur avec leur trace ; le client ne reçoit jamais de détail technique.

## Base de données

- Le schéma est géré par **Flyway** (`src/main/resources/db/migration`). Une migration appliquée ne se modifie jamais : on en ajoute une nouvelle.
- `V1__baseline.sql` crée l'extension `btree_gist` : le rôle qui exécute les migrations doit pouvoir créer des extensions (ou l'extension doit être créée une fois par un administrateur).
- Contrôle d'intégrité à surveiller : `SELECT * FROM wallet_balance_discrepancies;` doit toujours être **vide**.

## Identité : premier démarrage et exploitation courante

**Premier super-administrateur.** Renseigner `WTC_BOOTSTRAP_SUPER_ADMIN_EMAIL` et `WTC_BOOTSTRAP_SUPER_ADMIN_PASSWORD` (mot de passe respectant la politique : 10 caractères minimum, pas un mot de passe courant) puis démarrer. Le compte est créé une seule fois ; **dès qu'un super-administrateur existe, ces variables n'ont plus aucun effet** : les retirer ensuite. À la première connexion (`POST /api/auth/login`), le jeton obtenu ne permet que d'activer le second facteur (`POST /api/me/mfa/enrollment` puis `/api/me/mfa/confirmation`) ; après quoi la connexion passe par `/api/auth/mfa/verify` et donne tous les droits. Ce super-administrateur crée ensuite les autres comptes (`POST /api/admin/users`) : chaque invité reçoit un lien d'invitation pour choisir son mot de passe.

**Compte verrouillé** (5 échecs) : se déverrouille seul après 15 minutes ; une réinitialisation du mot de passe le déverrouille aussitôt. **Téléphone TOTP perdu** : un super-administrateur appelle `POST /api/admin/users/{id}/mfa-reset` ; la personne se reconnecte et réactive son second facteur.

**Lien de réinitialisation ou d'invitation non reçu.** Tant que le module de notification n'existe pas (phase 8), les liens sont déposés dans la table `outbox_events` (types `identity.password-reset-requested` et `identity.staff-invited`), le jeton y est chiffré. Ils ne sont donc pas envoyés automatiquement.

**Surveiller.**
- `SELECT * FROM wallet_balance_discrepancies;` vide (voir plus haut).
- `SELECT action, count(*) FROM audit_log WHERE occurred_at > now() - interval '1 day' GROUP BY 1;` : une hausse de `REFRESH_TOKEN_REUSE_DETECTED` (un jeton volé a été rejoué) ou de `ACCOUNT_LOCKED` mérite une enquête.
- `SELECT outcome, count(*) FROM login_audit WHERE occurred_at > now() - interval '1 hour' GROUP BY 1;` : pic de `BAD_CREDENTIALS` = tentative de bourrage d'identifiants.

**Contrôles d'intégrité de l'argent (à planifier toutes les heures, alerte au moindre résultat).**

```sql
-- 1. Un solde ne diffère jamais de la somme de ses écritures : DOIT être vide.
SELECT * FROM wallet_balance_discrepancies;
-- 2. Aucune transaction sans écriture (échec attrapé à tort par un appelant) : DOIT être vide.
SELECT t.id, t.reference FROM ledger_transactions t LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id WHERE e.id IS NULL;
-- 3. Ajustements en attente depuis plus de 2 jours (oubliés).
SELECT public_id, created_at FROM balance_adjustments WHERE status = 'PENDING' AND created_at < now() - interval '2 days';
```

Une divergence **ne se corrige jamais par UPDATE** : on pose une transaction `CORRECTION` au grand livre (les écritures sont immuables), après analyse.

**Mise en service du fournisseur (avant le premier transfert réel).** 1. Déclarer l'IP de sortie à Wace (ou VPN). 2. Renseigner `WTC_PAYMENT_PROVIDER=wace` et les accès. 3. Se connecter en `ADMIN` et lancer `POST /api/admin/reference/sync` : il importe les codes payeur, les banques et les libellés. 4. Lire les `warnings` de la réponse : chaque opérateur mobile sans service correspondant doit recevoir son code payeur à la main (`mobile_operators.wace_payer_code`) avant d'être utilisable. 5. Vérifier les taux de change contre ceux de Wace. Un transfert vers une banque ou un opérateur sans code payeur est refusé **avant tout débit** (`DESTINATION_NOT_SUPPORTED`).

**Transferts : ce qu'il faut surveiller (toutes les 5 minutes, alerte au moindre résultat de 1 et 2).**

```sql
-- 1. Transferts bloqués pour décision humaine (jamais résolus automatiquement).
SELECT reference, payout_hold, status, created_at FROM transfers WHERE payout_hold IS NOT NULL ORDER BY created_at;
-- 2. Événements d'envoi définitivement en échec.
SELECT id, event_type, aggregate_id, last_error FROM outbox_events WHERE status = 'FAILED';
-- 3. Transferts « en cours » depuis trop longtemps (file saturée, fournisseur lent).
SELECT reference, status, created_at FROM transfers WHERE status IN ('PAYOUT_PENDING', 'PROCESSING') AND created_at < now() - interval '1 hour' AND payout_hold IS NULL;
-- 4. Transferts échoués non remboursés (remboursement impossible : portefeuille clos ?).
SELECT reference, failure_reason FROM transfers WHERE status = 'FAILED';
```

Traitement des blocages :

| `payout_hold` | Signification | Que faire |
|---|---|---|
| `AMBIGUOUS_CREATE` | La création de l'ordre a eu une issue incertaine (ou Wace ne reconnaît pas la référence) | Chercher l'ordre dans le **back-office Wace** (le numéro du transfert est dans le commentaire d'un envoi bancaire). Trouvé : `POST /admin/transfers/{id}/sync` avec sa référence. Introuvable : `POST /admin/transfers/{id}/refund` avec le motif |
| `AMOUNT_MISMATCH` | Wace annonce un montant différent du devis ; ordre non confirmé | Aligner les taux (`/admin/exchange-rates`) avec ceux de Wace ; décision à prendre avec Wace pour l'ordre (non annulable par l'API) |
| `PROVIDER_LOCKED` | Ordre bloqué par la conformité de Wace | Contacter Wace ; **ne pas rembourser** tant que l'ordre n'est pas annulé chez eux (le suivi reprend seul s'ils le débloquent) |
| `CONFIRM_FAILED` | La confirmation échoue malgré les essais | Vérifier l'ordre chez Wace, le confirmer à la main chez eux ou contacter leur support |

Un transfert `PROCESSING` sans blocage n'appelle **aucune** action : le suivi interroge Wace toutes les 30 secondes.

**Rotation des clés.** Changer la clé de signature JWT invalide tous les jetons d'accès (au pire 15 minutes de reconnexions silencieuses via le refresh token). **Changer `WTC_SECURITY_ENCRYPTION_KEY` rend illisibles les secrets TOTP déjà enregistrés** : il n'y a pas encore d'outil de rechiffrement ; ne pas la changer sans l'avoir prévu, et la sauvegarder séparément de la base.

## Stockage des fichiers (KYC, photos)

- Le bucket est **privé** : n'ouvrir aucune politique publique. Les clients n'obtiennent que des liens signés (5 min pour un document KYC, 1 h pour une photo) ; un lien expiré se redemande en rappelant `GET /api/me/kyc` ou `/api/me`.
- En production, le bucket est créé par l'infrastructure (jamais par l'application) ; activer le chiffrement côté serveur et le versionnement, et sauvegarder le bucket **avec** la base : une pièce KYC sans sa ligne en base, ou l'inverse, est inutilisable.
- Les clés d'objets sont `kyc/<client>/<document>` et `photos/<compte>/<photo>`. Supprimer un client ne supprime pas ses fichiers (conservation à fixer avec la conformité, hypothèse 7).
- Contrôle de cohérence : `SELECT count(*) FROM kyc_documents` doit correspondre au nombre d'objets `kyc/…` du bucket.

## Notifications et alertes

- Choix des fournisseurs : `WTC_NOTIFY_{EMAIL,WHATSAPP,TELEGRAM}_PROVIDER` (obligatoires ; `fake` interdit en production). SMTP : `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD`. WhatsApp : `WTC_WABA_PHONE_NUMBER_ID`, `WTC_WABA_ACCESS_TOKEN` et les trois modèles `wtc_transfer_created|completed|refunded` approuvés chez Meta (4 paramètres : prénom/nom, référence, montant, bénéficiaire ; le remboursé en a 3). Telegram : `WTC_TELEGRAM_BOT_TOKEN`, `WTC_TELEGRAM_ADMIN_CHAT_ID`. Liens : `WTC_WEB_BASE_URL`.
- File : `SELECT status, channel, count(*) FROM notification_outbox GROUP BY 1,2;` — `FAILED` : lire `last_error`, corriger la cause puis `UPDATE notification_outbox SET status='PENDING', attempts=0, next_attempt_at=now() WHERE id=…` pour rejouer. `SKIPPED` : canal éteint ou sans modèle, normal.
- Les alertes d'exploitation arrivent sur Telegram (une fois, puis une fois par jour tant que le problème dure) ; sans `WTC_TELEGRAM_ADMIN_CHAT_ID`, aucune alerte n'est émise : exécuter alors à la main les requêtes de ce runbook.

## Tâches planifiées, jetons web et synthèse

- Tâches (toutes idempotentes, voir l'ADR 0016) : expiration KYC chaque heure, jetons web chaque 5 min, purges toutes les 6 h ; journalisées au niveau INFO/ERROR (`KYC :`, `Nettoyage :`). Les couper : `WTC_TASKS_ENABLED=false`.
- Un client dont la pièce a expiré est repassé en `EXPIRED` : il ne peut plus envoyer jusqu'à la validation d'une nouvelle pièce. Pour vérifier : `SELECT kyc_status, count(*) FROM customers GROUP BY 1;`
- Chatbot WhatsApp : `WTC_SERVICE_KEY` (à générer longue et aléatoire, à renouveler en cas de fuite : changer la variable et redéployer). Les jetons sont créés par le personnel (`POST /api/admin/web-tokens`, permission `TRANSFER_CREATE`) et ne sont jamais relisibles ; en cas de perte, en créer un autre.
- Synthèse quotidienne : message Telegram vers 07:00 UTC ; absent = chat non configuré ou file de notifications en échec (voir plus haut).

## Migration depuis l'ancien backend et bascule

**Répétitions (autant que nécessaire, sur une base PostgreSQL vide et jetable)** :

```bash
# variables d'environnement habituelles (DATABASE_*, WTC_SECURITY_*, WTC_STORAGE_*)
./mvnw spring-boot:run -Dspring-boot.run.profiles=etl -Dspring-boot.run.arguments="\
  --wtc.etl.source-url=jdbc:mariadb://HOTE:3306/moneytransfert --wtc.etl.source-user=LECTURE --wtc.etl.source-password=... \
  --wtc.etl.public-dir=/chemin/vers/wetransfercashapi/public --wtc.etl.report-file=etl-report.json"
```

Sans autre option, l'exécution est **à blanc** (rien n'est écrit). Lire `etl-report.json` : `failedControls` doit être vide, puis traiter les `ERROR` (rejets) et `WARN` (corrections) avec l'exploitation. Code de sortie 0 = contrôles OK, 2 = écart.

**Bascule (une seule fois)** :
1. Geler l'ancien système (maintenance : plus d'écriture), sauvegarder MySQL et `public/uploads`.
2. Démarrer le nouveau PostgreSQL vide (migrations Flyway par un premier démarrage de l'API, puis l'arrêter), configurer Wace (`WTC_PAYMENT_PROVIDER=wace`, accès recette/IP validés).
3. Lancer l'ETL avec `--wtc.etl.dry-run=false`. Vérifier `SELECT severity, count(*) FROM etl_report GROUP BY 1;` et `SELECT count(*) FROM wallet_balance_discrepancies;` (doit être 0).
4. Démarrer l'API, lancer `POST /api/admin/reference/sync` (banques, codes payeurs, libellés), puis relier les banques « migrées » : pour chaque `banks.name = 'Banque inconnue (migrée)'` utilisé, ré-affecter les destinations à la vraie banque (même pays) puis désactiver le placeholder ; idem `mobile_operators` « (migré) ».
5. Traiter les transferts à examiner : `SELECT reference, legacy_status, failure_reason FROM transfers WHERE status = 'CANCELLED' AND failure_reason LIKE 'Statut historique%' ORDER BY created_at;` (client débité, issue inconnue : décision au cas par cas, jamais de remboursement automatique) ; les `PROCESSING` migrés seront interrogés chez Wace (alertes Telegram pour les références inconnues).
6. Décider les soldes négatifs et les soldes d'origine douteux listés dans le rapport (`stage = 'balances'`), les rôles `ROLE_SUPER` (promotion en SUPER_ADMIN si voulu), les 2 recharges `PENDING`.
7. Rediriger Android/web vers le nouveau contrat (guide `docs/design/frontend-migration.md`), l'ancien backend reste en lecture seule.

**Retour arrière** : avant l'ouverture au public, supprimer la base cible et recommencer (l'ancien système n'a pas été modifié). Après ouverture, pas de retour automatique : les transferts créés ensuite n'existent que dans le nouveau système.

**Fichiers** : les pièces absentes de `public/` sont listées (`kyc.files_missing`) ; un client « vérifié » sans pièce exploitable reçoit une pièce de reprise sans fichier (visible : clé `legacy/no-file/…`).

## Dépannage

| Symptôme | Cause probable |
|---|---|
| Démarrage refusé : « No qualifying bean of type JwtDecoder » | `WTC_SECURITY_JWT_PUBLIC_KEY_LOCATION` absent |
| `Schema-validation: missing column` | Entité modifiée sans migration Flyway correspondante |
| `permission denied to create extension "btree_gist"` | Le rôle de migration n'a pas le droit de créer l'extension |
| 401 sur toutes les routes protégées | Mauvais `iss`, jeton expiré, ou clé publique qui ne correspond pas à la clé de signature |
| Démarrage refusé : « wtc.storage.endpoint est obligatoire » | Variables `WTC_STORAGE_*` absentes |
| Un lien de document ne s'ouvre pas depuis la machine du développeur | `WTC_STORAGE_PUBLIC_ENDPOINT` doit pointer vers un hôte joignable par le navigateur (pas `minio:9000`) |
| Démarrage refusé : « wtc.security.encryption-key est obligatoire » | `WTC_SECURITY_ENCRYPTION_KEY` absent ou ne décode pas en 32 octets |
| Un administrateur reçoit 403 partout après sa connexion | Il n'a pas encore activé son second facteur (jeton `enrollment_only`) |
