mod common;

use axum::http::StatusCode;
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};

const EMAIL: &str = "a@example.com";

fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

fn kdf() -> Value {
    json!({"m": 65536, "t": 3, "p": 1})
}

async fn login_with(app: &common::TestApp, key: &[u8]) -> StatusCode {
    app.post_json("/auth/login", json!({"email": EMAIL, "auth_key": b64(key)}), None)
        .await
        .0
}

fn last_code(app: &common::TestApp) -> String {
    let body = app.mailer.sent.lock().unwrap().last().unwrap().2.clone();
    let chars: Vec<char> = body.chars().collect();
    chars
        .windows(6)
        .find_map(|w| w.iter().all(|c| c.is_ascii_digit()).then(|| w.iter().collect()))
        .expect("no code in mail")
}

/// Runs request + verify; returns (reset_token, slot2).
async fn reset_token(app: &common::TestApp) -> (String, String) {
    let before = app.mailer.sent.lock().unwrap().len();
    let (status, _) = app.post_json("/auth/reset/request", json!({"email": EMAIL}), None).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    app.wait_mails(before + 1).await;
    let code = last_code(app);
    let (status, body) = app
        .post_json("/auth/reset/verify", json!({"email": EMAIL, "code": code}), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    (
        body["reset_token"].as_str().unwrap().to_string(),
        body["slot2"].as_str().unwrap().to_string(),
    )
}

fn complete_body(token: &str, recovery: &[u8], new_key: &[u8]) -> Value {
    json!({
        "reset_token": token,
        "recovery_auth": b64(recovery),
        "salt_pw": b64(&[11u8; 16]),
        "kdf": kdf(),
        "auth_key": b64(new_key),
        "slot1": b64(&[12u8; 60]),
    })
}

fn put_body(epoch: i64, base: Option<i64>, ct: &[u8]) -> Value {
    json!({"epoch": epoch, "base_version": base, "nonce": b64(&[9u8; 12]), "ciphertext": b64(ct)})
}

async fn setup() -> common::TestApp {
    let app = common::spawn().await;
    common::register_verified(&app, EMAIL).await;
    app
}

#[tokio::test]
async fn change_password_swaps_credentials_and_revokes_other_sessions() {
    let app = setup().await;
    let (token_a, _, epoch) = common::login_session(&app, EMAIL).await;
    let (token_b, _, _) = common::login_session(&app, EMAIL).await;
    let epoch = epoch + 1;
    app.put("/backup", put_body(epoch, None, b"blob"), Some(&token_a)).await;
    let (_, before) = app.get("/backup", Some(&token_a)).await;

    let (status, _) = app
        .post_json(
            "/auth/password",
            json!({
                "old_auth_key": b64(&[2u8; 32]),
                "salt_pw": b64(&[21u8; 16]),
                "kdf": kdf(),
                "auth_key": b64(&[22u8; 32]),
                "slot1": b64(&[23u8; 60]),
            }),
            Some(&token_a),
        )
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    assert_eq!(login_with(&app, &[2u8; 32]).await, StatusCode::UNAUTHORIZED);
    let (status, body) = app
        .post_json(
            "/auth/login",
            json!({"email": EMAIL, "auth_key": b64(&[22u8; 32])}),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["slot1"], b64(&[23u8; 60]));
    let (_, pre) = app.post_json("/auth/prelogin", json!({"email": EMAIL}), None).await;
    assert_eq!(pre["salt_pw"], b64(&[21u8; 16]));

    // The caller keeps working, the other session is revoked.
    let (status, _) = app.get("/backup", Some(&token_a)).await;
    assert_eq!(status, StatusCode::OK);
    let (status, body) = app.get("/backup", Some(&token_b)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "unauthorized"}));
    let (_, after) = app.get("/backup", Some(&token_a)).await;
    assert_eq!(before["ciphertext"], after["ciphertext"]);
    assert_eq!(before["version"], after["version"]);
}

#[tokio::test]
async fn change_password_wrong_old_key_is_401() {
    let app = setup().await;
    let (token, _, _) = common::login_session(&app, EMAIL).await;
    let (status, body) = app
        .post_json(
            "/auth/password",
            json!({
                "old_auth_key": b64(&[99u8; 32]),
                "salt_pw": b64(&[21u8; 16]),
                "kdf": kdf(),
                "auth_key": b64(&[22u8; 32]),
                "slot1": b64(&[23u8; 60]),
            }),
            Some(&token),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "invalid_credentials"}));
    assert_eq!(login_with(&app, &[2u8; 32]).await, StatusCode::OK);
}

