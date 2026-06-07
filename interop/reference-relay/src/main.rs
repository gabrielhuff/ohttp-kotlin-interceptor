//! OHTTP reference relay, built on the `payjoin/ohttp-relay` crate. Used as
//! the reference relay binary for interop testing the Kotlin client. The
//! upstream crate has no Martin-Thomson-authored counterpart — this is the
//! closest pure-Rust relay binary built on top of the same hyper/tokio
//! stack.
//!
//! Lifecycle:
//! 1. Picks a free TCP port via a short-lived probe on 127.0.0.1 (avoids the
//!    upstream's `[::]:0` bind, which doesn't work on IPv4-only hosts).
//! 2. Writes "listening=127.0.0.1:<port>" to stderr.
//! 3. Forwards POST `/` requests carrying `message/ohttp-req` to the
//!    `--gateway` URL passed in (expected to be a local HTTP OHTTP gateway).

use std::str::FromStr;

use clap::Parser;
use ohttp_relay::{listen_tcp, GatewayUri};
use tokio::net::TcpListener;

#[derive(Parser, Debug)]
#[command(name = "ohttp-reference-relay")]
struct Args {
    /// Gateway URI to forward encapsulated requests to. Use a plain `http://`
    /// URI for local interop testing.
    #[arg(long)]
    gateway: String,
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    tracing_subscriber::fmt::init();
    rustls::crypto::ring::default_provider()
        .install_default()
        .expect("install default crypto provider");

    let args = Args::parse();
    let gateway = GatewayUri::from_str(&args.gateway)?;

    // Probe a free IPv4 port: bind 127.0.0.1:0, read the assigned port, drop.
    // The upstream `listen_tcp_on_free_port` is hardcoded to `[::]:0`, which
    // fails on hosts without IPv6 (like our CI sandbox).
    let port = {
        let probe = TcpListener::bind("127.0.0.1:0").await?;
        let p = probe.local_addr()?.port();
        drop(probe);
        p
    };

    eprintln!("listening=127.0.0.1:{port}");
    let handle = listen_tcp(port, gateway).await?;
    handle.await??;
    Ok(())
}
