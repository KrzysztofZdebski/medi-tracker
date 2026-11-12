use axum::{body::Bytes, extract::State, http::StatusCode, routing::post, Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::Row;

use super::{
    claim_code_attempt, create_session, decode_b64, issue_code, normalize_email, parse_json,
    send_mail_detached, valid_email, Kdf, NewCredentials, RecoveryMaterial,
};
use crate::{
    error::ApiError,
    secrets::{ct_eq, hash_secret, hmac_code, new_id},
    AppState,
};

const PURPOSE: &str = "verify";
const UNVERIFIED_TTL_SECS: i64 = 24 * 3600;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/auth/register", post(register))
        .route("/auth/verify", post(verify))
        .route("/auth/verify/resend", post(resend))
}

#[derive(Deserialize)]
struct RegisterReq {
    email: String,
    kdf: Kdf,
    salt_pw: String,
    auth_key: String,
    slot1: String,
    slot2: String,
    recovery_auth: String,
}

#[derive(Deserialize)]
struct VerifyReq {
    email: String,
    code: String,
    auth_key: String,
}

#[derive(Deserialize)]
struct ResendReq {
    email: String,
}

fn accepted() -> (StatusCode, Json<Value>) {
    (StatusCode::ACCEPTED, Json(json!({})))
}

async fn register(
    State(state): State<AppState>,
    body: Bytes,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let req: RegisterReq = parse_json(&body)?;
    let email = normalize_email(&req.email);
    if !valid_email(&email) {
        return Err(ApiError::InvalidInput);
    }
    let creds = NewCredentials::parse(&req.kdf, &req.salt_pw, &req.auth_key, &req.slot1)?;
    let recovery = RecoveryMaterial::parse(&req.slot2, &req.recovery_auth)?;
    let now = (state.clock)();
    state.limits.check(&format!("register:{email}"), 5, 3600, now)?;

    let existing = sqlx::query("SELECT id, email_verified_at FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    if let Some(row) = &existing {
        let verified: Option<i64> = row.get("email_verified_at");
        if verified.is_some() {
            let notice = "Ktoś próbował utworzyć konto mediTracker z tym adresem e-mail. \
                          Jeśli to byłeś Ty, możesz się zalogować na istniejące konto. \
                          W przeciwnym razie zignoruj tę wiadomość.";
            send_mail_detached(&state, &email, "mediTracker: próba rejestracji", notice.into());
            return Ok(accepted());
        }
    }

    let user_id: String = match existing {
        Some(row) => {
            let id: String = row.get("id");
            sqlx::query(
                "UPDATE users SET created_at = ?, kdf = ?, salt_pw = ?, auth_hash = ?, slot1 = ?,
                   slot2 = ?, recovery_auth_hash = ?
                 WHERE id = ? AND email_verified_at IS NULL",
            )
            .bind(now)
            .bind(&creds.kdf)
            .bind(&creds.salt_pw)
            .bind(&creds.auth_hash)
            .bind(&creds.slot1)
            .bind(&recovery.slot2)
            .bind(&recovery.recovery_auth_hash)
            .bind(&id)
            .execute(&state.db)
            .await?;
            id
        }
        None => {
            let id = new_id();
            sqlx::query(
                "INSERT INTO users (id, email, created_at, kdf, salt_pw, auth_hash, slot1, slot2,
                   recovery_auth_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&id)
            .bind(&email)
            .bind(now)
            .bind(&creds.kdf)
            .bind(&creds.salt_pw)
            .bind(&creds.auth_hash)
            .bind(&creds.slot1)
            .bind(&recovery.slot2)
            .bind(&recovery.recovery_auth_hash)
            .execute(&state.db)
            .await?;
            id
        }
    };
    issue_code(&state, &user_id, PURPOSE, &email).await?;
    Ok(accepted())
}

async fn verify(State(state): State<AppState>, body: Bytes) -> Result<Json<Value>, ApiError> {
    let req: VerifyReq = parse_json(&body)?;
    let email = normalize_email(&req.email);
    let now = (state.clock)();
    state.limits.check(&format!("verify:{email}"), 10, 900, now)?;

    let auth_key = decode_b64(&req.auth_key)?;

    let user = sqlx::query(
        "SELECT id, active_epoch, auth_hash FROM users WHERE email = ? AND email_verified_at IS NULL",
    )
    .bind(&email)
    .fetch_optional(&state.db)
    .await?
    .ok_or(ApiError::InvalidCode)?;
    let user_id: String = user.get("id");
    let epoch: i64 = user.get("active_epoch");
    let auth_hash: Vec<u8> = user.get("auth_hash");

    // Claim an attempt atomically before comparing, so concurrent guesses can't exceed the cap.
    let code_hash = claim_code_attempt(&state, &user_id, PURPOSE, now).await?;

    // Both checks always run; the key binding stops a re-registrant from reusing the victim's code.
    let code_ok = ct_eq(&hmac_code(&state.config.server_secret, req.code.trim()), &code_hash);
    let key_ok = ct_eq(&hash_secret(&auth_key), &auth_hash);
    if !(code_ok & key_ok) {
        return Err(ApiError::InvalidCode);
    }

    sqlx::query("UPDATE users SET email_verified_at = ? WHERE id = ?")
        .bind(now)
        .bind(&user_id)
        .execute(&state.db)
        .await?;
    sqlx::query("DELETE FROM email_codes WHERE user_id = ? AND purpose = ?")
        .bind(&user_id)
        .bind(PURPOSE)
        .execute(&state.db)
        .await?;
    let mut conn = state.db.acquire().await?;
    let token = create_session(&mut conn, now, &user_id).await?;
    Ok(Json(json!({"token": token, "user_id": user_id, "epoch": epoch})))
}

async fn resend(
    State(state): State<AppState>,
    body: Bytes,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let req: ResendReq = parse_json(&body)?;
    let email = normalize_email(&req.email);
    let now = (state.clock)();
    state.limits.check(&format!("resend:{email}"), 3, 600, now)?;

    let user: Option<String> =
        sqlx::query_scalar("SELECT id FROM users WHERE email = ? AND email_verified_at IS NULL")
            .bind(&email)
            .fetch_optional(&state.db)
            .await?;
    if let Some(user_id) = user {
        issue_code(&state, &user_id, PURPOSE, &email).await?;
    }
    Ok(accepted())
}

/// Deletes accounts that never verified their email within 24 h (codes cascade).
pub async fn purge_unverified(state: &AppState, now: i64) -> Result<u64, ApiError> {
    let res = sqlx::query("DELETE FROM users WHERE email_verified_at IS NULL AND created_at < ?")
        .bind(now - UNVERIFIED_TTL_SECS)
        .execute(&state.db)
        .await?;
    Ok(res.rows_affected())
}
