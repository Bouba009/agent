FROM gradle:8.10-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/src/build/install/tirorda-ai /app
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["/app/bin/tirorda-ai"]
