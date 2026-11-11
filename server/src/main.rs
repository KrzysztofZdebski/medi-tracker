use std::sync::Arc;

use meditracker_server::{
    app,
    config::{Config, EmailMode},
    init_db,
    limits::Limits,
    mailer::{LogMailer, Mailer, SmtpMailer},
    maintenance,
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
        EmailMode::Smtp => {
            let need = |v: &Option<String>, name: &str| {
                v.clone()
                    .ok_or_else(|| anyhow::anyhow!("{name} is required when EMAIL_MODE=smtp"))
            };
            Arc::new(SmtpMailer::new(
                &need(&config.smtp_host, "SMTP_HOST")?,
                &need(&config.smtp_user, "SMTP_USER")?,
                &need(&config.smtp_pass, "SMTP_PASS")?,
                &need(&config.smtp_from, "SMTP_FROM")?,
            )?)
        }
    };
    let bind = config.bind;
    let state = AppState {
        db,
        config: Arc::new(config),
        mailer,
        limits: Arc::new(Limits::new()),
        clock: system_clock(),
    };
    let backup_dir = std::path::PathBuf::from(
        std::env::var("BACKUP_DIR").unwrap_or_else(|_| "/data/backups".into()),
    );
    maintenance::spawn(state.clone(), backup_dir);
    let listener = tokio::net::TcpListener::bind(bind).await?;
    tracing::info!("listening on {bind}");
    axum::serve(listener, app(state)).await?;
    Ok(())
}
