# Build context: the repository root (see docker-compose.yml), so catalog/ can be copied.
FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY catalog/ /app/
COPY server/catalog-job/run.sh /app/run.sh
RUN chmod +x /app/run.sh
CMD ["/app/run.sh"]
