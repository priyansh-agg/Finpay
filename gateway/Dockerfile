# Build with JDK 21 (Spring Boot 3.4 does not support newer JDKs yet).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# The base image already IS JDK 21 — point the maven-toolchains-plugin at it.
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0">' \
    '  <toolchain><type>jdk</type>' \
    '    <provides><version>21</version><vendor>openjdk</vendor></provides>' \
    '    <configuration><jdkHome>/opt/java/openjdk</jdkHome></configuration>' \
    '  </toolchain>' \
    '</toolchains>' > /root/.m2/toolchains.xml
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
