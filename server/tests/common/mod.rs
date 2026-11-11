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
    mailer::MemoryMailer,
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

pub async fn spawn() -> TestApp {
    let db = init_db("sqlite::memory:").await.expect("db init");
    let mailer = Arc::new(MemoryMailer::default());
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
        mailer: mailer.clone(),
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
