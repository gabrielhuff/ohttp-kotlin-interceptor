package org.chromium.net;

public abstract class UploadDataSink {
    public abstract void onReadSucceeded(boolean finalChunk);
    public abstract void onReadError(Exception exception);
    public abstract void onRewindSucceeded();
    public abstract void onRewindError(Exception exception);
}
