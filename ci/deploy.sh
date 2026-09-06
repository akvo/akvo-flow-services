#!/usr/bin/env bash

set -eu

function log {
   echo "$(date +"%T") - INFO - $*"
}

export PROJECT_NAME=akvo-lumen

if [[ "${TRAVIS_BRANCH}" != "develop" ]] && [[ ! "${TRAVIS_TAG:-}" =~ promote-.* ]]; then
    exit 0
fi

if [[ "${TRAVIS_PULL_REQUEST}" != "false" ]]; then
    exit 0
fi

log Authentication with gcloud and kubectl
gcloud auth activate-service-account --key-file=/home/semaphore/.secrets/gcp.json
gcloud config set project akvo-lumen
gcloud config set container/cluster europe-west1-d
gcloud config set compute/zone europe-west1-d
gcloud config set container/use_client_certificate False

ENVIRONMENT=test
if [[ "${TRAVIS_TAG:-}" =~ promote-.* ]]; then
    log Environment is production
    gcloud container clusters get-credentials production
    ENVIRONMENT=production
    POD_CPU_REQUESTS="400m"
    POD_CPU_LIMITS="10000m"
    POD_MEM_REQUESTS="5120Mi"
    POD_MEM_LIMITS="5632Mi"
    # Production's reports volume was expanded to 350Gi at some point without the
    # template following. A PersistentVolumeClaim can grow but never shrink, so every
    # apply since then was rejected -- see the commit message.
    REPORTS_STORAGE="350Gi"
else
    log Environment is test
    gcloud container clusters get-credentials test
    POD_CPU_REQUESTS="200m"
    POD_CPU_LIMITS="400m"
    POD_MEM_REQUESTS="1024Mi"
    POD_MEM_LIMITS="2048Mi"
    # Test's volume is genuinely 200Gi. Naming the sizes separately keeps this fix from
    # silently growing it to production's, which could not be undone.
    REPORTS_STORAGE="200Gi"
    log Pushing images
    gcloud auth configure-docker
    docker push "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:${TRAVIS_COMMIT}"
    docker push "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services-proxy:${TRAVIS_COMMIT}"
fi

log Deploying

sed -e "s/\$TRAVIS_COMMIT/$TRAVIS_COMMIT/" \
  -e "s/\${ENVIRONMENT}/${ENVIRONMENT}/" \
  -e "s/\${POD_CPU_REQUESTS}/${POD_CPU_REQUESTS}/" \
  -e "s/\${POD_MEM_REQUESTS}/${POD_MEM_REQUESTS}/" \
  -e "s/\${POD_CPU_LIMITS}/${POD_CPU_LIMITS}/" \
  -e "s/\${POD_MEM_LIMITS}/${POD_MEM_LIMITS}/" \
  -e "s/\${REPORTS_STORAGE}/${REPORTS_STORAGE}/" \
  ci/akvo-flow-services.yaml.template > akvo-flow-services.yaml

kubectl apply -f akvo-flow-services.yaml

ci/wait-for-k8s-deployment-to-be-ready.sh

#docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml run --no-deps tests /import-and-run.sh kubernetes-test

log Done
