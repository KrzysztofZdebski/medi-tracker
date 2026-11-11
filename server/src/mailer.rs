use std::sync::Mutex;

use async_trait::async_trait;

#[async_trait]
pub trait Mailer: Send + Sync {
    async fn send(&self, to: &str, subject: &str, body: &str) -> anyhow::Result<()>;
}

/// `EMAIL_MODE=log`: prints the message (including codes) to the log.
pub struct LogMailer;

#[async_trait]
impl Mailer for LogMailer {
    async fn send(&self, to: &str, subject: &str, body: &str) -> anyhow::Result<()> {
        tracing::info!("EMAIL to={to} subject={subject}\n{body}");
        Ok(())
    }
}

/// Test mailer: records (to, subject, body).
#[derive(Default)]
pub struct MemoryMailer {
    pub sent: Mutex<Vec<(String, String, String)>>,
}

#[async_trait]
impl Mailer for MemoryMailer {
    async fn send(&self, to: &str, subject: &str, body: &str) -> anyhow::Result<()> {
        self.sent
            .lock()
            .unwrap()
            .push((to.to_string(), subject.to_string(), body.to_string()));
        Ok(())
    }
}
