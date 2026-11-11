use axum::{
    body::Bytes,
    extract::{rejection::BytesRejection, DefaultBodyLimit, State},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::Row;

use super::{decode_b64, parse_json};
use crate::{error::ApiError, session::Session, AppState, MAX_BODY_BYTES};

/// Decoded ciphertext cap.
const MAX_BLOB_BYTES: usize = MAX_BODY_BYTES;
/// Request cap for `/backup`: a 5 MiB blob is ~6.7 MiB as base64, plus JSON overhead.
const MAX_BACKUP_REQUEST_BYTES: usize = 7 * 1024 * 1024;

pub fn router() -> Router<AppState> {
    Router::new()
        .route(
            "/backup",
            get(get_backup)
                .put(put_backup)
                .layer(DefaultBodyLimit::max(MAX_BACKUP_REQUEST_BYTES)),
        )
        .route("/backup/takeover", post(takeover))
}

#[derive(Deserialize)]
struct PutReq {
    epoch: i64,
    base_version: Option<i64>,
    nonce: String,
    ciphertext: String,
}

/// Maps a body-limit rejection to the JSON `too_large` error; anything else is bad input.
pub fn body_bytes(body: Result<Bytes, BytesRejection>) -> Result<Bytes, ApiError> {
    body.map_err(|e| {
        if e.status() == StatusCode::PAYLOAD_TOO_LARGE {
            ApiError::TooLarge
        } else {
            ApiError::InvalidInput
        }
    })
}

async fn get_backup(
    State(state): State<AppState>,
    session: Session,
) -> Result<Json<Value>, ApiError> {
    let epoch: i64 = sqlx::query_scalar("SELECT active_epoch FROM users WHERE id = ?")
        .bind(&session.user_id)
        .fetch_one(&state.db)
        .await?;
    let row = sqlx::query(
        "SELECT version, nonce, ciphertext, updated_at FROM backups WHERE user_id = ?",
    )
    .bind(&session.user_id)
    .fetch_optional(&state.db)
    .await?;
    let blob = row.and_then(|r| {
        let nonce: Option<Vec<u8>> = r.get("nonce");
        let ciphertext: Option<Vec<u8>> = r.get("ciphertext");
        Some((
            r.get::<i64, _>("version"),
            nonce?,
            ciphertext?,
            r.get::<Option<i64>, _>("updated_at"),
        ))
    });
    Ok(Json(match blob {
        Some((version, nonce, ciphertext, updated_at)) => json!({
            "version": version,
            "epoch": epoch,
            "nonce": STANDARD.encode(nonce),
            "ciphertext": STANDARD.encode(ciphertext),
            "updated_at": updated_at,
        }),
        None => json!({
            "version": null,
            "epoch": epoch,
            "nonce": null,
            "ciphertext": null,
            "updated_at": null,
        }),
    }))
}

async fn put_backup(
    State(state): State<AppState>,
    session: Session,
    body: Result<Bytes, BytesRejection>,
) -> Result<Json<Value>, ApiError> {
    let req: PutReq = parse_json(&body_bytes(body)?)?;
    let nonce = decode_b64(&req.nonce)?;
    let ciphertext = decode_b64(&req.ciphertext)?;
    if ciphertext.len() > MAX_BLOB_BYTES {
        return Err(ApiError::TooLarge);
    }
    let now = (state.clock)();

    let mut tx = state.db.begin().await?;
    // A no-op write first takes SQLite's write lock, so the check and the write below can't interleave
    // with another writer (and can't fail with a busy-snapshot upgrade error).
    let active_epoch: i64 = sqlx::query_scalar(
        "UPDATE users SET active_epoch = active_epoch WHERE id = ? RETURNING active_epoch",
    )
    .bind(&session.user_id)
    .fetch_one(&mut *tx)
    .await?;
    let current: Option<i64> = sqlx::query_scalar("SELECT version FROM backups WHERE user_id = ?")
        .bind(&session.user_id)
        .fetch_optional(&mut *tx)
        .await?;
    if req.epoch != active_epoch || req.base_version != current {
        return Err(ApiError::Conflict {
            epoch: active_epoch,
            version: current,
        });
    }
    let version = current.unwrap_or(0) + 1;
    sqlx::query(
        "INSERT INTO backups (user_id, version, nonce, ciphertext, updated_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(user_id) DO UPDATE SET
           version = excluded.version, nonce = excluded.nonce,
           ciphertext = excluded.ciphertext, updated_at = excluded.updated_at",
    )
    .bind(&session.user_id)
    .bind(version)
    .bind(nonce)
    .bind(ciphertext)
    .bind(now)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(Json(json!({ "version": version })))
}

async fn takeover(
    State(state): State<AppState>,
    session: Session,
) -> Result<Json<Value>, ApiError> {
    let mut tx = state.db.begin().await?;
    let epoch: i64 = sqlx::query_scalar(
        "UPDATE users SET active_epoch = active_epoch + 1 WHERE id = ? RETURNING active_epoch",
    )
    .bind(&session.user_id)
    .fetch_one(&mut *tx)
    .await?;
    let version: Option<i64> = sqlx::query_scalar("SELECT version FROM backups WHERE user_id = ?")
        .bind(&session.user_id)
        .fetch_optional(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Json(json!({ "epoch": epoch, "version": version })))
}
