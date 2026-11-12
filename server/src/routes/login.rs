use axum::{body::Bytes, extract::State, http::StatusCode, routing::post, Json, Router};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::Row;

use super::{create_session, decode_b64, normalize_email, parse_json};
use crate::{
    error::ApiError,
    secrets::{ct_eq, fake_salt, hash_secret},
    session::Session,
    AppState,
};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/auth/prelogin", post(prelogin))
        .route("/auth/login", post(login))
        .route("/auth/logout", post(logout))
}

#[derive(Deserialize)]
struct PreloginReq {
    email: String,
}

#[derive(Deserialize)]
struct LoginReq {
    email: String,
    auth_key: String,
}

async fn prelogin(State(state): State<AppState>, body: Bytes) -> Result<Json<Value>, ApiError> {
    let req: PreloginReq = parse_json(&body)?;
    let email = normalize_email(&req.email);
    let now = (state.clock)();
    state.limits.check(&format!("prelogin:{email}"), 30, 900, now)?;

    let row = sqlx::query("SELECT salt_pw, kdf FROM users WHERE email = ? AND email_verified_at IS NOT NULL")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    let (salt, kdf) = match row {
        Some(r) => {
            let salt: Option<Vec<u8>> = r.get("salt_pw");
            let kdf: String = r.get("kdf");
            (
                salt.unwrap_or_else(|| fake_salt(&state.config.server_secret, &email).to_vec()),
                serde_json::from_str(&kdf).unwrap_or_else(|_| default_kdf()),
            )
        }
        None => (
            fake_salt(&state.config.server_secret, &email).to_vec(),
            default_kdf(),
        ),
    };
    Ok(Json(json!({"salt_pw": STANDARD.encode(salt), "kdf": kdf})))
}

fn default_kdf() -> Value {
    json!({"m": 65536, "t": 3, "p": 1})
}

async fn login(State(state): State<AppState>, body: Bytes) -> Result<Json<Value>, ApiError> {
    let req: LoginReq = parse_json(&body)?;
    let email = normalize_email(&req.email);
    let now = (state.clock)();
    state.limits.check(&format!("login:{email}"), 10, 900, now)?;
    let auth_key = decode_b64(&req.auth_key)?;

    let user = sqlx::query(
        "SELECT id, auth_hash, slot1 FROM users WHERE email = ? AND email_verified_at IS NOT NULL",
    )
    .bind(&email)
    .fetch_optional(&state.db)
    .await?;

    // Unknown emails compare against a dummy hash so the work is the same as for known ones.
    let supplied = hash_secret(&auth_key);
    let stored: Vec<u8> = user
        .as_ref()
        .and_then(|r| r.get::<Option<Vec<u8>>, _>("auth_hash"))
        .unwrap_or_else(|| hash_secret(b"meditracker-dummy-auth").to_vec());
    let key_ok = ct_eq(&supplied, &stored);
    let user = match user {
        Some(u) if key_ok => u,
        _ => return Err(ApiError::InvalidCredentials),
    };
    let user_id: String = user.get("id");
    let slot1: Option<Vec<u8>> = user.get("slot1");

    let mut tx = state.db.begin().await?;
    let epoch: i64 = sqlx::query_scalar(
        "UPDATE users SET active_epoch = active_epoch + 1 WHERE id = ? RETURNING active_epoch",
    )
    .bind(&user_id)
    .fetch_one(&mut *tx)
    .await?;
    let token = create_session(&mut tx, now, &user_id).await?;
    let version: Option<i64> = sqlx::query_scalar("SELECT version FROM backups WHERE user_id = ?")
        .bind(&user_id)
        .fetch_optional(&mut *tx)
        .await?;
    tx.commit().await?;

    Ok(Json(json!({
        "token": token,
        "user_id": user_id,
        "slot1": STANDARD.encode(slot1.unwrap_or_default()),
        "epoch": epoch,
        "backup_version": version,
    })))
}

async fn logout(
    State(state): State<AppState>,
    session: Session,
) -> Result<StatusCode, ApiError> {
    sqlx::query("DELETE FROM sessions WHERE token_hash = ?")
        .bind(session.token_hash.to_vec())
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
