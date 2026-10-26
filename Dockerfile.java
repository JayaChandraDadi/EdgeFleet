# Builds one Java module of the reactor (MODULE = controller or device-sim).
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY proto proto
COPY config-model config-model
COPY device-sim device-sim
COPY controller controller
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B package -DskipTests -pl ${MODULE} -am \
 && JAR=$(ls ${MODULE}/target/*-exec.jar 2>/dev/null || ls ${MODULE}/target/${MODULE}-*.jar | grep -v original | head -1) \
 && cp "$JAR" /app.jar

FROM eclipse-temurin:21-jre
COPY --from=build /app.jar /app/app.jar
WORKDIR /app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
