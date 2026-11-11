mod common;

use axum::http::StatusCode;
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};

const T0: i64 = 1_700_000_000;
const DAY: i64 = 24 * 3600;

fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

fn reg_body(email: &str) -> Value {
    json!({
        "email": email,
        "kdf": {"m": 65536, "t": 3, "p": 1},
        "salt_pw": b64(&[1u8; 16]),
        "auth_key": b64(&[2u8; 32]),
        "slot1": b64(&[3u8; 60]),
        "slot2": b64(&[4u8; 60]),
        "recovery_auth": b64(&[5u8; 32]),
    })
}

fn code_of(body: &str) -> String {
    let chars: Vec<char> = body.chars().collect();
    chars
        .windows(6)
        .find_map(|w| w.iter().all(|c| c.is_ascii_digit()).then(|| w.iter().collect()))
        .expect("no code in mail")
}

fn last_code(app: &common::TestApp) -> String {
    code_of(&app.mailer.sent.lock().unwrap().last().expect("no mail").2)
}

/// Registers without verifying; returns once the verification mail is recorded.
async fn register_unverified(app: &common::TestApp, email: &str) {
    let (status, _) = app.post_json("/auth/register", reg_body(email), None).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    app.wait_mails(1).await;
}

async fn verify(app: &common::TestApp, email: &str) -> (StatusCode, Value) {
    let code = last_code(app);
    app.post_json(
        "/auth/verify",
        json!({"email": email, "code": code, "auth_key": b64(&[2u8; 32])}),
        None,
    )
    .await
}

/// Registers and verifies an account (auth_key = [2u8; 32]).
async fn account(app: &common::TestApp, email: &str) {
    register_unverified(app, email).await;
    let (status, _) = verify(app, email).await;
    assert_eq!(status, StatusCode::OK);
}

async fn login_with(app: &common::TestApp, email: &str, key: &[u8]) -> (StatusCode, Value) {
    app.post_json(
        "/auth/login",
        json!({"email": email, "auth_key": b64(key)}),
        None,
    )
    .await
}

async fn login(app: &common::TestApp, email: &str) -> (StatusCode, Value) {
    login_with(app, email, &[2u8; 32]).await
}

async fn prelogin(app: &common::TestApp, email: &str) -> (StatusCode, Value) {
    app.post_json("/auth/prelogin", json!({ "email": email }), None)
        .await
}

#[tokio::test]
async fn prelogin_unknown_email_returns_stable_fake_salt() {
    let app = common::spawn().await;
    let (s1, a) = prelogin(&app, "nobody@example.com").await;
    let (s2, b) = prelogin(&app, "nobody@example.com").await;
    let (_, c) = prelogin(&app, "other@example.com").await;
    assert_eq!(s1, StatusCode::OK);
    assert_eq!(s2, StatusCode::OK);
    assert_eq!(a, b);
    assert_eq!(STANDARD.decode(a["salt_pw"].as_str().unwrap()).unwrap().len(), 16);
    assert_eq!(a["kdf"], json!({"m": 65536, "t": 3, "p": 1}));
    assert_ne!(a["salt_pw"], c["salt_pw"]);
}

#[tokio::test]
async fn prelogin_known_email_returns_registered_salt() {
    let app = common::spawn().await;
    account(&app, "a@example.com").await;
    let (status, body) = prelogin(&app, " A@Example.com").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["salt_pw"], b64(&[1u8; 16]));
    assert_eq!(body["kdf"], json!({"m": 65536, "t": 3, "p": 1}));
}

#[tokio::test]
async fn login_returns_slot1_and_bumps_epoch() {
    let app = common::spawn().await;
    account(&app, "a@example.com").await;
    let (status, body) = login(&app, "a@example.com").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["epoch"], 1);
    assert_eq!(body["slot1"], b64(&[3u8; 60]));
    assert!(body["backup_version"].is_null());
    assert!(!body["token"].as_str().unwrap().is_empty());
    assert!(!body["user_id"].as_str().unwrap().is_empty());
    let (_, body) = login(&app, "a@example.com").await;
    assert_eq!(body["epoch"], 2);
}

