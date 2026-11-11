mod common;

use meditracker_server::maintenance;

#[tokio::test]
async fn purge_expired_removes_only_expired_sessions_and_reset_tokens() {
    let app = common::spawn().await;
    sqlx::query("INSERT INTO users (id, email, created_at, kdf) VALUES ('u','e@x',1,'{}')")
        .execute(&app.db)
        .await
        .unwrap();
    for (hash, exp) in [(b"a", 100i64), (b"b", 300)] {
        sqlx::query("INSERT INTO sessions VALUES (?, 'u', 1, 1, ?)")
            .bind(&hash[..])
            .bind(exp)
            .execute(&app.db)
            .await
            .unwrap();
        sqlx::query("INSERT INTO reset_tokens VALUES (?, 'u', ?)")
            .bind(&hash[..])
            .bind(exp)
            .execute(&app.db)
            .await
            .unwrap();
    }
    let state = app.state();
    assert_eq!(maintenance::purge_expired(&state, 200).await.unwrap(), 2);
    for table in ["sessions", "reset_tokens"] {
        let left: Vec<i64> = sqlx::query_scalar(&format!("SELECT expires_at FROM {table}"))
            .fetch_all(&app.db)
            .await
            .unwrap();
        assert_eq!(left, vec![300], "{table}");
    }
}

#[tokio::test]
async fn backup_db_writes_a_copy_and_prunes_old_ones() {
    // VACUUM INTO is a no-op on in-memory databases, so use a real file.
    let dir = std::env::temp_dir().join(format!("meditracker-backup-test-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let src_url = format!("sqlite:{}", dir.join("src.db").display());
    let src = meditracker_server::init_db(&src_url).await.unwrap();
    sqlx::query("INSERT INTO users (id, email, created_at, kdf) VALUES ('u','e@x',1,'{}')")
        .execute(&src)
        .await
        .unwrap();
    let app = common::spawn().await;
    let mut state = app.state();
    state.db = src;

    let bdir = dir.join("backups");
    std::fs::create_dir_all(&bdir).unwrap();
    for d in 1..=8 {
        std::fs::write(bdir.join(format!("2020-01-{d:02}.db")), b"old").unwrap();
    }
    let path = maintenance::backup_db(&state, &bdir, 1_700_000_000).await.unwrap();
    assert_eq!(path.file_name().unwrap(), "2023-11-14.db");
    // running again the same day overwrites instead of failing
    maintenance::backup_db(&state, &bdir, 1_700_000_000).await.unwrap();

    let copy = sqlx::SqlitePool::connect(&format!("sqlite:{}", path.display())).await.unwrap();
    let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM users").fetch_one(&copy).await.unwrap();
    assert_eq!(n, 1);
    copy.close().await;

    let mut left: Vec<String> = std::fs::read_dir(&bdir)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    left.sort();
    assert_eq!(left.len(), 7);
    assert_eq!(left[0], "2020-01-03.db");
    assert_eq!(left[6], "2023-11-14.db");
    state.db.close().await;
    std::fs::remove_dir_all(&dir).unwrap();
}
