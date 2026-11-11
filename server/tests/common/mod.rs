#![allow(dead_code)]

use std::{
    path::PathBuf,
    sync::{
        atomic::{AtomicI64, Ordering},
        Arc,
    },
};

use axum::{
    body::Body,
    http::{header, Method, Request, StatusCode},
    Router,
};
use http_body_util::BodyExt;
use meditracker_server::{
    app,
    config::{Config, EmailMode},
    init_db,
    limits::Limits,
    mailer::{Mailer, MemoryMailer},
    AppState,
};
use serde_json::Value;
use sqlx::SqlitePool;
use tower::ServiceExt;

pub struct TestApp {
    pub router: Router,
    pub mailer: Arc<MemoryMailer>,
    pub db: SqlitePool,
    time: Arc<AtomicI64>,
}

/// Wraps the in-memory mailer with an artificial delay to emulate a slow SMTP server.
struct DelayMailer {
    inner: Arc<MemoryMailer>,
    delay: std::time::Duration,
}

#[async_trait::async_trait]
impl Mailer for DelayMailer {
    async fn send(&self, to: &str, subject: &str, body: &str) -> anyhow::Result<()> {
        tokio::time::sleep(self.delay).await;
        self.inner.send(to, subject, body).await
    }
}

pub async fn spawn() -> TestApp {
    spawn_with_mail_delay(0).await
}

pub async fn spawn_with_mail_delay(delay_ms: u64) -> TestApp {
    let db = init_db("sqlite::memory:").await.expect("db init");
    let mailer = Arc::new(MemoryMailer::default());
    let state_mailer: Arc<dyn Mailer> = Arc::new(DelayMailer {
        inner: mailer.clone(),
        delay: std::time::Duration::from_millis(delay_ms),
    });
    let time = Arc::new(AtomicI64::new(1_700_000_000));
    let clock_time = time.clone();
    let config = Config {
        bind: "127.0.0.1:0".parse().unwrap(),
        database_url: "sqlite::memory:".into(),
        server_secret: vec![7u8; 32],
        email_mode: EmailMode::Log,
        smtp_host: None,
        smtp_user: None,
        smtp_pass: None,
        smtp_from: None,
        public_url: "http://localhost".into(),
        catalog_dir: PathBuf::from("../catalog"),
    };
    let state = AppState {
        db: db.clone(),
        config: Arc::new(config),
        mailer: state_mailer,
        limits: Arc::new(Limits::new()),
        clock: Arc::new(move || clock_time.load(Ordering::SeqCst)),
    };
    TestApp {
        router: app(state),
        mailer,
        db,
        time,
    }
}

impl TestApp {
    /// Mails are sent off the request path; waits until at least `n` have been recorded.
    pub async fn wait_mails(&self, n: usize) {
        for _ in 0..200 {
            if self.mailer.sent.lock().unwrap().len() >= n {
                return;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        panic!("expected {n} mails");
    }

    pub fn set_time(&self, t: i64) {
        self.time.store(t, Ordering::SeqCst);
    }

    async fn send(
        &self,
        method: Method,
        path: &str,
        json: Option<Value>,
        token: Option<&str>,
    ) -> (StatusCode, Value) {
        let mut req = Request::builder().method(method).uri(path);
        if let Some(t) = token {
            req = req.header(header::AUTHORIZATION, format!("Bearer {t}"));
        }
        let body = match json {
            Some(v) => {
                req = req.header(header::CONTENT_TYPE, "application/json");
                Body::from(v.to_string())
            }
            None => Body::empty(),
        };
        let resp = self
            .router
            .clone()
            .oneshot(req.body(body).unwrap())
            .await
            .unwrap();
        let status = resp.status();
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        // Non-JSON bodies (e.g. "ok") come back as a JSON string.
        let value = serde_json::from_slice(&bytes)
            .unwrap_or_else(|_| Value::String(String::from_utf8_lossy(&bytes).into_owned()));
        (status, value)
    }

    pub async fn get(&self, path: &str, token: Option<&str>) -> (StatusCode, Value) {
        self.send(Method::GET, path, None, token).await
    }

    pub async fn post_json(
        &self,
        path: &str,
        json: Value,
        token: Option<&str>,
    ) -> (StatusCode, Value) {
        self.send(Method::POST, path, Some(json), token).await
    }

    pub async fn put(&self, path: &str, json: Value, token: Option<&str>) -> (StatusCode, Value) {
        self.send(Method::PUT, path, Some(json), token).await
    }

    pub async fn delete(
        &self,
        path: &str,
        json: Option<Value>,
        token: Option<&str>,
    ) -> (StatusCode, Value) {
        self.send(Method::DELETE, path, json, token).await
    }
}

fn b64(bytes: &[u8]) -> String {
    use base64::{engine::general_purpose::STANDARD, Engine};
    STANDARD.encode(bytes)
}

/// Registers and verifies an account (auth_key = [2u8; 32], slot1 = [3u8; 60]).
pub async fn register_verified(app: &TestApp, email: &str) {
    use serde_json::json;
    let (status, _) = app
        .post_json(
            "/auth/register",
            json!({
                "email": email,
                "kdf": {"m": 65536, "t": 3, "p": 1},
                "salt_pw": b64(&[1u8; 16]),
                "auth_key": b64(&[2u8; 32]),
                "slot1": b64(&[3u8; 60]),
                "slot2": b64(&[4u8; 60]),
                "recovery_auth": b64(&[5u8; 32]),
            }),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::ACCEPTED);
    let n = app.mailer.sent.lock().unwrap().len() + 1;
    app.wait_mails(n).await;
    let body = app.mailer.sent.lock().unwrap().last().unwrap().2.clone();
    let chars: Vec<char> = body.chars().collect();
    let code: String = chars
        .windows(6)
        .find_map(|w| w.iter().all(|c| c.is_ascii_digit()).then(|| w.iter().collect()))
        .expect("no code in mail");
    let (status, _) = app
        .post_json(
            "/auth/verify",
            json!({"email": email, "code": code, "auth_key": b64(&[2u8; 32])}),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
}

/// Logs in with the default key; returns (token, user_id, epoch). Each login bumps the epoch.
pub async fn login_session(app: &TestApp, email: &str) -> (String, String, i64) {
    use serde_json::json;
    let (status, body) = app
        .post_json(
            "/auth/login",
            json!({"email": email, "auth_key": b64(&[2u8; 32])}),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    (
        body["token"].as_str().unwrap().to_string(),
        body["user_id"].as_str().unwrap().to_string(),
        body["epoch"].as_i64().unwrap(),
    )
}
