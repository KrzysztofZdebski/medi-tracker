mod common;

use std::{path::PathBuf, sync::Arc};

use axum::http::StatusCode;
use base64::{engine::general_purpose::STANDARD, Engine};
use meditracker_server::{
    config::{Config, EmailMode},
    limits::Limits,
    mailer::MemoryMailer,
    routes::register::purge_unverified,
    AppState,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

const T0: i64 = 1_700_000_000;

fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

fn reg_body(email: &str, auth_key: &[u8]) -> Value {
    json!({
        "email": email,
        "kdf": {"m": 65536, "t": 3, "p": 1},
        "salt_pw": b64(&[1u8; 16]),
        "auth_key": b64(auth_key),
        "slot1": b64(&[3u8; 60]),
        "slot2": b64(&[4u8; 60]),
        "recovery_auth": b64(&[5u8; 32]),
    })
}

fn find_code(body: &str) -> Option<String> {
    let chars: Vec<char> = body.chars().collect();
    chars.windows(6).find_map(|w| {
        w.iter()
            .all(|c| c.is_ascii_digit())
            .then(|| w.iter().collect::<String>())
    })
}

/// Returns the 6-digit code of the most recent mail, panicking if there is none.
fn last_code(app: &common::TestApp) -> String {
    let sent = app.mailer.sent.lock().unwrap();
    find_code(&sent.last().expect("no mail sent").2).expect("no code in mail")
}

async fn register(app: &common::TestApp, email: &str, auth_key: &[u8]) -> (StatusCode, Value) {
    app.post_json("/auth/register", reg_body(email, auth_key), None)
        .await
}

/// Verifies with the default registration key ([2u8; 32]).
async fn verify(app: &common::TestApp, email: &str, code: &str) -> (StatusCode, Value) {
    verify_with_key(app, email, code, &[2u8; 32]).await
}

async fn verify_with_key(
    app: &common::TestApp,
    email: &str,
    code: &str,
    auth_key: &[u8],
) -> (StatusCode, Value) {
    app.post_json(
        "/auth/verify",
        json!({"email": email, "code": code, "auth_key": b64(auth_key)}),
        None,
    )
    .await
}

async fn auth_hash(app: &common::TestApp, email: &str) -> Vec<u8> {
    sqlx::query_scalar("SELECT auth_hash FROM users WHERE email=?")
        .bind(email)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn count(app: &common::TestApp, table: &str) -> i64 {
    sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
        .fetch_one(&app.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn register_then_verify_returns_session() {
    let app = common::spawn().await;
    let (status, body) = register(&app, "a@example.com", &[2u8; 32]).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert_eq!(body, json!({}));
    app.wait_mails(1).await;
    assert_eq!(app.mailer.sent.lock().unwrap().len(), 1);
    let code = last_code(&app);
    assert_eq!(code.len(), 6);

    let (status, body) = verify(&app, "a@example.com", &code).await;
    assert_eq!(status, StatusCode::OK);
    assert!(!body["token"].as_str().unwrap().is_empty());
    assert!(!body["user_id"].as_str().unwrap().is_empty());
    assert_eq!(body["epoch"], 0);
    assert_eq!(count(&app, "sessions").await, 1);
    assert_eq!(count(&app, "email_codes").await, 0);
}

#[tokio::test]
async fn email_is_normalized() {
    let app = common::spawn().await;
    let (status, _) = register(&app, " Anna@Example.COM", &[2u8; 32]).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    app.wait_mails(1).await;
    let code = last_code(&app);
    let (status, _) = verify(&app, "anna@example.com", &code).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn wrong_code_five_times_invalidates() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    app.wait_mails(1).await;
    let code = last_code(&app);
    let wrong = if code == "000000" { "111111" } else { "000000" };
    for _ in 0..5 {
        let (status, body) = verify(&app, "a@example.com", wrong).await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(body["error"], "invalid_code");
    }
    let (status, body) = verify(&app, "a@example.com", &code).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "invalid_code");
}

#[tokio::test]
async fn code_expires_after_15_minutes() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    app.wait_mails(1).await;
    let code = last_code(&app);
    app.set_time(T0 + 901);
    let (status, body) = verify(&app, "a@example.com", &code).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "invalid_code");
}

#[tokio::test]
async fn register_existing_verified_email_looks_identical() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    app.wait_mails(1).await;
    let code = last_code(&app);
    let (status, _) = verify(&app, "a@example.com", &code).await;
    assert_eq!(status, StatusCode::OK);
    let before = auth_hash(&app, "a@example.com").await;

    let (status, body) = register(&app, "a@example.com", &[9u8; 32]).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert_eq!(body, json!({}));
    app.wait_mails(2).await;
    {
        let sent = app.mailer.sent.lock().unwrap();
        assert_eq!(sent.len(), 2);
        assert_eq!(sent[1].0, "a@example.com");
        assert_eq!(sent[1].1, "mediTracker: próba rejestracji");
        assert!(find_code(&sent[1].2).is_none(), "notice must not contain a code");
    }
    assert_eq!(auth_hash(&app, "a@example.com").await, before);
    assert_eq!(count(&app, "email_codes").await, 0);
}

