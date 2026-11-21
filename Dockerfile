FROM eclipse-temurin:21.0.13.1_2-jre-jammy

RUN groupadd --gid 10001 oms \
    && useradd --uid 10001 --gid oms --no-create-home --shell /usr/sbin/nologin oms
WORKDIR /app
COPY --chown=10001:10001 oms-bootstrap/target/oms-bootstrap-0.1.0-SNAPSHOT.jar /app/oms.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/oms.jar"]
