use axum::{routing::get, Json, Router};
use serde_json::{json, Value};

use crate::{session::Session, AppState};

pub fn router() -> Router<AppState> {
    Router::new().route("/backup", get(get_backup))
}

// Placeholder until Task 4 implements the real backup endpoints.
async fn get_backup(_session: Session) -> Json<Value> {
    Json(json!({}))
}
