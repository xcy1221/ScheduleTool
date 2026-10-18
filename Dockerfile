FROM node:22.22.0-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci --no-fund --no-audit
COPY frontend/ ./
RUN npm run build
FROM maven:3.9.15-eclipse-temurin-21 AS build
WORKDIR /project
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
COPY --from=web /web/dist frontend/dist
RUN mvn -B -f backend/pom.xml verify
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S schedule && adduser -S schedule -G schedule && mkdir /app/logs && chown schedule:schedule /app/logs
COPY --from=build --chown=schedule:schedule /project/backend/target/scheduletool.jar /app/app.jar
USER schedule
ENV APP_BIND=0.0.0.0 APP_PORT=8080 APP_CONTEXT=/schedule
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD wget -q --spider http://127.0.0.1:8080/schedule/ || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
