// Minimal OHTTP gateway built on chris-wood/ohttp-go (which itself uses
// Cloudflare's circl/hpke). Used solely as a battle-tested reference impl
// for interop testing the Kotlin client.
//
// Lifecycle:
//   1. Generates a fresh X25519 HPKE key.
//   2. Writes "keyConfigHex=<hex>" and "listening=<host>:<port>" to stderr.
//   3. Serves POST /ohttp until killed.
//
// Inner BHTTP requests are dispatched to --origin (scheme+host[:port] only);
// the original path/query/method/headers/body come from the decapsulated
// request.
package main

import (
	"context"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"syscall"
	"time"

	ohttp "github.com/chris-wood/ohttp-go"
	"github.com/cloudflare/circl/hpke"
)

func main() {
	addr := flag.String("addr", "127.0.0.1:0", "listen address")
	origin := flag.String("origin", "", "scheme://host[:port] of the origin to forward inner requests to (path/query come from the BHTTP request)")
	keyIDFlag := flag.Int("key-id", 1, "OHTTP key identifier (0-255)")
	flag.Parse()

	if *origin == "" {
		log.Fatal("missing required --origin flag")
	}
	originURL, err := url.Parse(*origin)
	if err != nil {
		log.Fatalf("invalid --origin: %v", err)
	}

	keyID := uint8(*keyIDFlag)
	priv, err := ohttp.NewConfig(keyID, hpke.KEM_X25519_HKDF_SHA256, hpke.KDF_HKDF_SHA256, hpke.AEAD_AES128GCM)
	if err != nil {
		log.Fatalf("NewConfig: %v", err)
	}
	pub := priv.Config()
	gateway := ohttp.NewDefaultGateway([]ohttp.PrivateConfig{priv})

	listener, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	fmt.Fprintf(os.Stderr, "keyConfigHex=%s\n", hex.EncodeToString(pub.Marshal()))
	fmt.Fprintf(os.Stderr, "listening=%s\n", listener.Addr().String())

	mux := http.NewServeMux()
	mux.HandleFunc("/ohttp", func(w http.ResponseWriter, r *http.Request) {
		handle(w, r, gateway, originURL)
	})
	server := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}

	go func() {
		sigs := make(chan os.Signal, 1)
		signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
		<-sigs
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()

	if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
		log.Fatalf("serve: %v", err)
	}
}

func handle(w http.ResponseWriter, r *http.Request, gateway ohttp.Gateway, originURL *url.URL) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if ct := r.Header.Get("Content-Type"); ct != "message/ohttp-req" {
		http.Error(w, "expected message/ohttp-req", http.StatusUnsupportedMediaType)
		return
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "read body: "+err.Error(), 500)
		return
	}
	encReq, err := ohttp.UnmarshalEncapsulatedRequest(body)
	if err != nil {
		http.Error(w, "unmarshal req: "+err.Error(), 400)
		return
	}
	bhttpBytes, ctx, err := gateway.DecapsulateRequest(encReq)
	if err != nil {
		http.Error(w, "decapsulate: "+err.Error(), 400)
		return
	}
	innerReq, err := ohttp.UnmarshalBinaryRequest(bhttpBytes)
	if err != nil {
		http.Error(w, "bhttp decode: "+err.Error(), 400)
		return
	}
	// Redirect to the local origin override (preserving path/query/body).
	innerReq.URL.Scheme = originURL.Scheme
	innerReq.URL.Host = originURL.Host
	innerReq.Host = originURL.Host
	innerReq.RequestURI = ""

	upstream, err := http.DefaultClient.Do(innerReq)
	if err != nil {
		http.Error(w, "origin: "+err.Error(), 502)
		return
	}
	defer upstream.Body.Close()
	// chris-wood/ohttp-go expects a *http.Response cast to BinaryResponse.
	binResp := ohttp.BinaryResponse(*upstream)
	respBytes, err := binResp.Marshal()
	if err != nil {
		http.Error(w, "bhttp marshal: "+err.Error(), 500)
		return
	}
	encResp, err := ctx.EncapsulateResponse(respBytes)
	if err != nil {
		http.Error(w, "encapsulate: "+err.Error(), 500)
		return
	}
	w.Header().Set("Content-Type", "message/ohttp-res")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(encResp.Marshal())
}
