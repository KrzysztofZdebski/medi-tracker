use axum::{
    body::Bytes,
    extract::{rejection::BytesRejection, State},
    http::StatusCode,
    routing::delete,
    Router,
};
use serde::Deserialize;
use sqlx::Row;

use super::{backup::body_bytes, decode_b64, parse_json};
use crate::{
    error::ApiError,
    secrets::{ct_eq, hash_secret},
    session::Session,
    AppState,
};

pub fn router() -> Router<AppState> {
    Router::new().route("/account", delete(delete_account))
}

#[derive(Deserialize)]
struct DeleteReq {
    auth_key: String,
}

/// Deletes the account after re-checking the auth key; FK cascades remove sessions, codes,
/// reset tokens and the backup.
async fn delete_account(
    State(state): State<AppState>,
    session: Session,
    body: Result<Bytes, BytesRejection>,
) -> Result<StatusCode, ApiError> {
    let req: DeleteReq = parse_json(&body_bytes(body)?)?;
    let auth_key = decode_b64(&req.auth_key)?;
    let now = (state.clock)();
    state
        .limits
        .check(&format!("delete:{}", session.user_id), 10, 900, now)?;

    let stored: Option<Vec<u8>> = sqlx::query("SELECT auth_hash FROM users WHERE id = ?")
        .bind(&session.user_id)
        .fetch_one(&state.db)
        .await?
        .get("auth_hash");
    let key_ok = stored.is_some_and(|h| ct_eq(&hash_secret(&auth_key), &h));
    if !key_ok {
        return Err(ApiError::InvalidCredentials);
    }
    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(&session.user_id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
