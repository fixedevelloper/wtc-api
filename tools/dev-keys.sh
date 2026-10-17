#!/usr/bin/env bash
# Génère les secrets de DÉVELOPPEMENT dans .local/ (ignoré par git). Ne jamais les réutiliser en production.
#  - jwt-private.pem / jwt-public.pem : paire RSA qui signe puis vérifie les jetons d'accès
#  - encryption.key                   : clé AES-256 (Base64) qui chiffre les secrets stockés (TOTP, liens dans l'outbox)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local
if [ -f .local/jwt-private.pem ]; then echo "Secrets déjà présents dans .local/ (supprimez-les pour les régénérer)."; exit 0; fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/jwt-private.pem
openssl rsa -in .local/jwt-private.pem -pubout -out .local/jwt-public.pem
openssl rand -base64 32 | tr -d '\n' > .local/encryption.key
# La clé privée doit être lisible par l'utilisateur non privilégié du conteneur (uid 100) qui la monte : acceptable en
# développement car .local/ est ignoré par git et ne sort pas de la machine ; en production, l'orchestrateur monte le secret.
chmod 644 .local/jwt-private.pem
chmod 600 .local/encryption.key
echo "Secrets créés dans .local/ : jwt-private.pem, jwt-public.pem, encryption.key"
