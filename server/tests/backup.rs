mod common;

use axum::http::StatusCode;
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};

fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

fn put_body(epoch: i64, base: Option<i64>, ct: &[u8]) -> Value {
    json!({"epoch": epoch, "base_version": base, "nonce": b64(&[9u8; 12]), "ciphertext": b64(ct)})
}

async fn setup() -> (common::TestApp, String, String, i64) {
    let app = common::spawn().await;
    common::register_verified(&app, "a@example.com").await;
    let (token, user_id, epoch) = common::login_session(&app, "a@example.com").await;
    (app, token, user_id, epoch)
}

#[tokio::test]
async fn first_put_with_null_base_creates_version_1() {
    let (app, token, _, epoch) = setup().await;
    let (status, body) = app.put("/backup", put_body(epoch, None, b"blob"), Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"version": 1}));
}

#[tokio::test]
async fn put_with_stale_version_is_409() {
    let (app, token, _, epoch) = setup().await;
    app.put("/backup", put_body(epoch, None, b"one"), Some(&token)).await;
    let (status, body) = app.put("/backup", put_body(epoch, None, b"two"), Some(&token)).await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body, json!({"error": "conflict", "epoch": epoch, "version": 1}));
    let (status, body) = app.put("/backup", put_body(epoch, Some(1), b"two"), Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"version": 2}));
}

#[tokio::test]
async fn put_with_old_epoch_is_409_even_if_version_matches() {
    let (app, token_a, _, epoch_a) = setup().await;
    app.put("/backup", put_body(epoch_a, None, b"one"), Some(&token_a)).await;
    let (_token_b, _, epoch_b) = common::login_session(&app, "a@example.com").await;
    assert_eq!(epoch_b, epoch_a + 1);
    let (status, body) = app
        .put("/backup", put_body(epoch_a, Some(1), b"stale"), Some(&token_a))
        .await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body, json!({"error": "conflict", "epoch": epoch_b, "version": 1}));
}

#[tokio::test]
async fn takeover_bumps_epoch() {
    let (app, token, _, epoch) = setup().await;
    let (status, body) = app.post_json("/backup/takeover", json!({}), Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"epoch": epoch + 1, "version": null}));
    app.put("/backup", put_body(epoch + 1, None, b"one"), Some(&token)).await;
    let (_, body) = app.post_json("/backup/takeover", json!({}), Some(&token)).await;
    assert_eq!(body, json!({"epoch": epoch + 2, "version": 1}));
    // The previous epoch can no longer write.
    let (status, _) = app
        .put("/backup", put_body(epoch + 1, Some(1), b"x"), Some(&token))
        .await;
    assert_eq!(status, StatusCode::CONFLICT);
}

#[tokio::test]
async fn get_returns_blob_roundtrip() {
    let (app, token, _, epoch) = setup().await;
    let (status, body) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body,
        json!({"version": null, "epoch": epoch, "nonce": null, "ciphertext": null, "updated_at": null})
    );
    let blob: Vec<u8> = (0..=255u8).cycle().take(1000).collect();
    app.set_time(1_700_000_123);
    app.put("/backup", put_body(epoch, None, &blob), Some(&token)).await;
    let (status, body) = app.get("/backup", Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["version"], 1);
    assert_eq!(body["epoch"], epoch);
    assert_eq!(body["updated_at"], 1_700_000_123);
    assert_eq!(STANDARD.decode(body["nonce"].as_str().unwrap()).unwrap(), vec![9u8; 12]);
    assert_eq!(STANDARD.decode(body["ciphertext"].as_str().unwrap()).unwrap(), blob);
}

#[tokio::test]
async fn get_with_wiped_blob_nulls_fields_but_version_still_gates_writes() {
    let (app, token, user_id, epoch) = setup().await;
    app.put("/backup", put_body(epoch, None, b"one"), Some(&token)).await;
    sqlx::query(
        "UPDATE backups SET nonce = NULL, ciphertext = NULL, updated_at = NULL WHERE user_id = ?",
    )
    .bind(&user_id)
    .execute(&app.db)
    .await
    .unwrap();
    let (_, body) = app.get("/backup", Some(&token)).await;
    assert_eq!(
        body,
        json!({"version": null, "epoch": epoch, "nonce": null, "ciphertext": null, "updated_at": null})
    );
    let (status, body) = app.put("/backup", put_body(epoch, None, b"x"), Some(&token)).await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["version"], 1);
    let (_, body) = app.put("/backup", put_body(epoch, Some(1), b"x"), Some(&token)).await;
    assert_eq!(body, json!({"version": 2}));
}

#[tokio::test]
async fn blob_over_5mb_is_413() {
    let (app, token, _, epoch) = setup().await;
    // Decoded ciphertext just over 5 MiB: within the body limit, rejected by the size check.
    let big = vec![0u8; 5 * 1024 * 1024 + 1];
    let (status, body) = app.put("/backup", put_body(epoch, None, &big), Some(&token)).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(body, json!({"error": "too_large"}));
    // Exactly 5 MiB is accepted.
    let ok = vec![0u8; 5 * 1024 * 1024];
    let (status, _) = app.put("/backup", put_body(epoch, None, &ok), Some(&token)).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn body_over_request_limit_is_json_413() {
    let (app, token, _, epoch) = setup().await;
    let huge = vec![0u8; 8 * 1024 * 1024];
    let (status, body) = app.put("/backup", put_body(epoch, None, &huge), Some(&token)).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(body, json!({"error": "too_large"}));
}

#[tokio::test]
async fn backup_and_account_routes_require_auth() {
    let app = common::spawn().await;
    let (s, _) = app.put("/backup", put_body(0, None, b"x"), None).await;
    assert_eq!(s, StatusCode::UNAUTHORIZED);
    let (s, _) = app.post_json("/backup/takeover", json!({}), None).await;
    assert_eq!(s, StatusCode::UNAUTHORIZED);
    let (s, _) = app
        .delete("/account", Some(json!({"auth_key": b64(&[2u8; 32])})), None)
        .await;
    assert_eq!(s, StatusCode::UNAUTHORIZED);
}

async fn count(app: &common::TestApp, table: &str) -> i64 {
    sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
        .fetch_one(&app.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn delete_account_requires_auth_key_and_removes_everything() {
    let (app, token, user_id, epoch) = setup().await;
    app.put("/backup", put_body(epoch, None, b"one"), Some(&token)).await;
    sqlx::query("INSERT INTO reset_tokens (token_hash, user_id, expires_at) VALUES (x'01', ?, 2000000000)")
        .bind(&user_id)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO email_codes (user_id, purpose, code_hash, expires_at) VALUES (?, 'reset', x'02', 2000000000)",
    )
    .bind(&user_id)
    .execute(&app.db)
    .await
    .unwrap();

    let (status, body) = app
        .delete("/account", Some(json!({"auth_key": b64(&[9u8; 32])})), Some(&token))
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body, json!({"error": "invalid_credentials"}));
    assert_eq!(count(&app, "users").await, 1);

    let (status, _) = app
        .delete("/account", Some(json!({"auth_key": b64(&[2u8; 32])})), Some(&token))
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    let (status, _) = app
        .post_json(
            "/auth/login",
            json!({"email": "a@example.com", "auth_key": b64(&[2u8; 32])}),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    for table in ["users", "sessions", "backups", "email_codes", "reset_tokens"] {
        assert_eq!(count(&app, table).await, 0, "{table}");
    }
}
