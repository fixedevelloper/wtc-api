# syntax=docker/dockerfile:1.7

# ---------- Étape 1 : compilation (JDK) ----------
FROM eclipse-temurin:24-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q dependency:go-offline
COPY api api
COPY src src
# Les tests et contrôles de qualité tournent en CI avant la construction de l'image.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true -Djacoco.skip=true package
RUN java -Djarmode=tools -jar target/wtc-api-*.jar extract --layers --destination /extracted \
    # Nom fixe : le point d'entrée ne dépend pas de la version du pom.
    && mv /extracted/application/wtc-api-*.jar /extracted/application/app.jar

# ---------- Étape 2 : exécution (JRE seul, utilisateur sans privilège) ----------
FROM eclipse-temurin:24-jre-alpine
RUN addgroup -S wtc && adduser -S wtc -G wtc
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER wtc

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 8080 : API publique ; 8081 : supervision (à ne jamais publier hors du réseau interne)
EXPOSE 8080 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8081/actuator/health/liveness >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
