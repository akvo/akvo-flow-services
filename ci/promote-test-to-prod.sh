#!/usr/bin/env bash

set -u

function log {
   echo "$(date +"%T") - INFO - $*"
}

PREVIOUS_CONTEXT=$(kubectl config current-context)
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

function switch_back () {
    log "Switching k8s context back to ${PREVIOUS_CONTEXT}"
    kubectl config use-context "${PREVIOUS_CONTEXT}"
}

function read_version () {
    CLUSTER=$1
    log "Reading ${CLUSTER} version"
    log "running: gcloud container clusters get-credentials ${CLUSTER} --zone europe-west1-d --project akvo-lumen"
    if ! gcloud container clusters get-credentials "${CLUSTER}" --zone europe-west1-d --project akvo-lumen; then
        log "Could not change context to ${CLUSTER}. Nothing done."
        switch_back
        exit 3
    fi

    VERSION=$(kubectl get deployments akvo-flow-services -o jsonpath="{@.spec.template.metadata.labels['akvo-flow-services-version']}")
}

read_version "test"
TEST_VERSION=$VERSION

read_version "production"
PROD_VERSION=$VERSION

log "Deployed test version is $TEST_VERSION"
log "Deployed prod version is $PROD_VERSION"
log "See https://github.com/akvo/akvo-flow-services/compare/$PROD_VERSION..$TEST_VERSION"

log "Commits to be deployed:"
echo ""
git log --oneline $PROD_VERSION..$TEST_VERSION | grep -v "Merge pull request" | grep -v "Merge branch"

"${DIR}"/helpers/generate-slack-notification.sh "${PROD_VERSION}" "${TEST_VERSION}" "I am thinking about deploying this flow services to production. Should I?" "warning"
./notify.slack.sh

read -r -e -p "Are you sure you want to promote to production? [yn] " CONFIRM
if [ "${CONFIRM}" != "y" ]; then
  log "Nothing done"
  exit 1
fi

TAG_NAME="promote-$(date +"%Y%m%d-%H%M%S")"

echo ""
read -r -e -p "Does this deployment contain a hotfix, rollback or fix-forward for a previous deployment? [yn] " FIX
if [ "${FIX}" != "n" ]; then
   PROMOTION_REASON="FIX_RELEASE"
else
   PROMOTION_REASON="REGULAR_RELEASE"
fi

"${DIR}"/helpers/generate-slack-notification.sh "${PROD_VERSION}" "${TEST_VERSION}" "Promoting Flow Service to production cluster" "warning"

log "To deploy, run: "
echo "----------------------------------------------"
echo "git tag -a $TAG_NAME $TEST_VERSION -m \"$PROMOTION_REASON\""
echo "git push origin $TAG_NAME"
echo "./notify.slack.sh"
echo "----------------------------------------------"

switch_back