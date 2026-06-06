package org.chromium.net;

import java.util.concurrent.Executor;

public abstract class CronetEngine {
    public abstract UrlRequest.Builder newUrlRequestBuilder(String url, UrlRequest.Callback callback, Executor executor);
}
