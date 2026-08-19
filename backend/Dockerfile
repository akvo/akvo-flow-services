FROM openjdk:8u162-jdk-stretch
MAINTAINER Akvo Foundation <devops@akvo.org>

WORKDIR /app
COPY prod/start-prod-env.sh /app/start-prod-env.sh
RUN chmod u+x /app/start-prod-env.sh
COPY target/akvo-flow-services.jar /app/akvo-flow-services.jar

CMD ["/app/start-prod-env.sh"]