#[tokio::test]
async fn reset_request_unknown_and_known_identical() {
    let app = setup().await;
    let known = app.post_json("/auth/reset/request", json!({"email": EMAIL}), None).await;
    let unknown = app
        .post_json("/auth/reset/request", json!({"email": "nobody@example.com"}), None)
        .await;
    assert_eq!(known, (StatusCode::ACCEPTED, json!({})));
    assert_eq!(known, unknown);
    // register_verified sent one mail; the reset adds exactly one more, for the known address.
    app.wait_mails(2).await;
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    let sent = app.mailer.sent.lock().unwrap();
    assert_eq!(sent.len(), 2);
    assert_eq!(sent[1].0, EMAIL);
    assert_eq!(sent[1].1, "mediTracker: kod resetowania hasła");
}

#[tokio::test]
async fn reset_request_for_unverified_account_sends_no_mail() {
    let app = common::spawn().await;
    let (status, _) = app
        .post_json(
            "/auth/register",
            json!({
                "email": EMAIL, "kdf": kdf(), "salt_pw": b64(&[1u8; 16]),
                "auth_key": b64(&[2u8; 32]), "slot1": b64(&[3u8; 60]),
                "slot2": b64(&[4u8; 60]), "recovery_auth": b64(&[5u8; 32]),
            }),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::ACCEPTED);
    app.wait_mails(1).await;
    let code = last_code(&app);
    let (status, _) = app.post_json("/auth/reset/request", json!({"email": EMAIL}), None).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    assert_eq!(app.mailer.sent.lock().unwrap().len(), 1);
    // The pending registration's verify code must not work as a reset code.
    let (status, body) = app
        .post_json("/auth/reset/verify", json!({"email": EMAIL, "code": code}), None)
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body, json!({"error": "invalid_code"}));
}

#[tokio::test]
async fn reset_verify_returns_token_and_slot2() {
    let app = setup().await;
    let (token, slot2) = reset_token(&app).await;
    assert!(!token.is_empty());
    assert_eq!(slot2, b64(&[4u8; 60]));
}

#[tokio::test]
async fn reset_verify_wrong_unknown_and_exhausted_codes_are_identical_400() {
    let app = setup().await;
    app.post_json("/auth/reset/request", json!({"email": EMAIL}), None).await;
    app.wait_mails(2).await;
    let code = last_code(&app);
    let wrong = if code == "000000" { "111111" } else { "000000" };
    let expected = (StatusCode::BAD_REQUEST, json!({"error": "invalid_code"}));
    let unknown = app
        .post_json("/auth/reset/verify", json!({"email": "x@example.com", "code": wrong}), None)
        .await;
    assert_eq!(unknown, expected);
    for _ in 0..5 {
        let r = app
            .post_json("/auth/reset/verify", json!({"email": EMAIL, "code": wrong}), None)
            .await;
        assert_eq!(r, expected);
    }
    // Five attempts are used up: even the right code is rejected now.
    let r = app
        .post_json("/auth/reset/verify", json!({"email": EMAIL, "code": code}), None)
        .await;
    assert_eq!(r, expected);
}

#[tokio::test]
async fn reset_complete_requires_recovery_auth() {
    let app = setup().await;
    let (token, _, epoch) = common::login_session(&app, EMAIL).await;
    app.put("/backup", put_body(epoch, None, b"blob"), Some(&token)).await;
    let (_, before) = app.get("/backup", Some(&token)).await;

    let (reset, _) = reset_token(&app).await;
    let (status, body) = app
        .post_json("/auth/reset/complete", complete_body(&reset, &[66u8; 32], &[22u8; 32]), None)
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "invalid_recovery"}));
    assert_eq!(login_with(&app, &[2u8; 32]).await, StatusCode::OK);
    assert_eq!(login_with(&app, &[22u8; 32]).await, StatusCode::UNAUTHORIZED);

    // The token survives a wrong recovery proof.
    let (status, _) = app
        .post_json("/auth/reset/complete", complete_body(&reset, &[5u8; 32], &[22u8; 32]), None)
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(login_with(&app, &[2u8; 32]).await, StatusCode::UNAUTHORIZED);
    let (status, login) = app
        .post_json(
            "/auth/login",
            json!({"email": EMAIL, "auth_key": b64(&[22u8; 32])}),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(login["slot1"], b64(&[12u8; 60]));

    // Old sessions are gone, the backup is unchanged, the token is single-use.
    let (status, _) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let new_token = login["token"].as_str().unwrap();
    let (_, after) = app.get("/backup", Some(new_token)).await;
    assert_eq!(before["ciphertext"], after["ciphertext"]);
    assert_eq!(before["nonce"], after["nonce"]);
    let (status, body) = app
        .post_json("/auth/reset/complete", complete_body(&reset, &[5u8; 32], &[22u8; 32]), None)
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "invalid_token"}));
}

