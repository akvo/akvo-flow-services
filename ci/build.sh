#!/usr/bin/env bash

set -euo pipefail

function log {
   echo "$(date +"%T") - INFO - $*"
}

export PROJECT_NAME=akvo-lumen
export TRAVIS_COMMIT="${TRAVIS_COMMIT:=local}"

if [[ "${TRAVIS_TAG:-}" =~ promote-.* ]]; then
    echo "Skipping build as it is a prod promotion"
    exit 0
fi

log Login to DockerHub
echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

log Building backend dev container
docker build --rm=false -t akvo-flow-services-dev:develop backend -f backend/Dockerfile-dev
log Running backend test and uberjar
docker run -v "${HOME}/.m2:/root/.m2" -v "$(pwd)/backend:/app" akvo-flow-services-dev:develop lein 'do' test, uberjar

log Building production container
docker build --rm=false \
       -t "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:${TRAVIS_COMMIT}" \
       -t "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:develop" \
       -t "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:latest" \
       ./backend

log Building proxy and checking container
(
    cd nginx
    docker build \
	   -t "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services-proxy:latest" \
	   -t "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services-proxy:${TRAVIS_COMMIT}" .

    docker run \
	   --rm \
	   --entrypoint /usr/local/openresty/bin/openresty \
	   "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services-proxy" \
	   -t -c /usr/local/openresty/nginx/conf/nginx.conf

    docker run \
	   --rm \
	   --entrypoint /usr/local/openresty/bin/openresty \
	   "eu.gcr.io/${PROJECT_NAME}/akvo-flow-services-proxy" \
	   -t -c /usr/local/openresty/nginx/conf/nginx-test.conf

    docker-compose up -d
    docker-compose exec testnetwork /bin/sh -c 'cd /usr/local/src/ && ./entrypoint.sh ./test-auth.sh'
    docker-compose down -v
)

log Starting docker compose env
docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml up -d --build
log Running integration tests
docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml run --no-deps tests lein test :integration

log Done

