use axum::{extract::FromRequestParts, http::header, http::request::Parts};
use sqlx::Row;

use crate::{error::ApiError, routes::SESSION_TTL_SECS, secrets::hash_secret, AppState};

/// Authenticated caller, resolved from `Authorization: Bearer <token>`.
/// Missing, unknown or expired tokens are rejected with `Unauthorized`; a valid one slides the expiry.
pub struct Session {
    pub user_id: String,
    pub token_hash: [u8; 32],
}

impl FromRequestParts<AppState> for Session {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        let token = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(str::trim)
            .filter(|t| !t.is_empty())
            .ok_or(ApiError::Unauthorized)?;
        let token_hash = hash_secret(token.as_bytes());
        let now = (state.clock)();
        let row = sqlx::query(
            "UPDATE sessions SET last_used_at = ?, expires_at = ?
             WHERE token_hash = ? AND expires_at >= ? RETURNING user_id",
        )
        .bind(now)
        .bind(now + SESSION_TTL_SECS)
        .bind(token_hash.to_vec())
        .bind(now)
        .fetch_optional(&state.db)
        .await?
        .ok_or(ApiError::Unauthorized)?;
        Ok(Session {
            user_id: row.get("user_id"),
            token_hash,
        })
    }
}
