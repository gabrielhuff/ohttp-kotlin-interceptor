package org.chromium.net;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

public abstract class UrlRequest {

    public abstract void start();
    public abstract void followRedirect();
    public abstract void read(ByteBuffer buffer);
    public abstract void cancel();
    public abstract boolean isDone();
    public abstract void getStatus(StatusListener listener);

    public abstract static class Builder {
        public static final int REQUEST_PRIORITY_IDLE = 0;
        public static final int REQUEST_PRIORITY_LOWEST = 1;
        public static final int REQUEST_PRIORITY_LOW = 2;
        public static final int REQUEST_PRIORITY_MEDIUM = 3;
        public static final int REQUEST_PRIORITY_HIGHEST = 4;

        public abstract Builder setHttpMethod(String method);
        public abstract Builder addHeader(String header, String value);
        public abstract Builder disableCache();
        public abstract Builder setPriority(int priority);
        public abstract Builder setUploadDataProvider(UploadDataProvider uploadDataProvider, Executor executor);
        public abstract Builder allowDirectExecutor();
        public abstract UrlRequest build();
    }

    public abstract static class Callback {
        public abstract void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) throws Exception;
        public abstract void onResponseStarted(UrlRequest request, UrlResponseInfo info) throws Exception;
        public abstract void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) throws Exception;
        public abstract void onSucceeded(UrlRequest request, UrlResponseInfo info);
        public abstract void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error);
        public void onCanceled(UrlRequest request, UrlResponseInfo info) {}
    }

    public interface StatusListener {
        void onStatus(int status);
    }

    public static class Status {
        public static final int INVALID = -1;
        public static final int IDLE = 0;
        public static final int WAITING_FOR_STALLED_SOCKET_POOL = 1;
        public static final int WAITING_FOR_AVAILABLE_SOCKET = 2;
        public static final int WAITING_FOR_DELEGATE = 3;
        public static final int WAITING_FOR_CACHE = 4;
        public static final int DOWNLOADING_PROXY_SCRIPT = 5;
        public static final int RESOLVING_PROXY_FOR_URL = 6;
        public static final int RESOLVING_HOST_IN_PROXY_SCRIPT = 7;
        public static final int ESTABLISHING_PROXY_TUNNEL = 8;
        public static final int RESOLVING_HOST = 9;
        public static final int CONNECTING = 10;
        public static final int SSL_HANDSHAKE = 11;
        public static final int SENDING_REQUEST = 12;
        public static final int WAITING_FOR_RESPONSE = 13;
        public static final int READING_RESPONSE = 14;
    }
}
