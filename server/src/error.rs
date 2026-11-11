use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ApiError {
    InvalidInput,
    InvalidCode,
    InvalidCredentials,
    Unverified,
    Unauthorized,
    InvalidToken,
    InvalidRecovery,
    Conflict { epoch: i64, version: Option<i64> },
    TooLarge,
    RateLimited,
    Internal,
}

impl ApiError {
    pub fn status(&self) -> StatusCode {
        match self {
            ApiError::InvalidInput | ApiError::InvalidCode => StatusCode::BAD_REQUEST,
            ApiError::InvalidCredentials
            | ApiError::Unauthorized
            | ApiError::InvalidToken
            | ApiError::InvalidRecovery => StatusCode::UNAUTHORIZED,
            ApiError::Unverified => StatusCode::FORBIDDEN,
            ApiError::Conflict { .. } => StatusCode::CONFLICT,
            ApiError::TooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            ApiError::RateLimited => StatusCode::TOO_MANY_REQUESTS,
            ApiError::Internal => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    pub fn code(&self) -> &'static str {
        match self {
            ApiError::InvalidInput => "invalid_input",
            ApiError::InvalidCode => "invalid_code",
            ApiError::InvalidCredentials => "invalid_credentials",
            ApiError::Unverified => "unverified",
            ApiError::Unauthorized => "unauthorized",
            ApiError::InvalidToken => "invalid_token",
            ApiError::InvalidRecovery => "invalid_recovery",
            ApiError::Conflict { .. } => "conflict",
            ApiError::TooLarge => "too_large",
            ApiError::RateLimited => "rate_limited",
            ApiError::Internal => "internal",
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = match &self {
            ApiError::Conflict { epoch, version } => {
                json!({ "error": self.code(), "epoch": epoch, "version": version })
            }
            _ => json!({ "error": self.code() }),
        };
        (self.status(), Json(body)).into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(_: sqlx::Error) -> Self {
        // Deliberately not logging the error: query errors may embed user data.
        tracing::error!("database error");
        ApiError::Internal
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statuses_and_codes_match_contract() {
        let cases = [
            (ApiError::InvalidInput, 400, "invalid_input"),
            (ApiError::InvalidCode, 400, "invalid_code"),
            (ApiError::InvalidCredentials, 401, "invalid_credentials"),
            (ApiError::Unverified, 403, "unverified"),
            (ApiError::Unauthorized, 401, "unauthorized"),
            (ApiError::InvalidToken, 401, "invalid_token"),
            (ApiError::InvalidRecovery, 401, "invalid_recovery"),
            (ApiError::Conflict { epoch: 1, version: None }, 409, "conflict"),
            (ApiError::TooLarge, 413, "too_large"),
            (ApiError::RateLimited, 429, "rate_limited"),
            (ApiError::Internal, 500, "internal"),
        ];
        for (e, status, code) in cases {
            assert_eq!(e.status().as_u16(), status);
            assert_eq!(e.code(), code);
        }
    }
}
