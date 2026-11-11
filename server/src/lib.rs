pub mod config;
pub mod error;
pub mod limits;
pub mod mailer;
pub mod routes;
pub mod secrets;

use std::{str::FromStr, sync::Arc, time::Duration};

use axum::{extract::DefaultBodyLimit, routing::get, Router};
use sqlx::{
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions},
    SqlitePool,
};
use tower_http::trace::{DefaultOnResponse, TraceLayer};

use crate::{config::Config, limits::Limits, mailer::Mailer};

pub const MAX_BODY_BYTES: usize = 5 * 1024 * 1024;

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Arc<Config>,
    pub mailer: Arc<dyn Mailer>,
    pub limits: Arc<Limits>,
    /// Unix seconds; overridable in tests.
    pub clock: Arc<dyn Fn() -> i64 + Send + Sync>,
}

pub fn system_clock() -> Arc<dyn Fn() -> i64 + Send + Sync> {
    Arc::new(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0)
    })
}

/// Opens the SQLite pool (WAL, foreign keys on) and runs migrations.
pub async fn init_db(database_url: &str) -> anyhow::Result<SqlitePool> {
    let in_memory = database_url.contains(":memory:");
    let opts = SqliteConnectOptions::from_str(database_url)?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .foreign_keys(true)
        .busy_timeout(Duration::from_secs(5));
    let pool_opts = if in_memory {
        // Each in-memory connection is a separate database: keep exactly one alive.
        SqlitePoolOptions::new()
            .max_connections(1)
            .min_connections(1)
            .idle_timeout(None)
            .max_lifetime(None)
    } else {
        SqlitePoolOptions::new().max_connections(5)
    };
    let pool = pool_opts.connect_with(opts).await?;
    sqlx::migrate!("./migrations").run(&pool).await?;
    Ok(pool)
}

pub fn app(state: AppState) -> Router {
    // Logs method, path, status and latency only (no query string, headers or bodies).
    let trace = TraceLayer::new_for_http()
        .make_span_with(|req: &axum::http::Request<_>| {
            tracing::info_span!("request", method = %req.method(), path = %req.uri().path())
        })
        .on_request(())
        .on_response(DefaultOnResponse::new().level(tracing::Level::INFO));

    Router::new()
        .route("/health", get(|| async { "ok" }))
        .merge(routes::router())
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
        .layer(trace)
        .with_state(state)
}
