# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the pre-built installDist distribution
COPY server/build/install/server /app

# SQLite storage directory
RUN mkdir -p /app/data
VOLUME /app/data

# Default container environment
ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080
ENV SQLITE_DB_PATH=/app/data/server-mochame.db
ENV SQLITE_DB_BACKUP_DIR=/app/data/backups

EXPOSE 8080

ENTRYPOINT ["/app/bin/server"]