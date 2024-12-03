FROM eclipse-temurin:23-jre-alpine
    # Für M1 Rechner: FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine
    # Dies ist ein Java-11 Docker-Image von Adoptium.
    # Da wir generelle JDK/JREs von Adoptium bevorzugen, verwenden wir auch deren Docker-Image.
    # Hier wird die Docker-Organisation von Adoptiums-Seite aus verlinkt: https://blog.adoptium.net/2021/08/using-jlink-in-dockerfiles/
    # Hier ist die verlinkte Docker-Organisation: https://hub.docker.com/_/eclipse-temurin
    # Wir verwenden die alpine-Variante, um die Größe des Docker-Images zu minimieren.

LABEL org.opencontainers.image.source=https://github.com/minova-afis/aero.minova.cas
LABEL maintainer=service@minova.com

COPY customer-build-project/target/aero.minova.cas.jar /opt/aero.minova.cas/lib/

ENV aero_minova_core_application_root_path='/'
ENTRYPOINT ["/opt/java/openjdk/bin/java"]
CMD ["-jar", "/opt/aero.minova.cas/lib/aero.minova.cas.jar"]

VOLUME /var/lib/aero.minova.cas

EXPOSE 8084
EXPOSE 8081
