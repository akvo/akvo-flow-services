#!/usr/bin/env bash

set -euo pipefail

function log {
   echo "$(date +"%T") - INFO - $*"
}

export PROJECT_NAME=akvo-lumen
export TRAVIS_COMMIT="${TRAVIS_COMMIT:=local}"

log Building backend dev container
docker build --rm=false -t akvo-flow-services-dev:develop backend -f backend/Dockerfile-dev
log Running backend test and uberjar
docker run -v "${HOME}/.m2:/root/.m2" -v "$(pwd)/backend:/app" akvo-flow-services-dev:develop lein 'do' test, uberjar

log Building production container
docker build --rm=false -t "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:${TRAVIS_COMMIT}" ./backend
docker tag "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:${TRAVIS_COMMIT}" "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:develop"

log Starting docker compose env
docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml up -d --build
log Running integration tests
docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml run --no-deps tests lein test :integration

log Done
