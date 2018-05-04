FROM openjdk:8u162-jdk-stretch
MAINTAINER Akvo Foundation <devops@akvo.org>

RUN set -ex; apt-get update && \
    apt-get install git openssh-client && \
    rm -rf /var/cache/apt/*

WORKDIR /app
COPY prod/start-prod-env.sh /app/start-prod-env.sh
RUN chmod u+x /app/start-prod-env.sh
COPY target/akvo-flow-services.jar /app/akvo-flow-services.jar

CMD ["/app/start-prod-env.sh"]
