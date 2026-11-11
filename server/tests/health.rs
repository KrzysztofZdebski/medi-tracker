mod common;

use axum::http::StatusCode;

#[tokio::test]
async fn health_returns_ok() {
    let app = common::spawn().await;
    let (status, body) = app.get("/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, "ok");
}

#[tokio::test]
async fn migrations_create_schema_with_foreign_keys_on() {
    let app = common::spawn().await;
    for table in ["users", "email_codes", "sessions", "reset_tokens", "backups"] {
        let n: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")
                .bind(table)
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(n, 1, "missing table {table}");
    }
    let fk: i64 = sqlx::query_scalar("PRAGMA foreign_keys")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(fk, 1);
    // cascade: deleting a user removes its backup
    sqlx::query("INSERT INTO users (id, email, created_at, kdf) VALUES ('u','e@x',1,'{}')")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO backups (user_id, version) VALUES ('u', 1)")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM users WHERE id='u'")
        .execute(&app.db)
        .await
        .unwrap();
    let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM backups")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(n, 0);
}

#[tokio::test]
async fn catalog_files_are_served() {
    let dir = std::env::temp_dir().join(format!("meditracker-catalog-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("version.json"), br#"{"schema":1}"#).unwrap();
    let blob: Vec<u8> = (0u8..=255).collect();
    std::fs::write(dir.join("leki.sqlite.gz"), &blob).unwrap();

    let app = common::spawn_with_catalog(dir.clone()).await;
    let (status, body) = app.get_raw("/catalog/version.json").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, br#"{"schema":1}"#);
    let (status, body) = app.get_raw("/catalog/leki.sqlite.gz").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, blob);
    let (status, _) = app.get_raw("/catalog/missing.bin").await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    std::fs::remove_dir_all(&dir).unwrap();
}
