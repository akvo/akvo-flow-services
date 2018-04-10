#!/usr/bin/env sh

set -e

if [ ! -d "/akvo-flow-server-config/" ]; then
    if [ -d "dev/flow-server-config" ]; then
        echo "Creating fake git repo with Flow config"
        cp -r dev/flow-server-config /server-config-repo
        current_dir=$(pwd)
        cd /server-config-repo > /dev/null
        git init
        git config --global user.email "you@example.com"
        git config --global user.name "Your Name"
        git add -A
        git commit -m "Initial commit"
        git clone /server-config-repo /akvo-flow-server-config
        cd ${current_dir}
    else
        echo "Checking out Github repo"
        git clone git@github.com:akvo/akvo-flow-server-config.git /akvo-flow-server-config
    fi
fi

java -jar akvo-flow-services.jar /etc/config/akvo-flow-services/config.edn