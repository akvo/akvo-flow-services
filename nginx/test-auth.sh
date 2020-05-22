#!/usr/bin/env bash

set -eu

wait4ports nginx=tcp://localhost:8082 upstream=tcp://localhost:3000

curl --silent --request GET --url "http://localhost:8082/" | grep 'OK'
curl --silent --request GET --url "http://localhost:8082/invalidate" | grep 'OK'
curl --verbose "http://localhost:8082/sign?instance=akvoflow-uat1" 2>&1 | grep 'HTTP.*401'
./api.sh "http://localhost:8082/sign?instance=akvoflow-uat1" | grep '{}'
