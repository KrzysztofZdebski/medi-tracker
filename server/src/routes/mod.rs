pub mod register;

use axum::{body::Bytes, Router};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::de::DeserializeOwned;

use crate::{
    error::ApiError,
    secrets::{hash_secret, hmac_code, new_code, new_token},
    AppState,
};

pub const CODE_TTL_SECS: i64 = 15 * 60;
pub const SESSION_TTL_SECS: i64 = 90 * 24 * 3600;

pub fn router() -> Router<AppState> {
    register::router()
}

/// Parses a JSON body, mapping every failure (malformed, missing fields) to `invalid_input`.
pub fn parse_json<T: DeserializeOwned>(body: &Bytes) -> Result<T, ApiError> {
    serde_json::from_slice(body).map_err(|_| ApiError::InvalidInput)
}

/// Canonical email form used for storage, lookups and rate-limit keys.
pub fn normalize_email(email: &str) -> String {
    email.trim().to_lowercase()
}

pub fn valid_email(email: &str) -> bool {
    if email.len() > 254 || email.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return false;
    }
    match email.split_once('@') {
        Some((local, domain)) => {
            !local.is_empty()
                && !domain.contains('@')
                && domain.contains('.')
                && !domain.starts_with('.')
                && !domain.ends_with('.')
        }
        None => false,
    }
}

pub fn decode_b64(s: &str) -> Result<Vec<u8>, ApiError> {
    STANDARD.decode(s).map_err(|_| ApiError::InvalidInput)
}

/// Replaces any existing code for (user_id, purpose), resets attempts and emails the new code.
/// Mail delivery failures are logged (without addresses) but not surfaced, so responses
/// never depend on whether an address exists or is deliverable.
pub async fn issue_code(
    state: &AppState,
    user_id: &str,
    purpose: &str,
    email: &str,
) -> Result<(), ApiError> {
    let code = new_code();
    let now = (state.clock)();
    sqlx::query(
        "INSERT INTO email_codes (user_id, purpose, code_hash, expires_at, attempts)
         VALUES (?, ?, ?, ?, 0)
         ON CONFLICT(user_id, purpose) DO UPDATE SET
           code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0",
    )
    .bind(user_id)
    .bind(purpose)
    .bind(hmac_code(&state.config.server_secret, &code).to_vec())
    .bind(now + CODE_TTL_SECS)
    .execute(&state.db)
    .await?;
    let body = format!("Twój kod: {code}. Wygasa za 15 minut.");
    send_mail_detached(state, email, "mediTracker: kod potwierdzający", body);
    Ok(())
}

/// Sends a mail off the request path, so response timing doesn't reveal whether a mail was sent.
/// Failures are logged without addresses and not surfaced.
pub fn send_mail_detached(state: &AppState, to: &str, subject: &'static str, body: String) {
    let mailer = state.mailer.clone();
    let to = to.to_string();
    tokio::spawn(async move {
        if mailer.send(&to, subject, &body).await.is_err() {
            tracing::error!("sending email failed");
        }
    });
}

/// Creates a 90-day session and returns the raw bearer token (only its hash is stored).
pub async fn create_session(state: &AppState, user_id: &str) -> Result<String, ApiError> {
    let token = new_token();
    let now = (state.clock)();
    sqlx::query(
        "INSERT INTO sessions (token_hash, user_id, created_at, last_used_at, expires_at)
         VALUES (?, ?, ?, ?, ?)",
    )
    .bind(hash_secret(token.as_bytes()).to_vec())
    .bind(user_id)
    .bind(now)
    .bind(now)
    .bind(now + SESSION_TTL_SECS)
    .execute(&state.db)
    .await?;
    Ok(token)
}
