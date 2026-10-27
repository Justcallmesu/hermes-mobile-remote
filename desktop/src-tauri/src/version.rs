/// The app version from Cargo.toml.
pub fn app_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_is_semver() {
        let v = super::app_version();
        assert_eq!(v.split('.').count(), 3, "{v}");
    }
}
