//! OHTTP reference gateway, built on Martin Thomson's `ohttp` crate (the
//! IETF-author reference implementation, using `rust-hpke` so it has no NSS
//! dependency). Used solely as a battle-tested cross-implementation gateway
//! for interop testing the Kotlin client.
//!
//! Lifecycle:
//! 1. Generates a fresh HPKE keypair.
//! 2. Writes "keyConfigHex=<hex>" and "listening=<host>:<port>" to stderr.
//! 3. Serves POST / requests with `Content-Type: message/ohttp-req`,
//!    decapsulates with the `ohttp` crate, reads the inner BHTTP request
//!    directly, forwards it to the `--origin` URL (path/query/method/
//!    headers/body come from the encapsulated request), encapsulates the
//!    response, and returns it as `message/ohttp-res`.
//! 4. Also answers a BIP77 opt-in probe on
//!    `/.well-known/ohttp-gateway?allowed_purposes` so that
//!    `payjoin/ohttp-relay` (the reference relay we use for interop) will
//!    accept this gateway as a forwarding target.

use std::convert::Infallible;
use std::io::Cursor;
use std::net::SocketAddr;
use std::sync::Arc;

use bhttp::{Message, Mode, StatusCode as BhttpStatus};
use clap::Parser;
use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::{TokioExecutor, TokioIo};
use ohttp::hpke::{Aead, Kdf, Kem};
use ohttp::{KeyConfig, Server as OhttpServer, SymmetricSuite};
use tokio::net::TcpListener;
use tokio::sync::Mutex;
use url::Url;

#[derive(Parser, Debug, Clone)]
#[command(name = "ohttp-reference-gateway")]
struct Args {
    /// Address to bind. `127.0.0.1:0` (the default) picks a free port.
    #[arg(long, default_value = "127.0.0.1:0")]
    addr: SocketAddr,

    /// Origin to forward the decapsulated requests to. The encapsulated
    /// request's scheme/host are replaced with this URL's; the path,
    /// query, method, headers, and body are preserved.
    #[arg(long)]
    origin: Url,

    /// OHTTP key identifier (0-255).
    #[arg(long, default_value_t = 1)]
    key_id: u8,
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    ohttp::init();

    let config = KeyConfig::new(
        args.key_id,
        Kem::X25519Sha256,
        vec![SymmetricSuite::new(Kdf::HkdfSha256, Aead::Aes128Gcm)],
    )?;
    let key_config_bytes = config.encode()?;
    let server = Arc::new(Mutex::new(OhttpServer::new(config)?));

    let listener = TcpListener::bind(args.addr).await?;
    let local_addr = listener.local_addr()?;
    eprintln!("keyConfigHex={}", hex::encode(&key_config_bytes));
    eprintln!("listening={}", local_addr);

    let origin = Arc::new(args.origin);

    loop {
        let (stream, _) = listener.accept().await?;
        let io = TokioIo::new(stream);
        let server = Arc::clone(&server);
        let origin = Arc::clone(&origin);
        tokio::spawn(async move {
            let svc = service_fn(move |req| handle(req, Arc::clone(&server), Arc::clone(&origin)));
            if let Err(e) = http1::Builder::new().serve_connection(io, svc).await {
                eprintln!("connection error: {e:?}");
            }
        });
    }
}

