# Variables communes de l'essai de bout en bout (à sourcer). Tout est local et jetable : rien ne touche aux ports ni aux
# conteneurs de votre environnement habituel (PostgreSQL 55433, MinIO 59001, API 8080, supervision 8081, web 3000).
E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export E2E_PG_CONTAINER=wtc-e2e-pg
export E2E_MINIO_CONTAINER=wtc-e2e-minio
export E2E_PG_PORT=55433
export E2E_MINIO_PORT=59001
export E2E_API_PORT=8080
export E2E_WEB_URL=http://localhost:3000
export E2E_ADMIN_EMAIL=admin@wtc.local
export E2E_ADMIN_PASSWORD='Cobalt-Lanterne-Verger-42'
export E2E_SERVICE_KEY=e2e-service-key-0123456789abcdef0123456789abcdef
export E2E_DIR="$E2E_ROOT/.local/e2e"
mkdir -p "$E2E_DIR"

# Configuration de l'API pour l'essai : faux fournisseur de paiement qui « paie » 20 s après la création, faux canaux de
# notification (rien n'est envoyé), stockage MinIO local.
e2e_api_env() {
  export SPRING_PROFILES_ACTIVE=dev
  export DATABASE_URL="jdbc:postgresql://localhost:${E2E_PG_PORT}/wtc"
  export DATABASE_USER=wtc DATABASE_PASSWORD=e2e
  export WTC_SECURITY_JWT_PRIVATE_KEY_LOCATION="file:${E2E_ROOT}/.local/jwt-private.pem"
  export WTC_SECURITY_ENCRYPTION_KEY="$(cat "${E2E_ROOT}/.local/encryption.key")"
  export WTC_SERVICE_KEY="$E2E_SERVICE_KEY"
  export WTC_STORAGE_ENDPOINT="http://localhost:${E2E_MINIO_PORT}"
  export WTC_STORAGE_ACCESS_KEY=e2eminio WTC_STORAGE_SECRET_KEY=e2eminio-secret-0123456789
  export WTC_STORAGE_AUTO_CREATE_BUCKET=true
  export WTC_PAYMENT_PROVIDER=fake
  export WTC_PAYMENT_FAKE_AUTO_COMPLETE_AFTER=20s
  export WTC_NOTIFY_EMAIL_PROVIDER=fake WTC_NOTIFY_WHATSAPP_PROVIDER=fake WTC_NOTIFY_TELEGRAM_PROVIDER=fake
  export WTC_WEB_BASE_URL="$E2E_WEB_URL"
  export CORS_ALLOWED_ORIGINS="$E2E_WEB_URL"
  export WTC_BOOTSTRAP_SUPER_ADMIN_EMAIL="$E2E_ADMIN_EMAIL"
  export WTC_BOOTSTRAP_SUPER_ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD"
}
