package io.github.gabrielhuff.ohttp.testing

import okhttp3.HttpUrl

/**
 * The full in-process OHTTP topology for end-to-end tests: a [gateway], a
 * [relay] pointed at it, and a [keyDistributor] publishing the gateway's current
 * key. Construct it with the backend [originUrl] the proxied requests should be
 * served by; the gateway rewrites decoded requests to that origin.
 *
 * Tests reach the parts directly — e.g. `infra.gateway.rotateKey()`,
 * `infra.relay.url`, `infra.keyDistributor.keyConfigUrl`. The backend origin is
 * the test's own, not owned here. Closing the infra closes all three servers.
 */
internal class InProcessOhttpInfra(originUrl: HttpUrl) : AutoCloseable {

    val gateway: InProcessGateway = InProcessGateway(originUrl = originUrl)
    val relay: InProcessRelay = InProcessRelay(gatewayUrl = gateway.url)
    val keyDistributor: InProcessKeyDistributor = InProcessKeyDistributor(keyConfigBytes = { gateway.keyConfigBytes })

    override fun close() {
        keyDistributor.close()
        relay.close()
        gateway.close()
    }
}
