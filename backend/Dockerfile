FROM openjdk:8-jre-alpine
MAINTAINER Akvo Foundation <devops@akvo.org>

RUN set -ex; apk update && \
    apk add git && \
    rm -rf /var/cache/apk/*

WORKDIR /app
COPY prod/start-prod-env.sh /app/start-prod-env.sh
RUN chmod u+x /app/start-prod-env.sh
COPY target/akvo-flow-services.jar /app/akvo-flow-services.jar

CMD ["/app/start-prod-env.sh"]
