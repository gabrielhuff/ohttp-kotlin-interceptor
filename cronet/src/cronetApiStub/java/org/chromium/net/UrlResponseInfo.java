package org.chromium.net;

import java.util.List;
import java.util.Map;

public abstract class UrlResponseInfo {
    public abstract String getUrl();
    public abstract List<String> getUrlChain();
    public abstract int getHttpStatusCode();
    public abstract String getHttpStatusText();
    public abstract List<Map.Entry<String, String>> getAllHeadersAsList();
    public abstract Map<String, List<String>> getAllHeaders();
    public abstract boolean wasCached();
    public abstract String getNegotiatedProtocol();
    public abstract String getProxyServer();
    public abstract long getReceivedByteCount();
}
