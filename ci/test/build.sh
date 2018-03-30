#!/usr/bin/env bash

set -e

export PROJECT_NAME=akvo-lumen
BRANCH_NAME="${TRAVIS_BRANCH:=unknown}"

if [ -z "$TRAVIS_COMMIT" ]; then
    export TRAVIS_COMMIT=local
fi

docker build --rm=false -t akvo-flow-services-dev:develop backend -f backend/Dockerfile-dev
docker run -v $HOME/.m2:/root/.m2 -v `pwd`/backend:/app akvo-flow-services-dev:develop lein do test, uberjar

docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:$TRAVIS_COMMIT ./backend
docker tag eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:$TRAVIS_COMMIT eu.gcr.io/${PROJECT_NAME}/akvo-flow-services:develop

#docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml up -d --build
#docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml run --no-deps tests /import-and-run.sh integration-test