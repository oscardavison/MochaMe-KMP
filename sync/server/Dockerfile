# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre-jammy

# Create non-root user matching the host UID/GID (1000)
RUN groupadd -g 1000 appuser && \
    useradd -u 1000 -g appuser -m -s /bin/bash appuser

WORKDIR /app

# Ensure /app and /app/data are owned by appuser
RUN mkdir -p /app/data && chown -R appuser:appuser /app

# Copy binaries with correct non-root ownership
COPY --chown=appuser:appuser server/build/install/server /app

# Switch to non-root user for runtime
USER appuser

VOLUME /app/data

ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080
ENV SQLITE_DB_PATH=/app/data/server-mochame.db
ENV SQLITE_DB_BACKUP_DIR=/app/data/backups

EXPOSE 8080

ENTRYPOINT ["/app/bin/server"]