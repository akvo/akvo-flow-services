FROM openjdk:8-jre-alpine
MAINTAINER Akvo Foundation <devops@akvo.org>

RUN set -ex; apk update && \
    apk add git && \
    rm -rf /var/cache/apk/*

WORKDIR /app
COPY target/akvo-flow-services.jar /app/akvo-flow-services.jar

CMD ["java", "-jar", "akvo-flow-services.jar", "/etc/config/akvo-flow-services/config.edn" ]
