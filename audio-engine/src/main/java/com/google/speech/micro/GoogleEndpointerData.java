package com.google.speech.micro;

public class GoogleEndpointerData {
    static {
        System.loadLibrary("google_speech_micro_jni");
    }

    private long nativeEndpointerData;

    public GoogleEndpointerData(byte[] endpointerDataBytes) {
        if (endpointerDataBytes == null || endpointerDataBytes.length == 0) {
            throw new IllegalArgumentException("endpointerDataBytes must not be null or empty");
        }
        this.nativeEndpointerData = nativeNew(endpointerDataBytes, endpointerDataBytes.length);
        if (this.nativeEndpointerData == 0) {
            throw new IllegalStateException("Failed to create native GoogleEndpointerData");
        }
    }

    public long getNativePointer() {
        return nativeEndpointerData;
    }

    public int idealBufferBytes() {
        if (nativeEndpointerData == 0) return 0;
        return nativeIdealBufferBytes(nativeEndpointerData);
    }

    public String getEndpointerModelId() {
        if (nativeEndpointerData == 0) return "";
        return nativeGetEndpointerModelId(nativeEndpointerData);
    }

    public void close() {
        if (nativeEndpointerData != 0) {
            nativeClose(nativeEndpointerData);
            nativeEndpointerData = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private static native long nativeNew(byte[] endpointerDataBytes, int length);
    private static native void nativeClose(long nativeEndpointerData);
    private static native int nativeIdealBufferBytes(long nativeEndpointerData);
    private static native String nativeGetEndpointerModelId(long nativeEndpointerData);
}
