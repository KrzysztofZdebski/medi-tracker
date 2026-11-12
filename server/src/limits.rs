use std::{collections::HashMap, sync::Mutex};

use crate::error::ApiError;

/// Simple in-memory sliding-window rate limiter keyed by an arbitrary string.
#[derive(Default)]
pub struct Limits {
    hits: Mutex<HashMap<String, Vec<i64>>>,
}

impl Limits {
    pub fn new() -> Self {
        Self::default()
    }

    /// Allows at most `max` calls per `per_secs` window for `key`; `now` is unix seconds.
    pub fn check(&self, key: &str, max: u32, per_secs: i64, now: i64) -> Result<(), ApiError> {
        let mut map = self.hits.lock().unwrap();
        let hits = map.entry(key.to_string()).or_default();
        hits.retain(|t| *t > now - per_secs);
        if hits.len() as u32 >= max {
            return Err(ApiError::RateLimited);
        }
        hits.push(now);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_after_max_and_recovers_after_window() {
        let l = Limits::new();
        assert!(l.check("k", 2, 60, 100).is_ok());
        assert!(l.check("k", 2, 60, 101).is_ok());
        assert_eq!(l.check("k", 2, 60, 102), Err(ApiError::RateLimited));
        assert!(l.check("other", 2, 60, 102).is_ok());
        assert!(l.check("k", 2, 60, 161).is_ok());
    }
}
