#!/usr/bin/env bash

docker run --rm -e SLACK_CLI_TOKEN -v ~/.config:/home/akvo/.config -v "$(pwd)":/project \
  -it akvo/akvo-devops:20200511.221343.5c060a8 \
  promote-test-to-prod.sh akvo-flow-services akvo-flow-services-version akvo-flow-services