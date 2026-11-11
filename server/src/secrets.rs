use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hmac::{Hmac, Mac};
use rand::{rngs::OsRng, Rng, RngCore};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

type HmacSha256 = Hmac<Sha256>;

/// SHA-256, used for `auth_key`, `recovery_auth` and session/reset tokens.
pub fn hash_secret(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

fn hmac(secret: &[u8], domain: &str, data: &str) -> [u8; 32] {
    let mut mac = HmacSha256::new_from_slice(secret).expect("HMAC accepts any key length");
    mac.update(domain.as_bytes());
    mac.update(b":");
    mac.update(data.as_bytes());
    mac.finalize().into_bytes().into()
}

/// Keyed hash of an email code, so stored code hashes can't be brute-forced offline without the secret.
pub fn hmac_code(secret: &[u8], code: &str) -> [u8; 32] {
    hmac(secret, "code", code)
}

/// Uniform 6-digit code from the OS CSPRNG.
pub fn new_code() -> String {
    format!("{:06}", OsRng.gen_range(0..1_000_000u32))
}

/// 32 random bytes, base64url without padding.
pub fn new_token() -> String {
    let mut b = [0u8; 32];
    OsRng.fill_bytes(&mut b);
    URL_SAFE_NO_PAD.encode(b)
}

/// 128-bit random id, hex.
pub fn new_id() -> String {
    let mut b = [0u8; 16];
    OsRng.fill_bytes(&mut b);
    hex::encode(b)
}

/// Deterministic per-email salt for unknown accounts, indistinguishable from a real one.
pub fn fake_salt(secret: &[u8], email: &str) -> [u8; 16] {
    let full = hmac(secret, "salt", email);
    let mut out = [0u8; 16];
    out.copy_from_slice(&full[..16]);
    out
}

/// Constant-time equality (a length mismatch is not secret here).
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.ct_eq(b).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_six_digits() {
        for _ in 0..200 {
            let c = new_code();
            assert_eq!(c.len(), 6);
            assert!(c.chars().all(|x| x.is_ascii_digit()));
        }
    }

    #[test]
    fn tokens_are_unique_and_url_safe() {
        let (a, b) = (new_token(), new_token());
        assert_ne!(a, b);
        assert_eq!(a.len(), 43);
        assert!(a.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    }

    #[test]
    fn hashing_is_deterministic_and_keyed() {
        assert_eq!(hash_secret(b"x"), hash_secret(b"x"));
        assert_ne!(hash_secret(b"x"), hash_secret(b"y"));
        assert_eq!(hmac_code(b"k1", "123456"), hmac_code(b"k1", "123456"));
        assert_ne!(hmac_code(b"k1", "123456"), hmac_code(b"k2", "123456"));
        assert_ne!(hmac_code(b"k1", "123456"), hmac_code(b"k1", "654321"));
    }

    #[test]
    fn fake_salt_is_stable_per_email() {
        assert_eq!(fake_salt(b"k", "a@x.pl"), fake_salt(b"k", "a@x.pl"));
        assert_ne!(fake_salt(b"k", "a@x.pl"), fake_salt(b"k", "b@x.pl"));
        assert_ne!(fake_salt(b"k", "a@x.pl"), fake_salt(b"k2", "a@x.pl"));
    }

    #[test]
    fn ct_eq_compares() {
        assert!(ct_eq(b"abc", b"abc"));
        assert!(!ct_eq(b"abc", b"abd"));
        assert!(!ct_eq(b"abc", b"ab"));
    }
}