async fn handle(
    req: Request<Incoming>,
    server: Arc<Mutex<OhttpServer>>,
    origin: Arc<Url>,
) -> Result<Response<Full<Bytes>>, Infallible> {
    // BIP77 opt-in probe used by payjoin's ohttp-relay before it will forward
    // anything. We answer it positively so that relay can wire up to this
    // gateway in interop tests; non-payjoin relays just ignore the path.
    if req.method() == Method::GET
        && req.uri().path() == "/.well-known/ohttp-gateway"
        && req
            .uri()
            .query()
            .map(|q| q.contains("allowed_purposes"))
            .unwrap_or(false)
    {
        return Ok(bip77_purposes_response());
    }
    if req.method() != Method::POST {
        return Ok(text_error(StatusCode::METHOD_NOT_ALLOWED, "method not allowed"));
    }
    if !req
        .headers()
        .get(hyper::header::CONTENT_TYPE)
        .map(|v| v == "message/ohttp-req")
        .unwrap_or(false)
    {
        return Ok(text_error(StatusCode::UNSUPPORTED_MEDIA_TYPE, "expected message/ohttp-req"));
    }

    let body_bytes = match req.into_body().collect().await {
        Ok(b) => b.to_bytes(),
        Err(e) => return Ok(text_error(StatusCode::BAD_REQUEST, &format!("read body: {e}"))),
    };

    let (inner, server_response) = {
        let guard = server.lock().await;
        match guard.decapsulate(&body_bytes) {
            Ok(p) => p,
            Err(e) => {
                return Ok(text_error(StatusCode::BAD_REQUEST, &format!("decapsulate: {e:?}")))
            }
        }
    };

    let inner_message = match Message::read_bhttp(&mut Cursor::new(&inner[..])) {
        Ok(m) => m,
        Err(e) => return Ok(text_error(StatusCode::BAD_REQUEST, &format!("bhttp parse: {e:?}"))),
    };

    let upstream_resp = match dispatch_to_origin(&inner_message, &origin).await {
        Ok(r) => r,
        Err(e) => return Ok(text_error(StatusCode::BAD_GATEWAY, &format!("origin fetch: {e}"))),
    };

    let bhttp_status = match BhttpStatus::try_from(upstream_resp.status.as_u16()) {
        Ok(s) => s,
        Err(e) => {
            return Ok(text_error(
                StatusCode::INTERNAL_SERVER_ERROR,
                &format!("bhttp status: {e:?}"),
            ))
        }
    };
    let mut response_msg = Message::response(bhttp_status);
    for (name, value) in &upstream_resp.headers {
        response_msg.put_header(name.as_str(), value.as_bytes());
    }
    response_msg.write_content(&upstream_resp.body);
    let mut response_bhttp = Vec::new();
    if let Err(e) = response_msg.write_bhttp(Mode::KnownLength, &mut response_bhttp) {
        return Ok(text_error(StatusCode::INTERNAL_SERVER_ERROR, &format!("bhttp emit: {e:?}")));
    }
    let enc_response = match server_response.encapsulate(&response_bhttp) {
        Ok(b) => b,
        Err(e) => {
            return Ok(text_error(
                StatusCode::INTERNAL_SERVER_ERROR,
                &format!("encapsulate: {e:?}"),
            ))
        }
    };

    let mut resp = Response::new(Full::new(Bytes::from(enc_response)));
    resp.headers_mut()
        .insert(hyper::header::CONTENT_TYPE, "message/ohttp-res".parse().unwrap());
    Ok(resp)
}

struct UpstreamResponse {
    status: StatusCode,
    headers: Vec<(http::HeaderName, http::HeaderValue)>,
    body: Vec<u8>,
}

async fn dispatch_to_origin(
    inner_message: &Message,
    origin: &Url,
) -> Result<UpstreamResponse, Box<dyn std::error::Error + Send + Sync>> {
    let control = inner_message.control();
    let method_bytes = control
        .method()
        .ok_or("BHTTP message is not a request")?;
    let method = std::str::from_utf8(method_bytes)?.to_owned();
    let path_bytes = control.path().ok_or("BHTTP request has no path")?;
    let path_and_query = std::str::from_utf8(path_bytes)?.to_owned();

    let mut url = origin.as_str().trim_end_matches('/').to_string();
    url.push_str(&path_and_query);

    let mut builder = hyper::Request::builder()
        .method(method.as_str())
        .uri(url);
    for f in inner_message.header().fields() {
        if f.name().eq_ignore_ascii_case(b"host") || f.name().eq_ignore_ascii_case(b"content-length") {
            continue;
        }
        builder = builder.header(f.name(), f.value());
    }
    let body = inner_message.content().to_vec();
    let req = builder.body(Full::new(Bytes::from(body)))?;

    let connector = hyper_util::client::legacy::connect::HttpConnector::new();
    let client: hyper_util::client::legacy::Client<_, Full<Bytes>> =
        hyper_util::client::legacy::Client::builder(TokioExecutor::new()).build(connector);
    let resp = client.request(req).await?;

    let (parts, body) = resp.into_parts();
    let body_bytes = body.collect().await?.to_bytes().to_vec();
    let headers: Vec<_> = parts
        .headers
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    Ok(UpstreamResponse {
        status: parts.status,
        headers,
        body: body_bytes,
    })
}

/// ALPN-encoded `application/x-ohttp-allowed-purposes` body advertising the
/// BIP77 magic purpose string. Lets payjoin/ohttp-relay (the reference relay
/// we use for interop) opt this gateway in as a valid forwarding target.
fn bip77_purposes_response() -> Response<Full<Bytes>> {
    const PURPOSE: &[u8] = b"BIP77 454403bb-9f7b-4385-b31f-acd2dae20b7e";
    let mut body = Vec::with_capacity(3 + PURPOSE.len());
    body.extend_from_slice(&1u16.to_be_bytes());
    body.push(PURPOSE.len() as u8);
    body.extend_from_slice(PURPOSE);
    let mut resp = Response::new(Full::new(Bytes::from(body)));
    resp.headers_mut().insert(
        hyper::header::CONTENT_TYPE,
        "application/x-ohttp-allowed-purposes".parse().unwrap(),
    );
    resp
}

fn text_error(code: StatusCode, msg: &str) -> Response<Full<Bytes>> {
    let mut resp = Response::new(Full::new(Bytes::from(msg.to_owned())));
    *resp.status_mut() = code;
    resp.headers_mut()
        .insert(hyper::header::CONTENT_TYPE, "text/plain".parse().unwrap());
    resp
}
