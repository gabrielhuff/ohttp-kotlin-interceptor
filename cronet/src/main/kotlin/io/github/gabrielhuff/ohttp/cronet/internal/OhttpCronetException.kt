package io.github.gabrielhuff.ohttp.cronet.internal

import org.chromium.net.CronetException

/** Concrete [CronetException] used to wrap OHTTP-side failures (encapsulation, relay, decapsulation). */
internal class OhttpCronetException(message: String, cause: Throwable?) : CronetException(message, cause)
