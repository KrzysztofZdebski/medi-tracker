use axum::{body::Bytes, extract::State, http::StatusCode, routing::post, Router};
use serde::Deserialize;
use sqlx::Row;

use super::{decode_b64, parse_json, Kdf, NewCredentials};
use crate::{
    error::ApiError,
    secrets::{ct_eq, hash_secret},
    session::Session,
    AppState,
};

pub fn router() -> Router<AppState> {
    Router::new().route("/auth/password", post(change_password))
}

#[derive(Deserialize)]
struct PasswordReq {
    old_auth_key: String,
    salt_pw: String,
    kdf: Kdf,
    auth_key: String,
    slot1: String,
}

/// Swaps the password slot after re-checking the old auth key. slot2 and the recovery proof
/// stay; every session except the caller's is revoked.
async fn change_password(
    State(state): State<AppState>,
    session: Session,
    body: Bytes,
) -> Result<StatusCode, ApiError> {
    let req: PasswordReq = parse_json(&body)?;
    let old_key = decode_b64(&req.old_auth_key)?;
    let creds = NewCredentials::parse(&req.kdf, &req.salt_pw, &req.auth_key, &req.slot1)?;
    let now = (state.clock)();
    state
        .limits
        .check(&format!("password:{}", session.user_id), 10, 900, now)?;

    let stored: Option<Vec<u8>> = sqlx::query("SELECT auth_hash FROM users WHERE id = ?")
        .bind(&session.user_id)
        .fetch_one(&state.db)
        .await?
        .get("auth_hash");
    if !stored.is_some_and(|h| ct_eq(&hash_secret(&old_key), &h)) {
        return Err(ApiError::InvalidCredentials);
    }

    let mut tx = state.db.begin().await?;
    sqlx::query("UPDATE users SET salt_pw = ?, kdf = ?, auth_hash = ?, slot1 = ? WHERE id = ?")
        .bind(&creds.salt_pw)
        .bind(&creds.kdf)
        .bind(&creds.auth_hash)
        .bind(&creds.slot1)
        .bind(&session.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?")
        .bind(&session.user_id)
        .bind(session.token_hash.to_vec())
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