#[tokio::test]
async fn register_pending_email_overwrites() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    app.wait_mails(1).await;
    let first = last_code(&app);
    register(&app, "a@example.com", &[9u8; 32]).await;
    app.wait_mails(2).await;
    let second = last_code(&app);
    if first != second {
        let (status, _) = verify_with_key(&app, "a@example.com", &first, &[9u8; 32]).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "old code must be replaced");
    }
    let (status, _) = verify_with_key(&app, "a@example.com", &second, &[9u8; 32]).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        auth_hash(&app, "a@example.com").await,
        Sha256::digest([9u8; 32]).to_vec()
    );
    assert_eq!(count(&app, "users").await, 1);
}

#[tokio::test]
async fn unverified_purged_after_24h() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    let state = AppState {
        db: app.db.clone(),
        config: Arc::new(Config {
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
        }),
        mailer: Arc::new(MemoryMailer::default()),
        limits: Arc::new(Limits::new()),
        clock: meditracker_server::system_clock(),
    };
    purge_unverified(&state, T0 + 86_400 - 1).await.unwrap();
    assert_eq!(count(&app, "users").await, 1);
    purge_unverified(&state, T0 + 86_401).await.unwrap();
    assert_eq!(count(&app, "users").await, 0);
    assert_eq!(count(&app, "email_codes").await, 0);
}

#[tokio::test]
async fn invalid_input_rejected() {
    let app = common::spawn().await;
    for email in ["", "nope", "a@", "@b.com", "a b@c.com"] {
        let (status, body) = register(&app, email, &[2u8; 32]).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "email {email:?}");
        assert_eq!(body["error"], "invalid_input");
    }
    let mut missing = reg_body("a@example.com", &[2u8; 32]);
    missing.as_object_mut().unwrap().remove("slot2");
    let (status, body) = app.post_json("/auth/register", missing, None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "invalid_input");

    let mut bad_b64 = reg_body("a@example.com", &[2u8; 32]);
    bad_b64["auth_key"] = json!("!!!not base64!!!");
    let (status, body) = app.post_json("/auth/register", bad_b64, None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "invalid_input");
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    assert!(app.mailer.sent.lock().unwrap().is_empty());
}

#[tokio::test]
async fn resend_rate_limited() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    for _ in 0..3 {
        let (status, body) = app
            .post_json("/auth/verify/resend", json!({"email": "a@example.com"}), None)
            .await;
        assert_eq!(status, StatusCode::ACCEPTED);
        assert_eq!(body, json!({}));
    }
    let (status, body) = app
        .post_json("/auth/verify/resend", json!({"email": "a@example.com"}), None)
        .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(body["error"], "rate_limited");
}

#[tokio::test]
async fn resend_unknown_email_sends_nothing() {
    let app = common::spawn().await;
    let (status, body) = app
        .post_json("/auth/verify/resend", json!({"email": "ghost@example.com"}), None)
        .await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert_eq!(body, json!({}));
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    assert!(app.mailer.sent.lock().unwrap().is_empty());
}

#[tokio::test]
async fn mail_is_sent_off_the_request_path() {
    let app = common::spawn_with_mail_delay(200).await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    app.wait_mails(1).await;
    let started = std::time::Instant::now();
    let (status, _) = app
        .post_json("/auth/verify/resend", json!({"email": "a@example.com"}), None)
        .await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert!(
        started.elapsed() < std::time::Duration::from_millis(50),
        "resend waited for the mailer: {:?}",
        started.elapsed()
    );
    app.wait_mails(2).await;
}

#[tokio::test]
async fn concurrent_wrong_verifies_respect_attempt_cap() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    app.wait_mails(1).await;
    let code = last_code(&app);
    let wrong = if code == "000000" { "111111" } else { "000000" };
    let results = futures_util::future::join_all(
        (0..10).map(|_| verify(&app, "a@example.com", wrong)),
    )
    .await;
    assert!(results.iter().all(|(s, _)| *s == StatusCode::BAD_REQUEST));
    let attempts: i64 = sqlx::query_scalar("SELECT attempts FROM email_codes")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(attempts, 5);
    // Let the rate-limit window pass while the code is still unexpired.
    app.set_time(T0 + 900);
    let (status, body) = verify(&app, "a@example.com", &code).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "invalid_code");
}

#[tokio::test]
async fn verify_with_other_registrants_auth_key_fails() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[0xAA; 32]).await; // victim, key A
    register(&app, "a@example.com", &[0xBB; 32]).await; // attacker overwrites, key B
    app.wait_mails(2).await;
    let code = last_code(&app);
    let (status, body) = verify_with_key(&app, "a@example.com", &code, &[0xAA; 32]).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "invalid_code");
    let (status, _) = verify_with_key(&app, "a@example.com", &code, &[0xBB; 32]).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn verify_requires_auth_key() {
    let app = common::spawn().await;
    register(&app, "a@example.com", &[2u8; 32]).await;
    app.wait_mails(1).await;
    let code = last_code(&app);
    let (status, body) = app
        .post_json("/auth/verify", json!({"email": "a@example.com", "code": code}), None)
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"], "invalid_input");
}
