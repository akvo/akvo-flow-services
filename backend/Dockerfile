FROM openjdk:8-jre-alpine
MAINTAINER Akvo Foundation <devops@akvo.org>

WORKDIR /app
COPY target/akvo-flow-services.jar /app/akvo-flow-services.jar

CMD ["java", "-jar", "akvo-flow-services.jar"]
