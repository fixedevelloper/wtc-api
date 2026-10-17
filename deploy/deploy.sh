#!/usr/bin/env bash
# Exécuté SUR LE SERVEUR par .github/workflows/deploy.yml (via « ssh … bash -s »), ou à la main :
#   deploy.sh <image-complète> [répertoire]      ex. deploy.sh ghcr.io/acme/wtc:sha-ab12… /opt/wtc
# Met WTC_IMAGE à jour dans .env.prod, tire l'image, redémarre l'API puis attend qu'elle soit « healthy ».
# Si elle ne l'est pas, remet l'image précédente et sort en erreur (code 1).
# Attention : les migrations Flyway ne reculent pas. Un retour arrière ne reprend que le code ; une migration déjà appliquée
# doit donc rester compatible avec la version précédente (ajouter d'abord, retirer dans une version ultérieure).
set -euo pipefail

IMAGE="${1:?image complète requise (ex. ghcr.io/<org>/<dépôt>:<tag>)}"
DIR="${2:-.}"
WAIT_SECONDS="${WAIT_SECONDS:-180}"

cd "$DIR"
[ -f compose.prod.yaml ] || { echo "compose.prod.yaml introuvable dans $PWD" >&2; exit 2; }
[ -f .env.prod ]         || { echo ".env.prod introuvable dans $PWD" >&2; exit 2; }

compose() { docker compose --env-file .env.prod -f compose.prod.yaml "$@"; }

set_image() { # remplace (ou ajoute) la ligne WTC_IMAGE sans toucher aux autres secrets ni aux droits du fichier
  if grep -q '^WTC_IMAGE=' .env.prod; then
    sed -i "s|^WTC_IMAGE=.*|WTC_IMAGE=$1|" .env.prod
  else
    printf 'WTC_IMAGE=%s\n' "$1" >> .env.prod
  fi
}

wait_healthy() {
  local deadline=$((SECONDS + WAIT_SECONDS)) cid status
  while [ "$SECONDS" -lt "$deadline" ]; do
    cid="$(compose ps -q api 2>/dev/null || true)"
    if [ -n "$cid" ]; then
      status="$(docker inspect -f '{{if .State.Running}}{{.State.Health.Status}}{{else}}stopped{{end}}' "$cid" 2>/dev/null || echo unknown)"
      case "$status" in
        healthy) return 0 ;;
        stopped) return 1 ;;   # le conteneur s'est arrêté : inutile d'attendre
      esac
    fi
    sleep 3
  done
  return 1
}

PREVIOUS="$(grep '^WTC_IMAGE=' .env.prod | head -n1 | cut -d= -f2- || true)"
echo "Image actuelle  : ${PREVIOUS:-<aucune>}"
echo "Image à déployer: $IMAGE"

set_image "$IMAGE"
compose pull api
compose up -d api

if wait_healthy; then
  echo "Déploiement réussi : l'API est « healthy »."
  docker image prune -f >/dev/null 2>&1 || true
  exit 0
fi

echo "::error::L'API n'est pas devenue « healthy » en ${WAIT_SECONDS}s : journaux ci-dessous." >&2
compose logs --tail=60 api >&2 || true

if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$IMAGE" ]; then
  echo "Retour arrière vers $PREVIOUS" >&2
  set_image "$PREVIOUS"
  compose up -d api
  if wait_healthy; then
    echo "Retour arrière effectué : l'ancienne version tourne de nouveau." >&2
  else
    echo "::error::L'ancienne version ne démarre pas non plus : intervention manuelle nécessaire." >&2
  fi
else
  echo "Aucune version précédente vers laquelle revenir." >&2
fi
exit 1
