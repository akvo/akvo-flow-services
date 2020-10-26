#!/usr/bin/env bash

set -eu

function log {
   echo "$(date +"%T") - INFO - $*"
}

log "Getting Auth0 token"
auth0_response=$(curl --silent \
	     --data "client_id=0C1NQcW6mMWyvvOOZ6Dd5P6hJrjSbwPL" \
	     --data "username=${AUTH0_USER}" \
	     --data "password=${AUTH0_PASSWORD}" \
	     --data "grant_type=password" \
	     --data "scope=openid email" \
	     --url "https://akvotest.eu.auth0.com/oauth/token")

token=$(echo "${auth0_response}" \
	    | jq -M -r .id_token || "")

if [[ -z "$token" ]]; then
  log "No token found in Auth0 response:"
  echo "$auth0_response"
  exit 1
fi

log "Auth0 token: $(echo token | cut -c-6)"
URL="${1}"
shift

curl --silent \
     --header "Authorization: Bearer ${token}" \
     --request GET \
     "$@" \
     --url "${URL}" | jq -M .
