FROM rust:1-slim-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY migrations ./migrations
COPY src ./src
RUN cargo build --release --locked --bin meditracker-server

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 --no-create-home meditracker \
    && mkdir -p /data /catalog \
    && chown meditracker:meditracker /data
COPY --from=build /src/target/release/meditracker-server /usr/local/bin/meditracker-server
USER meditracker
ENV BIND=0.0.0.0:8080
EXPOSE 8080
VOLUME /data
CMD ["meditracker-server"]
