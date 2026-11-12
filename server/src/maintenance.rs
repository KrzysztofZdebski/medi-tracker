//! Background upkeep: purging expired rows and nightly database backups.

use std::path::{Path, PathBuf};

use crate::{routes::register::purge_unverified, AppState};

pub const KEEP_BACKUPS: usize = 7;

/// Deletes sessions and reset tokens whose `expires_at` has passed; returns rows removed.
pub async fn purge_expired(state: &AppState, now: i64) -> Result<u64, sqlx::Error> {
    let mut n = 0;
    for table in ["sessions", "reset_tokens"] {
        n += sqlx::query(&format!("DELETE FROM {table} WHERE expires_at < ?"))
            .bind(now)
            .execute(&state.db)
            .await?
            .rows_affected();
    }
    Ok(n)
}

/// Given backup file names, returns those to delete so that only the newest `keep`
/// `YYYY-MM-DD.db` files remain. Other file names are never selected.
pub fn backups_to_delete(names: &[String], keep: usize) -> Vec<String> {
    let mut dated: Vec<&String> = names.iter().filter(|n| is_backup_name(n)).collect();
    dated.sort(); // ISO dates sort chronologically
    let excess = dated.len().saturating_sub(keep);
    dated.into_iter().take(excess).cloned().collect()
}

fn is_backup_name(name: &str) -> bool {
    let b = name.as_bytes();
    name.len() == 13
        && name.ends_with(".db")
        && b[..10].iter().enumerate().all(|(i, c)| match i {
            4 | 7 => *c == b'-',
            _ => c.is_ascii_digit(),
        })
}

/// `YYYY-MM-DD` for a unix timestamp (UTC).
pub fn date_string(unix_secs: i64) -> String {
    let z = unix_secs.div_euclid(86_400) + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

/// Writes a consistent copy of the database to `<dir>/<date>.db` and prunes old copies.
pub async fn backup_db(state: &AppState, dir: &Path, now: i64) -> anyhow::Result<PathBuf> {
    tokio::fs::create_dir_all(dir).await?;
    let path = dir.join(format!("{}.db", date_string(now)));
    // VACUUM INTO refuses to overwrite an existing file (e.g. after a same-day restart).
    let _ = tokio::fs::remove_file(&path).await;
    let target = path.to_string_lossy().into_owned();
    let sql = format!("VACUUM INTO '{}'", target.replace("'", "''"));
    sqlx::query(&sql).execute(&state.db).await?;
    let mut names = Vec::new();
    let mut rd = tokio::fs::read_dir(dir).await?;
    while let Some(e) = rd.next_entry().await? {
        names.push(e.file_name().to_string_lossy().into_owned());
    }
    for old in backups_to_delete(&names, KEEP_BACKUPS) {
        let _ = tokio::fs::remove_file(dir.join(old)).await;
    }
    Ok(path)
}

/// Spawns the hourly purge and the daily backup tasks.
pub fn spawn(state: AppState, backup_dir: PathBuf) {
    let purge = state.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(3600));
        loop {
            tick.tick().await;
            let now = (purge.clock)();
            if purge_unverified(&purge, now).await.is_err() {
                tracing::error!("purging unverified accounts failed");
            }
            if purge_expired(&purge, now).await.is_err() {
                tracing::error!("purging expired sessions and reset tokens failed");
            }
        }
    });
    tokio::spawn(async move {
        // The first tick fires immediately: one backup at startup, then every 24 h.
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(86_400));
        loop {
            tick.tick().await;
            match backup_db(&state, &backup_dir, (state.clock)()).await {
                Ok(_) => tracing::info!("database backup written"),
                Err(_) => tracing::error!("database backup failed"),
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn keeps_newest_seven_and_ignores_foreign_files() {
        let files = names(&[
            "2026-11-01.db", "2026-11-02.db", "2026-11-03.db", "2026-11-04.db",
            "2026-11-05.db", "2026-11-06.db", "2026-11-07.db", "2026-11-08.db",
            "2026-11-09.db", "notes.txt", "2026-11-10.db.tmp",
        ]);
        assert_eq!(backups_to_delete(&files, 7), names(&["2026-11-01.db", "2026-11-02.db"]));
        assert!(backups_to_delete(&files[..7], 7).is_empty());
    }

    #[test]
    fn date_string_is_utc_iso() {
        assert_eq!(date_string(0), "1970-01-01");
        assert_eq!(date_string(1_700_000_000), "2023-11-14");
        assert_eq!(date_string(1_709_164_800), "2024-02-29");
    }
}
