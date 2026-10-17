# Tâches planifiées, commandes et fichiers statiques

## Commandes console Symfony

| Commande | Rôle | À porter ? |
|---|---|---|
| `app:callback-notification` | **Seule tâche de fond indispensable.** Interroge Wace pour chaque transaction `typeservice = WACEPAY` et `status = validation` : `PAID` → `effectue` + message WhatsApp de réception ; `CANCELED` → `echoue` + message d'échec (**sans remboursement**, voir [business-rules.md](business-rules.md)). Les contrôles PayDunya et Agensic sont commentés dans `execute()`. | **Oui** → `@Scheduled` + ShedLock, idempotent, avec politique de remboursement décidée |
| `app:wacepay:lookup`, `app:wacepay:save-payercodes`, `app:wacepay:save-payerservices`, `app:import-static` | Importent depuis Wace les motifs, relations, origines de fonds, services et codes payeurs vers `wace_data`, `wace_payer_service`, `operateur` | Oui, comme **tâches d'administration** déclenchées à la demande (endpoint admin ou `CommandLineRunner`) |
| `app:create-operator` | Crée des opérateurs (318 lignes, flux legacy) | À examiner ; probablement remplacé par l'import ci-dessus |
| `app:install` | Crée le premier compte (admin ou non) | Oui sous forme d'initialisation (`ApplicationRunner` désactivé par défaut) |
| `app:greet-user`, `app:waba:test` | Outils de test | Non |
| `app:legacy-usage` | Outil de la migration `/v1` | Non (propre à Symfony) |

**Planification en production : inconnue.** Le dépôt ne contient ni crontab, ni superviseur, ni conteneur de tâches. Il faut obtenir la fréquence réelle d'exécution de `app:callback-notification` auprès de l'exploitation avant de dimensionner le job Spring. Tant que Symfony et Spring cohabitent, **un seul des deux** doit l'exécuter.

## Fichiers servis par le backend (dossier `public/`)

| Chemin | Contenu | Appelant | Traitement dans Spring |
|---|---|---|---|
| `/wace_origin_fond.json`, `/wace_raison_to_send.json`, `/wace_relaction.json` | Listes statiques (origine des fonds, motifs, relations) **téléchargées par Android à la racine du serveur** | Android (3 appels) | Servir ces trois fichiers au même chemin pendant la transition, puis exposer `GET /api/reference/origin-of-funds`, `/transfer-reasons`, `/relationships` ; à terme, les alimenter depuis `wace_data` |
| `/world/cities.json` (35 Mo), `/world/payer_mofy.json` (1,2 Mo) | Référentiels volumineux d'import | Aucun appelant HTTP connu (utilisés par les commandes) | Ne pas servir publiquement ; garder comme ressources d'import |
| `/uploads/` (15 fichiers, 0,9 Mo), `/uploads/proofs/` | Photos de profil, pièces KYC, preuves de recharge. **Dossier public**, noms aléatoires | Web et Android (affichage) | Stockage hors racine web ou objet privé avec lien signé ; migrer les fichiers existants |
| `/reporting/recu/`, `/recharges/`, `/pub/` | Reçus PDF, divers | Reçus PDF générés avec FPDF (`recutransaction.pdf`) | Reçu : génération à la demande (route `/api/me/transactions/{id}/receipt`, à créer si le client en a besoin) |
| `avatar.jpg`, `logo.png`, `kyc.png`, `placeholder.jpg` | Images par défaut | Web / Android | Ressources statiques |

L'URL de base des fichiers (`BASE_DOMAIN`, paramètre `domain`) est concaténée dans les réponses JSON : `photo`, `piece_face_file`, `piece_recto_file`, `photoURL`. Le nouveau backend doit produire des URLs équivalentes (ou signées).
