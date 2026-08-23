package com.google.speech.micro;

public class GoogleEndpointerData {
    static {
        System.loadLibrary("google_speech_micro_jni");
    }

    private long nativePointer;

    public GoogleEndpointerData(byte[] endpointerDataBytes) {
        if (endpointerDataBytes == null || endpointerDataBytes.length == 0) {
            throw new IllegalArgumentException("endpointerDataBytes must not be null or empty");
        }
        this.nativePointer = nativeNew(endpointerDataBytes);
        if (this.nativePointer == 0) {
            throw new IllegalStateException("Failed to create native GoogleEndpointerData");
        }
    }

    public long getNativePointer() {
        return nativePointer;
    }

    public int getIdealBufferBytes() {
        if (nativePointer == 0) return 0;
        return nativeIdealBufferBytes(nativePointer);
    }

    public String getEndpointerModelId() {
        if (nativePointer == 0) return "";
        return nativeGetEndpointerModelId(nativePointer);
    }

    public void close() {
        if (nativePointer != 0) {
            nativeClose(nativePointer);
            nativePointer = 0;
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

    private static native long nativeNew(byte[] endpointerDataBytes);
    private static native void nativeClose(long nativePointer);
    private static native int nativeIdealBufferBytes(long nativePointer);
    private static native String nativeGetEndpointerModelId(long nativePointer);
}
