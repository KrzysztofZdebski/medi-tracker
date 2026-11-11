use axum::{body::Bytes, extract::State, http::StatusCode, routing::post, Json, Router};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::Row;

use super::{
    claim_code_attempt, decode_b64, issue_code, normalize_email, parse_json, Kdf, NewCredentials,
};
use crate::{
    error::ApiError,
    secrets::{ct_eq, hash_secret, hmac_code, new_token},
    AppState,
};

const PURPOSE: &str = "reset";
const RESET_TOKEN_TTL_SECS: i64 = 15 * 60;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/auth/reset/request", post(request))
        .route("/auth/reset/verify", post(verify))
        .route("/auth/reset/complete", post(complete))
        .route("/auth/reset/wipe", post(wipe))
}

#[derive(Deserialize)]
struct RequestReq {
    email: String,
}

#[derive(Deserialize)]
struct VerifyReq {
    email: String,
    code: String,
}

#[derive(Deserialize)]
struct CompleteReq {
    reset_token: String,
    recovery_auth: String,
    salt_pw: String,
    kdf: Kdf,
    auth_key: String,
    slot1: String,
}

#[derive(Deserialize)]
struct WipeReq {
    reset_token: String,
    salt_pw: String,
    kdf: Kdf,
    auth_key: String,
    slot1: String,
    slot2: String,
    recovery_auth: String,
}

/// Always 202 `{}`; a code is mailed only to verified accounts.
async fn request(
    State(state): State<AppState>,
    body: Bytes,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let req: RequestReq = parse_json(&body)?;
    let email = normalize_email(&req.email);
    let now = (state.clock)();
    state.limits.check(&format!("reset:{email}"), 3, 600, now)?;

    let user: Option<String> =
        sqlx::query_scalar("SELECT id FROM users WHERE email = ? AND email_verified_at IS NOT NULL")
            .bind(&email)
            .fetch_optional(&state.db)
            .await?;
    if let Some(user_id) = user {
        issue_code(&state, &user_id, PURPOSE, &email).await?;
    }
    Ok((StatusCode::ACCEPTED, Json(json!({}))))
}

/// Exchanges the emailed code for a 15-minute reset token plus slot 2.
async fn verify(State(state): State<AppState>, body: Bytes) -> Result<Json<Value>, ApiError> {
    let req: VerifyReq = parse_json(&body)?;
    let email = normalize_email(&req.email);
    let now = (state.clock)();
    state.limits.check(&format!("resetverify:{email}"), 10, 900, now)?;

    let user = sqlx::query(
        "SELECT id, slot2 FROM users WHERE email = ? AND email_verified_at IS NOT NULL",
    )
    .bind(&email)
    .fetch_optional(&state.db)
    .await?
    .ok_or(ApiError::InvalidCode)?;
    let user_id: String = user.get("id");
    let slot2: Option<Vec<u8>> = user.get("slot2");

    let code_hash = claim_code_attempt(&state, &user_id, PURPOSE, now).await?;
    if !ct_eq(&hmac_code(&state.config.server_secret, req.code.trim()), &code_hash) {
        return Err(ApiError::InvalidCode);
    }

    let token = new_token();
    let mut tx = state.db.begin().await?;
    sqlx::query("DELETE FROM email_codes WHERE user_id = ? AND purpose = ?")
        .bind(&user_id)
        .bind(PURPOSE)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM reset_tokens WHERE user_id = ?")
        .bind(&user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO reset_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
        .bind(hash_secret(token.as_bytes()).to_vec())
        .bind(&user_id)
        .bind(now + RESET_TOKEN_TTL_SECS)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Json(json!({
        "reset_token": token,
        "slot2": STANDARD.encode(slot2.unwrap_or_default()),
    })))
}

