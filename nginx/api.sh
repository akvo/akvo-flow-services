#!/usr/bin/env bash

set -eu

token=$(curl --silent \
	     --data "client_id=0C1NQcW6mMWyvvOOZ6Dd5P6hJrjSbwPL" \
	     --data "username=${AUTH0_USER}" \
	     --data "password=${AUTH0_PASSWORD}" \
	     --data "grant_type=password" \
	     --data "scope=openid email" \
	     --url "https://akvotest.eu.auth0.com/oauth/token" \
	    | jq -M -r .id_token)


URL="${1}"
shift

curl --silent \
     --header "Authorization: Bearer ${token}" \
     --request GET \
     "$@" \
     --url "${URL}" | jq -M .
