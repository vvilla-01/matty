#!/usr/bin/env bash
# Throwaway Mattermost + PostgreSQL for Matty development. No Docker, no root.
# Downloads Mattermost Team Edition and a portable PostgreSQL into $MATTY_DEV_DIR
# (default ~/.cache/matty-dev) and serves on :8065. The emulator reaches it at
# http://localhost:8065 after `adb reverse tcp:8065 tcp:8065`, which is also its
# Site URL (SITE_URL changes that): the address people sign in to, and one that
# is this machine, which is where VillaNET's stand-in build (villanet.sh) agrees
# to start. Seed it with: python3 tools/dev-server/seed.py
set -euo pipefail
MM_VERSION="${MM_VERSION:-12.0.2}"
PG_VERSION="${PG_VERSION:-17.6.0}"
DIR="${MATTY_DEV_DIR:-$HOME/.cache/matty-dev}"
mkdir -p "$DIR"
cd "$DIR"

if [ ! -x pg/bin/postgres ]; then
  echo "Downloading PostgreSQL $PG_VERSION…"
  mkdir -p pg-dl pg
  curl -sSfL -o pg-dl/pg.jar "https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/$PG_VERSION/embedded-postgres-binaries-linux-amd64-$PG_VERSION.jar"
  (cd pg && unzip -o -q ../pg-dl/pg.jar && tar xJf postgres-linux-x86_64.txz)
fi
if [ ! -x mm/mattermost/bin/mattermost ]; then
  echo "Downloading Mattermost $MM_VERSION…"
  mkdir -p mm
  curl -sSfL "https://releases.mattermost.com/$MM_VERSION/mattermost-team-$MM_VERSION-linux-amd64.tar.gz" | tar xz -C mm
fi
if [ ! -d pgdata ]; then
  pg/bin/initdb -D pgdata -U mmuser --auth=trust -E UTF8 >/dev/null
fi
pg/bin/pg_ctl -D pgdata -o "-p 5544 -c unix_socket_directories=''" -l pg.log status >/dev/null 2>&1 ||
  pg/bin/pg_ctl -D pgdata -o "-p 5544 -c unix_socket_directories=''" -l pg.log start -w
psql -h localhost -p 5544 -U mmuser -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='mattermost'" | grep -q 1 ||
  psql -h localhost -p 5544 -U mmuser -d postgres -c "CREATE DATABASE mattermost" >/dev/null

export MM_SQLSETTINGS_DRIVERNAME=postgres
export MM_SQLSETTINGS_DATASOURCE="postgres://mmuser@localhost:5544/mattermost?sslmode=disable&connect_timeout=10"
export MM_SERVICESETTINGS_SITEURL="${SITE_URL:-http://localhost:8065}"
# This machine only: the server has an admin with a known password and takes plugin uploads, so
# nobody else on the network may reach it. The emulator does through "adb reverse", which
# connects here on the loopback. LISTEN_ADDRESS=:8065 opens it to the network on purpose.
export MM_SERVICESETTINGS_LISTENADDRESS="${LISTEN_ADDRESS:-127.0.0.1:8065}"
export MM_SERVICESETTINGS_ENABLELOCALMODE=false
export MM_SERVICESETTINGS_ENABLEUSERACCESSTOKENS=true
export MM_SERVICESETTINGS_ENABLEGIFPICKER=true
export MM_SERVICESETTINGS_ENABLECUSTOMEMOJI=true
export MM_SERVICESETTINGS_ENABLELINKPREVIEWS=true
export MM_SERVICESETTINGS_COLLAPSEDTHREADS=default_on
export MM_PLUGINSETTINGS_ENABLEUPLOADS=true
export MM_TEAMSETTINGS_ENABLEOPENSERVER=true
export MM_EMAILSETTINGS_REQUIREEMAILVERIFICATION=false
export MM_EMAILSETTINGS_SENDEMAILNOTIFICATIONS=false
export MM_LOGSETTINGS_CONSOLELEVEL=WARN
export MM_FILESETTINGS_DIRECTORY="$DIR/mmdata/"
export MM_RATELIMITSETTINGS_ENABLE=false
cd mm/mattermost
echo "Mattermost on http://localhost:8065 (emulator: adb reverse tcp:8065 tcp:8065, then the same address)"
exec ./bin/mattermost