/// Rate-limits by token hash and returns (token_hash, user_id) of a live reset token.
async fn live_token(
    state: &AppState,
    token: &str,
    now: i64,
) -> Result<(Vec<u8>, String), ApiError> {
    let token_hash = hash_secret(token.as_bytes()).to_vec();
    state.limits.check(
        &format!("resettoken:{}", STANDARD.encode(&token_hash)),
        10,
        900,
        now,
    )?;
    let user_id: String = sqlx::query_scalar(
        "SELECT user_id FROM reset_tokens WHERE token_hash = ? AND expires_at >= ?",
    )
    .bind(&token_hash)
    .bind(now)
    .fetch_optional(&state.db)
    .await?
    .ok_or(ApiError::InvalidToken)?;
    Ok((token_hash, user_id))
}

/// Consumes the token inside the caller's transaction; a concurrent use makes this fail.
async fn consume_token(
    tx: &mut sqlx::SqliteConnection,
    token_hash: &[u8],
    now: i64,
) -> Result<(), ApiError> {
    let res = sqlx::query("DELETE FROM reset_tokens WHERE token_hash = ? AND expires_at >= ?")
        .bind(token_hash)
        .bind(now)
        .execute(tx)
        .await?;
    if res.rows_affected() == 1 {
        Ok(())
    } else {
        Err(ApiError::InvalidToken)
    }
}

/// Replaces the password slot after proving possession of the recovery code.
/// A wrong proof leaves the token valid for retries.
async fn complete(State(state): State<AppState>, body: Bytes) -> Result<StatusCode, ApiError> {
    let req: CompleteReq = parse_json(&body)?;
    let recovery_auth = decode_b64(&req.recovery_auth)?;
    let creds = NewCredentials::parse(&req.kdf, &req.salt_pw, &req.auth_key, &req.slot1)?;
    let now = (state.clock)();
    let (token_hash, user_id) = live_token(&state, &req.reset_token, now).await?;

    let stored: Option<Vec<u8>> = sqlx::query("SELECT recovery_auth_hash FROM users WHERE id = ?")
        .bind(&user_id)
        .fetch_one(&state.db)
        .await?
        .get("recovery_auth_hash");
    if !stored.is_some_and(|h| ct_eq(&hash_secret(&recovery_auth), &h)) {
        return Err(ApiError::InvalidRecovery);
    }

    let mut tx = state.db.begin().await?;
    consume_token(&mut tx, &token_hash, now).await?;
    sqlx::query("UPDATE users SET salt_pw = ?, kdf = ?, auth_hash = ?, slot1 = ? WHERE id = ?")
        .bind(&creds.salt_pw)
        .bind(&creds.kdf)
        .bind(&creds.auth_hash)
        .bind(&creds.slot1)
        .bind(&user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM sessions WHERE user_id = ?")
        .bind(&user_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Reset without the recovery code: new key material, backup blob deleted (the version
/// counter stays so later uploads keep a monotonic base), all sessions revoked.
async fn wipe(State(state): State<AppState>, body: Bytes) -> Result<StatusCode, ApiError> {
    let req: WipeReq = parse_json(&body)?;
    let creds = NewCredentials::parse(&req.kdf, &req.salt_pw, &req.auth_key, &req.slot1)?;
    let slot2 = decode_b64(&req.slot2)?;
    let recovery_auth = decode_b64(&req.recovery_auth)?;
    if slot2.is_empty() || recovery_auth.is_empty() {
        return Err(ApiError::InvalidInput);
    }
    let now = (state.clock)();
    let (token_hash, user_id) = live_token(&state, &req.reset_token, now).await?;

    let mut tx = state.db.begin().await?;
    consume_token(&mut tx, &token_hash, now).await?;
    sqlx::query(
        "UPDATE users SET salt_pw = ?, kdf = ?, auth_hash = ?, slot1 = ?, slot2 = ?,
           recovery_auth_hash = ? WHERE id = ?",
    )
    .bind(&creds.salt_pw)
    .bind(&creds.kdf)
    .bind(&creds.auth_hash)
    .bind(&creds.slot1)
    .bind(&slot2)
    .bind(hash_secret(&recovery_auth).to_vec())
    .bind(&user_id)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE backups SET nonce = NULL, ciphertext = NULL, updated_at = NULL WHERE user_id = ?",
    )
    .bind(&user_id)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM sessions WHERE user_id = ?")
        .bind(&user_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
