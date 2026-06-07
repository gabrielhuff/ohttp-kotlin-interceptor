// Minimal stub of Cronet's public API. NOT shipped with our published artifact;
// consumers must depend on org.chromium.net:cronet-api at runtime. The class
// shapes here mirror the abstract surface we touch — keep in sync with
// upstream Cronet (~119.x and later all match this).
package org.chromium.net;

import java.io.IOException;

public abstract class CronetException extends IOException {
    protected CronetException(String message, Throwable cause) {
        super(message, cause);
    }
}
