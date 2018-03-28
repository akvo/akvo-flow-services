#!/usr/bin/env bash

if [ ! -d "/akvo-flow-server-config/" ]; then
    mkdir -p /server-config-repo
    cp -r flow-server-config /server-config-repo
    pushd /server-config-repo
    git init
    git commit -a -m "Initial commit"
    git clone /server-config-repo /akvo-flow-server-config
    popd
fi

lein run dev/config.edn