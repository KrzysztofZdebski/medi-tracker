use std::sync::Mutex;

use async_trait::async_trait;
use lettre::{
    message::{header::ContentType, Mailbox},
    transport::smtp::authentication::Credentials,
    AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor,
};

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

/// `EMAIL_MODE=smtp`: STARTTLS to `host:587` with username/password credentials.
pub struct SmtpMailer {
    transport: AsyncSmtpTransport<Tokio1Executor>,
    from: Mailbox,
}

impl SmtpMailer {
    pub fn new(host: &str, user: &str, pass: &str, from: &str) -> anyhow::Result<Self> {
        let from: Mailbox = from
            .parse()
            .map_err(|_| anyhow::anyhow!("SMTP_FROM is not a valid mailbox"))?;
        let transport = AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(host)?
            .port(587)
            .timeout(Some(std::time::Duration::from_secs(10)))
            .credentials(Credentials::new(user.to_string(), pass.to_string()))
            .build();
        Ok(Self { transport, from })
    }
}

#[async_trait]
impl Mailer for SmtpMailer {
    async fn send(&self, to: &str, subject: &str, body: &str) -> anyhow::Result<()> {
        let msg = Message::builder()
            .from(self.from.clone())
            .to(to.parse()?)
            .subject(subject)
            .header(ContentType::TEXT_PLAIN)
            .body(body.to_string())?;
        self.transport.send(msg).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn smtp_mailer_constructs_from_config() {
        assert!(SmtpMailer::new("smtp.example.com", "u", "p", "mediTracker <no-reply@example.com>").is_ok());
        assert!(SmtpMailer::new("smtp.example.com", "u", "p", "not a mailbox").is_err());
    }
}
