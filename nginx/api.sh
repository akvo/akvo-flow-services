#!/usr/bin/env bash

set -eu

function log {
   echo "$(date +"%T") - INFO - $*"
}

log "Getting Auth0 token"
token=$(curl --silent \
	     --data "client_id=0C1NQcW6mMWyvvOOZ6Dd5P6hJrjSbwPL" \
	     --data "username=${AUTH0_USER}" \
	     --data "password=${AUTH0_PASSWORD}" \
	     --data "grant_type=password" \
	     --data "scope=openid email" \
	     --url "https://akvotest.eu.auth0.com/oauth/token" \
	    | jq -M -r .id_token)

log "Auth0 token: $(echo token | cut -c-6)"
URL="${1}"
shift

curl --silent \
     --header "Authorization: Bearer ${token}" \
     --request GET \
     "$@" \
     --url "${URL}" | jq -M .
