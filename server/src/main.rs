use std::sync::Arc;

use meditracker_server::{
    app,
    config::{Config, EmailMode},
    init_db,
    limits::Limits,
    mailer::{LogMailer, Mailer},
    system_clock, AppState,
};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    let config = Config::from_env()?;
    let db = init_db(&config.database_url).await?;
    let mailer: Arc<dyn Mailer> = match config.email_mode {
        EmailMode::Log => Arc::new(LogMailer),
        EmailMode::Smtp => anyhow::bail!("EMAIL_MODE=smtp is not implemented yet"),
    };
    let bind = config.bind;
    let state = AppState {
        db,
        config: Arc::new(config),
        mailer,
        limits: Arc::new(Limits::new()),
        clock: system_clock(),
    };
    let listener = tokio::net::TcpListener::bind(bind).await?;
    tracing::info!("listening on {bind}");
    axum::serve(listener, app(state)).await?;
    Ok(())
}