#[tokio::test]
async fn reset_token_expires_after_15_minutes() {
    let app = setup().await;
    let (reset, _) = reset_token(&app).await;
    app.set_time(1_700_000_000 + 15 * 60 + 1);
    let (status, body) = app
        .post_json("/auth/reset/complete", complete_body(&reset, &[5u8; 32], &[22u8; 32]), None)
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "invalid_token"}));
    let (status, body) = app
        .post_json("/auth/reset/wipe", wipe_body(&reset), None)
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "invalid_token"}));
}

fn wipe_body(token: &str) -> Value {
    json!({
        "reset_token": token,
        "salt_pw": b64(&[31u8; 16]),
        "kdf": kdf(),
        "auth_key": b64(&[32u8; 32]),
        "slot1": b64(&[33u8; 60]),
        "slot2": b64(&[34u8; 60]),
        "recovery_auth": b64(&[35u8; 32]),
    })
}

#[tokio::test]
async fn reset_wipe_replaces_slots_and_deletes_blob_but_keeps_version_counter() {
    let app = setup().await;
    let (token, _, epoch) = common::login_session(&app, EMAIL).await;
    let mut base = None;
    for i in 0..3u8 {
        let (status, body) = app.put("/backup", put_body(epoch, base, &[i; 8]), Some(&token)).await;
        assert_eq!(status, StatusCode::OK);
        base = body["version"].as_i64();
    }
    assert_eq!(base, Some(3));

    let (reset, _) = reset_token(&app).await;
    let (status, _) = app.post_json("/auth/reset/wipe", wipe_body(&reset), None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    // Old credentials and sessions are gone; new ones work.
    assert_eq!(login_with(&app, &[2u8; 32]).await, StatusCode::UNAUTHORIZED);
    let (status, _) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, login) = app
        .post_json(
            "/auth/login",
            json!({"email": EMAIL, "auth_key": b64(&[32u8; 32])}),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(login["slot1"], b64(&[33u8; 60]));
    let new_token = login["token"].as_str().unwrap();
    let new_epoch = login["epoch"].as_i64().unwrap();

    let (_, got) = app.get("/backup", Some(new_token)).await;
    assert_eq!(got["version"], 3);
    assert!(got["ciphertext"].is_null());
    assert!(got["nonce"].is_null());
    assert!(got["updated_at"].is_null());

    let (status, body) = app
        .put("/backup", put_body(new_epoch, Some(3), b"fresh"), Some(new_token))
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"version": 4}));

    // The new recovery proof and slot2 are in force for the next reset.
    let (reset2, slot2) = reset_token(&app).await;
    assert_eq!(slot2, b64(&[34u8; 60]));
    let (status, _) = app
        .post_json("/auth/reset/complete", complete_body(&reset2, &[35u8; 32], &[36u8; 32]), None)
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn reset_inputs_are_validated() {
    let app = setup().await;
    let (reset, _) = reset_token(&app).await;
    let mut body = complete_body(&reset, &[5u8; 32], &[22u8; 32]);
    body["salt_pw"] = json!(b64(&[1u8; 8]));
    let (status, err) = app.post_json("/auth/reset/complete", body, None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(err, json!({"error": "invalid_input"}));
    let mut body = wipe_body(&reset);
    body["kdf"] = json!({"m": 0, "t": 3, "p": 1});
    let (status, _) = app.post_json("/auth/reset/wipe", body, None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}
