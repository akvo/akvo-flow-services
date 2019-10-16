#!/usr/bin/env bash

set -eu

if [ ! -d "/akvo-flow-server-config/" ]; then
    echo "Creating fake git repo with Flow config"
    cp -r dev/flow-server-config /server-config-repo
    pushd /server-config-repo > /dev/null
    git init
    git config --global user.email "you@example.com"
    git config --global user.name "Your Name"
    git add -A
    git commit -m "Initial commit"
    git clone /server-config-repo /akvo-flow-server-config
    popd > /dev/null
fi

if [[ -f "/root/.lein/profiles.clj" ]]; then
    lein repl :headless
else
    lein run dev/config.edn
fi
