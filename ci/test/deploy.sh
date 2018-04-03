#!/usr/bin/env bash

set -eu

export PROJECT_NAME=akvo-lumen

if [[ "${TRAVIS_BRANCH}" != "develop" ]] && [[ "${TRAVIS_BRANCH}" != "master" ]]; then
    exit 0
fi

if [[ "${TRAVIS_PULL_REQUEST}" != "false" ]]; then
    exit 0
fi

# Making sure gcloud and kubectl are installed and up to date
gcloud components install kubectl
gcloud components update
gcloud version
which gcloud kubectl

# Authentication with gcloud and kubectl
gcloud auth activate-service-account --key-file ci/gcloud-service-account.json
gcloud config set project akvo-lumen
gcloud config set container/cluster europe-west1-d
gcloud config set compute/zone europe-west1-d
gcloud config set container/use_client_certificate True

if [[ "${TRAVIS_BRANCH}" == "master" ]]; then
    gcloud container clusters get-credentials lumen
else
    gcloud container clusters get-credentials test
fi

# Pushing images
gcloud docker -- push eu.gcr.io/${PROJECT_NAME}/akvo-flow-services

# Deploying

sed -e "s/\$TRAVIS_COMMIT/$TRAVIS_COMMIT/" ci/test/akvo-flow-services.yaml.template > akvo-flow-services.yaml

kubectl apply -f akvo-flow-services.yaml

ci/test/wait-for-k8s-deployment-to-be-ready.sh

#docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml run --no-deps tests /import-and-run.sh kubernetes-test