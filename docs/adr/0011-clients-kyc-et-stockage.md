# ADR 0011 — Clients, KYC, contacts de paiement et stockage des fichiers

- **Statut** : Accepté (mis en œuvre en phase 3)
- **Date** : 2026-10-16

## Décisions

| Sujet | Choix | Raison |
|---|---|---|
| Qui est le client ? | Toujours celui du jeton (claim `customer_id`, posé à la connexion). Aucune route `/me` ne prend un identifiant de client. Une référence vers le contact d'un autre client répond **404**, comme une référence inexistante | Pas d'accès inter-clients, pas d'énumération |
| Couplage identité ↔ client | L'identité appelle `CustomerAccounts` (couche application du client) ; le client **ne connaît pas** l'identité : il publie des événements (`CustomerStatusChanged`, `CustomerIdentityChanged`) que l'identité écoute dans la même transaction | Pas de cycle entre modules (vérifié par ArchUnit) ; une exception de l'écouteur annule toute la modification |
| Seule lecture de `users` par le client | `kyc_documents.reviewed_by` et l'auteur d'audit sont retrouvés par identifiant public dans la requête SQL | Évite un appel retour vers l'identité pour une simple clé |
| Statut du client | `ACTIVE` / `SUSPENDED` / `CLOSED`. Suspendre ou clore **suspend le compte d'accès et révoque ses refresh tokens immédiatement**. Un client suspendu (jeton encore valable ≤ 15 min) peut consulter mais plus rien modifier (`CUSTOMER_NOT_ACTIVE`). La clôture est définitive | Cohérent avec les limites de l'ADR 0010 |
| Statut KYC | **Calculé** par `KycPolicy` à partir des documents : `VERIFIED` dès qu'une pièce d'identité (passeport, carte nationale, titre de séjour, permis) approuvée est valable ; sinon `PENDING` s'il reste un document à examiner ; sinon `REJECTED` ; sinon `EXPIRED` ; sinon `NOT_SUBMITTED`. Un justificatif de domicile ou un selfie ne vérifient jamais seuls | Règle explicite et testée (voir *hypothèses*) |
| Identité vérifiée | Nom, prénom, raison sociale, date de naissance, nationalité, type de compte et date d'immatriculation **ne sont plus modifiables** par le client (`KYC_LOCKED`) | La vérification porte sur des pièces qui doivent rester en concordance |
| Adresse e-mail | **Non modifiable** via `PATCH /api/me` (`EMAIL_CHANGE_UNSUPPORTED`) | C'est l'identifiant de connexion : le changer sans la vérifier permettrait de voler un compte avec un jeton volé |
| Numéros sensibles | Numéro de pièce d'un contact et numéro de compte / mobile d'une destination : **AES-256-GCM** applicatif (contexte lié à l'objet), jamais renvoyés ; seul un masque `****1234` est exposé. Un numéro de pièce absent à la mise à jour est **conservé** (le client ne le revoit jamais) | Une fuite de base ou de réponse ne livre aucun numéro |
| Destinations | Banque ou opérateur doivent exister, être actifs et appartenir au pays indiqué ; le pays doit accepter les paiements entrants ; un opérateur doit supporter l'envoi ; formats contrôlés (numéro, SWIFT, IFSC). Une seule destination par défaut par bénéficiaire (la première l'est d'office ; archiver la défaut en désigne une autre) | Une destination invalide ne doit jamais atteindre Wace |
| Archivage | Les contacts et destinations sont **archivés**, jamais supprimés (les transferts passés les référencent) | Historique intact |
| Stockage | Port `ObjectStorage` + adaptateur **S3** (MinIO en local). Bucket **privé** ; accès par **lien signé de 5 min** (KYC) ou 1 h (photo). Le type réel est reconnu par la **signature du fichier** (JPEG, PNG, PDF), jamais par l'en-tête du client ; 8 Mio (KYC) / 2 Mio (photo) ; selfie = image seulement ; 30 documents par client ; 10 dépôts par heure | Règle l'ancien dossier `/uploads/` public avec noms aléatoires (constat de la phase 0) |
| Journal d'audit | Les modifications de profil journalisent **les noms des champs changés, pas leurs valeurs** | Pas de données personnelles dans l'audit |

## Hypothèses (non confirmées par l'utilisateur, à valider)

1. **Règle `VERIFIED`** : une seule pièce d'identité approuvée suffit (pas de selfie ni de justificatif de domicile obligatoires). Le backend Symfony n'avait pas de règle explicite (phase 0).
2. **Formats acceptés** : JPEG, PNG, PDF ; pas de GIF/WebP/HEIC (les photos d'iPhone en HEIC devront être converties par l'application).
3. **Peuvent recevoir un transfert** : les pays dont `can_receive` est vrai ; la liste vient des référentiels.
4. **Le support** (`SUPPORT`) lit les clients et examine le KYC ; il ne les suspend pas et ne modifie pas leurs contacts (`ADMIN` le peut).

## Limites connues

- **Expiration des documents** : un document approuvé dont la date d'expiration passe cesse de compter pour `VERIFIED` à la prochaine évaluation, mais **aucune tâche planifiée ne la déclenche encore** (phase 9). Tant que personne n'ouvre le KYC, `customers.kyc_status` n'est pas rafraîchi ; la vue `GET /api/me/kyc` est, elle, toujours recalculée.
- **Pas de détection de doublons** : deux fois le même numéro de compte pour un bénéficiaire est accepté (les numéros sont chiffrés ; un index aveugle HMAC permettrait de les détecter).
- **Photo dans le back-office** : `GET /api/admin/customers/{id}` ne renvoie pas `photoUrl` (la photo appartient au compte d'accès) ; le solde (`balance`) arrive avec le module portefeuille.
- **`GET /api/admin/customers/{id}/wallet`** est reporté avec le module portefeuille (phase 5).
- **N+1 sur la liste d'administration des contacts** : une requête de destinations par contact de la page (≤ 100). Acceptable pour le back-office ; à regrouper si la page devient lente.
- **Le stockage est lu en entier en mémoire** (≤ 8 Mio par dépôt). Suffisant pour des pièces d'identité ; à passer en flux si on accepte de plus gros fichiers.
