use std::{net::SocketAddr, path::PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EmailMode {
    Log,
    Smtp,
}

#[derive(Debug, Clone)]
pub struct Config {
    pub bind: SocketAddr,
    pub database_url: String,
    pub server_secret: Vec<u8>,
    pub email_mode: EmailMode,
    pub smtp_host: Option<String>,
    pub smtp_user: Option<String>,
    pub smtp_pass: Option<String>,
    pub smtp_from: Option<String>,
    pub public_url: String,
    pub catalog_dir: PathBuf,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Config> {
        let get = |k: &str| std::env::var(k).ok().filter(|v| !v.is_empty());
        let bind = get("BIND").unwrap_or_else(|| "0.0.0.0:8080".into()).parse()?;
        let secret_hex = get("SERVER_SECRET")
            .ok_or_else(|| anyhow::anyhow!("SERVER_SECRET (hex) is required"))?;
        let server_secret = hex::decode(secret_hex.trim())
            .map_err(|_| anyhow::anyhow!("SERVER_SECRET must be hex"))?;
        if server_secret.len() < 16 {
            anyhow::bail!("SERVER_SECRET must decode to at least 16 bytes");
        }
        let email_mode = match get("EMAIL_MODE").as_deref().unwrap_or("log") {
            "log" => EmailMode::Log,
            "smtp" => EmailMode::Smtp,
            other => anyhow::bail!("EMAIL_MODE must be 'log' or 'smtp', got '{other}'"),
        };
        Ok(Config {
            bind,
            database_url: get("DATABASE_URL").unwrap_or_else(|| "sqlite:meditracker.db".into()),
            server_secret,
            email_mode,
            smtp_host: get("SMTP_HOST"),
            smtp_user: get("SMTP_USER"),
            smtp_pass: get("SMTP_PASS"),
            smtp_from: get("SMTP_FROM"),
            public_url: get("PUBLIC_URL").unwrap_or_else(|| "http://localhost:8080".into()),
            catalog_dir: get("CATALOG_DIR").unwrap_or_else(|| "catalog".into()).into(),
        })
    }
}