#[tokio::test]
async fn login_reports_backup_version() {
    let app = common::spawn().await;
    account(&app, "a@example.com").await;
    let (_, body) = login(&app, "a@example.com").await;
    let user_id = body["user_id"].as_str().unwrap().to_string();
    sqlx::query("INSERT INTO backups (user_id, version) VALUES (?, 7)")
        .bind(&user_id)
        .execute(&app.db)
        .await
        .unwrap();
    let (_, body) = login(&app, "a@example.com").await;
    assert_eq!(body["backup_version"], 7);
}

#[tokio::test]
async fn login_wrong_key_and_unknown_email_identical() {
    let app = common::spawn().await;
    account(&app, "a@example.com").await;
    let wrong = login_with(&app, "a@example.com", &[9u8; 32]).await;
    let unknown = login(&app, "nobody@example.com").await;
    assert_eq!(wrong.0, StatusCode::UNAUTHORIZED);
    assert_eq!(wrong.1, json!({"error": "invalid_credentials"}));
    assert_eq!(wrong, unknown);
}

#[tokio::test]
async fn login_unverified_correct_password_resends_code() {
    let app = common::spawn().await;
    register_unverified(&app, "a@example.com").await;
    let (status, body) = login(&app, "a@example.com").await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body, json!({"error": "unverified"}));
    app.wait_mails(2).await;
    let (status, _) = verify(&app, "a@example.com").await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn login_unverified_wrong_password_is_invalid_credentials() {
    let app = common::spawn().await;
    register_unverified(&app, "b@example.com").await;
    let (status, body) = login_with(&app, "b@example.com", &[9u8; 32]).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"], "invalid_credentials");
    // Give a wrongly-sent detached mail a chance to show up before asserting there is none.
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    assert_eq!(app.mailer.sent.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn logout_revokes_token() {
    let app = common::spawn().await;
    account(&app, "a@example.com").await;
    let (_, body) = login(&app, "a@example.com").await;
    let token = body["token"].as_str().unwrap().to_string();
    let (status, _) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = app.post_json("/auth/logout", json!({}), Some(&token)).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, body) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "unauthorized"}));
}

#[tokio::test]
async fn missing_or_garbage_token_is_unauthorized() {
    let app = common::spawn().await;
    let (status, body) = app.get("/backup", None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"], "unauthorized");
    let (status, _) = app.get("/backup", Some("garbage")).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn session_expires_after_90_days_idle() {
    let app = common::spawn().await;
    account(&app, "a@example.com").await;
    app.set_time(T0);
    let (_, body) = login(&app, "a@example.com").await;
    let token = body["token"].as_str().unwrap().to_string();
    app.set_time(T0 + 90 * DAY + 1);
    let (status, _) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn session_use_extends_expiry() {
    let app = common::spawn().await;
    account(&app, "a@example.com").await;
    app.set_time(T0);
    let (_, body) = login(&app, "a@example.com").await;
    let token = body["token"].as_str().unwrap().to_string();

    app.set_time(T0 + 89 * DAY);
    let (status, _) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    // The use at +89d slid the expiry to +179d, so +90d+1 is still valid.
    app.set_time(T0 + 90 * DAY + 1);
    let (status, _) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    // Idle for more than 90 days from the last use.
    app.set_time(T0 + 180 * DAY + 2);
    let (status, _) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn login_rate_limited() {
    let app = common::spawn().await;
    for _ in 0..10 {
        let (status, _) = login(&app, "nobody@example.com").await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }
    let (status, _) = login(&app, " Nobody@example.com").await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn prelogin_rate_limited() {
    let app = common::spawn().await;
    for _ in 0..30 {
        let (status, _) = prelogin(&app, "x@example.com").await;
        assert_eq!(status, StatusCode::OK);
    }
    let (status, _) = prelogin(&app, "x@example.com").await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
}
