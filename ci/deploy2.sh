#!/usr/bin/env bash

set -eu

function log {
    echo "$(date +"%T") - INFO - $*"
}

export PROJECT_NAME=akvo-lumen

if [[ "${CI_BRANCH}" != "issue/2439-sem-deploy2" ]]; then
    exit 0
fi

log Making sure gcloud and kubectl are installed and up to date
# gcloud components install kubectl
# gcloud components update
# gcloud version
# which gcloud kubectl

log Authentication with gcloud and kubectl
gcloud auth activate-service-account --key-file=/home/semaphore/.secrets/gcp.json
gcloud config set project akvo-lumen
gcloud config set container/cluster europe-west1-d
gcloud config set compute/zone europe-west1-d
gcloud config set container/use_client_certificate False

if [[ "${CI_TAG:-}" =~ promote-.* ]]; then
    log Environment is production
    gcloud container clusters get-credentials production
else
    log Environement is test
    gcloud container clusters get-credentials test
fi

log Finding blue/green state
LIVE_COLOR=$(./ci/live-color.sh)
log LIVE is "${LIVE_COLOR}"
DARK_COLOR=$(./ci/helpers/dark-color.sh "$LIVE_COLOR")
