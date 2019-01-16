#!/usr/bin/env bash

set -o errexit
set -o nounset

DOCKER_COMPOSE_PROJECT="${1:-akvolumen}"
LUMEN_URL="${2:-http://t1.lumen.local:3030/}"
LUMEN_USER="${3:-jerome}"
LUMEN_PASSWORD="${4:-password}"
CYPRESS_RECORD_KEY="${CYPRESS_RECORD_KEY:-}"

starttime=$(date +%s)

while [ $(( $(date +%s) - 120 )) -lt "${starttime}" ]; do

    if wget "${LUMEN_URL}" -O - -nv; then
        exit 0
    else
        echo "Waiting for the client to start..."
        sleep 5
    fi
done

if [[ "${DOCKER_COMPOSE_PROJECT}" == "akvolumen" ]]; then
    docker-compose \
	run --no-deps \
        -e CYPRESS_LUMEN_URL="${LUMEN_URL}" \
	-e CYPRESS_LUMEN_USER="${LUMEN_USER}" \
	-e CYPRESS_LUMEN_PASSWORD="${LUMEN_PASSWORD}" \
	-e CYPRESS_RECORD_KEY="${CYPRESS_RECORD_KEY}" \
	fe-e2e-tests run.sh
else
    docker-compose \
	-p akvo-lumen-ci \
	-f docker-compose.yml \
	-f docker-compose.ci.yml \
	run --no-deps \
	-e CYPRESS_LUMEN_URL="${LUMEN_URL}" \
	-e CYPRESS_LUMEN_USER="${LUMEN_USER}" \
	-e CYPRESS_LUMEN_PASSWORD="${LUMEN_PASSWORD}" \
	-e CYPRESS_RECORD_KEY="${CYPRESS_RECORD_KEY}" \
	fe-e2e-tests run.sh
fi
