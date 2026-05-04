# Deploy backend (Holape — Spring Boot)

Este directorio contiene los artefactos para desplegar el backend en cualquiera de las dos VMs:

- **mws.digitalclub.com.pe** — usa `holape.env.mws.example`
- **infinance.innovag.com.pe** — usa `holape.env.infinance.example`

El backend escucha en `127.0.0.1:8443` (HTTP plano). El TLS y el `proxy_pass` los maneja nginx en la VM (ver `holape-angular/deploy/nginx.<dominio>.conf`).

## Bootstrap (una sola vez por VM)

```bash
# 1. Usuario y carpetas
sudo useradd --system --no-create-home --shell /usr/sbin/nologin holape || true
sudo mkdir -p /opt/holape /etc/holape /var/log/holape
sudo chown -R holape:holape /opt/holape /var/log/holape
sudo chmod 750 /etc/holape

# 2. Variables de entorno (editar JWT_SECRET y verificar credenciales)
cd ~/digitalclub/digitalclub-backend
sudo cp deploy/holape.env.mws.example /etc/holape/holape.env       # en la VM mws
# o
sudo cp deploy/holape.env.infinance.example /etc/holape/holape.env # en la VM infinance

sudo chown root:holape /etc/holape/holape.env
sudo chmod 640 /etc/holape/holape.env

# Generar JWT_SECRET único por VM y editarlo en el .env
JWT=$(openssl rand -base64 48)
sudo sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$JWT|" /etc/holape/holape.env

# 3. systemd unit
sudo cp deploy/holape.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable holape

# 4. Reset de Flyway (solo si la DB ya tenía flyway_schema_history)
# Conectarse a la RDS y ejecutar:
#   DROP TABLE IF EXISTS flyway_schema_history;
# La primera vez que arranque el backend con FLYWAY_ENABLED=true,
# Flyway baselina automáticamente (no aplica nada porque el repo
# no contiene migraciones).
```

## Deploy continuo

```bash
cd ~/digitalclub/digitalclub-backend
./deploy/deploy.sh
```

El script:
1. Hace `git pull` (reset duro a `origin/main`)
2. `mvn clean package -DskipTests`
3. Copia el JAR a `/opt/holape/holape-1.0.0.jar`
4. `systemctl restart holape`
5. Muestra el estado y las últimas 50 líneas de log

## Operación

```bash
sudo systemctl status holape                # estado
sudo systemctl restart holape               # reinicio manual
sudo journalctl -u holape -f                # logs en vivo
sudo journalctl -u holape --since "1h ago"  # último hora
tail -f /var/log/holape/app.log             # mirror del stdout/stderr
```

## Diferencias entre VMs (resumen)

| Variable | mws | infinance |
|---|---|---|
| `DB_HOST` | `mws.clbzi5ldkemb...` | `digital-club.clbzi5ldkemb...` |
| `AWS_S3_BUCKET` | `mws-prod` | `infinance-prod` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | distintas | distintas |
| `APP_MAIL_FROM` | `noreplay@mws.digitalclub.com.pe` | `noreplay@infinance.innovag.com.pe` |
| `APP_MAIL_FROM_NAME` | `MWS DIGITAL CLUB` | `Infinance Cobranza` |
| `APP_SMS_NAME` | `MWS` | `InFinance` |
| `ALLOWED_ORIGINS` | `https://mws.digitalclub.com.pe` | `https://infinance.innovag.com.pe` |
| `JWT_SECRET` | único, generado en bootstrap | único, generado en bootstrap |

> Las credenciales de SES, RDS user/pass y SMS de Digital Club son comunes a ambos dominios — solo cambia el `from email` y los buckets.

## Reset de Flyway (post-borrado de migraciones)

Las 16 migraciones `V2026_01_*`/`V2026_02_*` se eliminaron del repo porque ya estaban aplicadas en ambas RDS. Para que Flyway tome el estado actual como nueva línea base:

1. Verificar que `FLYWAY_ENABLED=true` en `/etc/holape/holape.env`.
2. Conectarse a la RDS y dropear la tabla de historia (si existía):
   ```sql
   DROP TABLE IF EXISTS flyway_schema_history;
   ```
3. `sudo systemctl restart holape`
4. Verificar:
   ```bash
   sudo journalctl -u holape | grep -iE 'flyway|migration'
   ```
   Debe verse: `Successfully baselined schema with version: 0` y `Schema is up to date`.

A partir de aquí, cualquier nueva migración (`src/main/resources/db/migration/V<fecha>__<desc>.sql`) se aplicará en el siguiente reinicio de cada VM.
