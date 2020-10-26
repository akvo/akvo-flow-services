#!/usr/bin/env bash

set -eu

function log {
   echo "$(date +"%T") - INFO - $*"
}

log "Waiting for local test environment to be up"
wait4ports nginx=tcp://localhost:8082 upstream=tcp://localhost:3000

log "Check service is up"
curl --silent --request GET --url "http://localhost:8082/" | grep 'OK'
log "Check /invalidate is up"
curl --silent --request GET --url "http://localhost:8082/invalidate" | grep 'OK'

log "Check sign fails without an Auth0 token"
curl --verbose "http://localhost:8082/sign?instance=akvoflow-uat1" 2>&1 | grep 'HTTP.*401'

log "check sign works with a valid Auth0 token"
./api.sh "http://localhost:8082/sign?instance=akvoflow-uat1" | grep '{}'
log "Auth0 proxy test passed"