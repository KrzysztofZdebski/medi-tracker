pub mod account;
pub mod backup;
pub mod login;
pub mod password;
pub mod register;
pub mod reset;

use axum::{body::Bytes, Router};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{de::DeserializeOwned, Deserialize};
use sqlx::{Row, SqliteConnection};

use crate::{
    error::ApiError,
    secrets::{hash_secret, hmac_code, new_code, new_token},
    AppState,
};

pub const CODE_TTL_SECS: i64 = 15 * 60;
pub const SESSION_TTL_SECS: i64 = 90 * 24 * 3600;
pub const MAX_CODE_ATTEMPTS: i64 = 5;

pub fn router() -> Router<AppState> {
    register::router()
        .merge(login::router())
        .merge(backup::router())
        .merge(account::router())
        .merge(password::router())
        .merge(reset::router())
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
    let (subject, body) = match purpose {
        "reset" => (
            "mediTracker: kod resetowania hasła",
            format!(
                "Twój kod resetowania hasła: {code}. Wygasa za 15 minut. \
                 Jeśli to nie Ty, zignoruj tę wiadomość."
            ),
        ),
        _ => (
            "mediTracker: kod potwierdzający",
            format!("Twój kod: {code}. Wygasa za 15 minut."),
        ),
    };
    send_mail_detached(state, email, subject, body);
    Ok(())
}

/// Claims one of the (user, purpose) code's attempts atomically and returns its stored hash.
/// Claiming before comparing means concurrent guesses can't exceed the cap. A missing,
/// expired or exhausted code is `invalid_code`.
pub async fn claim_code_attempt(
    state: &AppState,
    user_id: &str,
    purpose: &str,
    now: i64,
) -> Result<Vec<u8>, ApiError> {
    let row = sqlx::query(
        "UPDATE email_codes SET attempts = attempts + 1
         WHERE user_id = ? AND purpose = ? AND attempts < ? AND expires_at >= ?
         RETURNING code_hash",
    )
    .bind(user_id)
    .bind(purpose)
    .bind(MAX_CODE_ATTEMPTS)
    .bind(now)
    .fetch_optional(&state.db)
    .await?
    .ok_or(ApiError::InvalidCode)?;
    Ok(row.get("code_hash"))
}

#[derive(Deserialize)]
pub struct Kdf {
    m: u32,
    t: u32,
    p: u32,
}

/// New password-slot material shared by register, change-password and reset, validated.
pub struct NewCredentials {
    pub salt_pw: Vec<u8>,
    pub kdf: String,
    pub auth_hash: Vec<u8>,
    pub slot1: Vec<u8>,
}

impl NewCredentials {
    pub fn parse(kdf: &Kdf, salt_pw: &str, auth_key: &str, slot1: &str) -> Result<Self, ApiError> {
        if kdf.m == 0 || kdf.t == 0 || kdf.p == 0 {
            return Err(ApiError::InvalidInput);
        }
        let salt_pw = decode_b64(salt_pw)?;
        let auth_key = decode_b64(auth_key)?;
        let slot1 = decode_b64(slot1)?;
        if salt_pw.len() != 16 || auth_key.is_empty() || slot1.is_empty() {
            return Err(ApiError::InvalidInput);
        }
        Ok(Self {
            salt_pw,
            kdf: serde_json::json!({"m": kdf.m, "t": kdf.t, "p": kdf.p}).to_string(),
            auth_hash: hash_secret(&auth_key).to_vec(),
            slot1,
        })
    }
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
pub async fn create_session(
    conn: &mut SqliteConnection,
    now: i64,
    user_id: &str,
) -> Result<String, ApiError> {
    let token = new_token();
    sqlx::query(
        "INSERT INTO sessions (token_hash, user_id, created_at, last_used_at, expires_at)
         VALUES (?, ?, ?, ?, ?)",
    )
    .bind(hash_secret(token.as_bytes()).to_vec())
    .bind(user_id)
    .bind(now)
    .bind(now)
    .bind(now + SESSION_TTL_SECS)
    .execute(conn)
    .await?;
    Ok(token)
}

/// Recovery slot material (`slot2` and the recovery proof's hash), validated.
pub struct RecoveryMaterial {
    pub slot2: Vec<u8>,
    pub recovery_auth_hash: Vec<u8>,
}

impl RecoveryMaterial {
    pub fn parse(slot2: &str, recovery_auth: &str) -> Result<Self, ApiError> {
        let slot2 = decode_b64(slot2)?;
        let recovery_auth = decode_b64(recovery_auth)?;
        if slot2.is_empty() || recovery_auth.is_empty() {
            return Err(ApiError::InvalidInput);
        }
        Ok(Self {
            slot2,
            recovery_auth_hash: hash_secret(&recovery_auth).to_vec(),
        })
    }
}
