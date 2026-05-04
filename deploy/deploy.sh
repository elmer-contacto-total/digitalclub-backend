#!/usr/bin/env bash
# Deploy idempotente del backend Holape.
# Bootstrap (una sola vez por VM) está documentado en deploy/README.md.
#
# Uso (en la VM):
#   cd ~/digitalclub/digitalclub-backend
#   ./deploy/deploy.sh

set -euo pipefail

REPO_DIR="${REPO_DIR:-$HOME/digitalclub/digitalclub-backend}"
JAR_NAME="holape-1.0.0.jar"
INSTALL_DIR="/opt/holape"
SERVICE_NAME="holape"

echo "==> Sincronizando repositorio en $REPO_DIR"
cd "$REPO_DIR"
git fetch origin
git reset --hard origin/main
git clean -fd

echo "==> Build con Maven"
mvn clean package -DskipTests

echo "==> Instalando JAR en $INSTALL_DIR"
sudo install -m 644 -o holape -g holape "target/$JAR_NAME" "$INSTALL_DIR/$JAR_NAME"

echo "==> Reiniciando servicio $SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

echo "==> Estado del servicio:"
sudo systemctl --no-pager status "$SERVICE_NAME" || true

echo
echo "==> Últimas 50 líneas del log:"
sudo journalctl -u "$SERVICE_NAME" -n 50 --no-pager || true

echo
echo "Deploy terminado. Para seguir el log:"
echo "    sudo journalctl -u $SERVICE_NAME -f"
