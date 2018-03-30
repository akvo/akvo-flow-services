#!/usr/bin/env bash

set -e

if [ ! -d "/akvo-flow-server-config/" ]; then
    echo "Checking out Github repo"
    git clone git@github.com:akvo/akvo-flow-server-config.git /akvo-flow-server-config
fi

java -jar akvo-flow-services.jar /etc/config/akvo-flow-services/config.edn