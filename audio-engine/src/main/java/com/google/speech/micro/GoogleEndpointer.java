package com.google.speech.micro;

public class GoogleEndpointer {
    static {
        System.loadLibrary("google_speech_micro_jni");
    }

    public static class GoogleEndpointerResult {
        public int bytesConsumed;
        public int endpointerEvent;
        public long eventTimestampMs;

        public static final int EVENT_NONE = 0;
        public static final int EVENT_START_OF_SPEECH = 1;
        public static final int EVENT_END_OF_SPEECH = 2;

        public boolean isSpeechStart() {
            return endpointerEvent == EVENT_START_OF_SPEECH;
        }

        public boolean isSpeechEnd() {
            return endpointerEvent == EVENT_END_OF_SPEECH;
        }
    }

    private long nativeEndpointer;
    private GoogleEndpointerData endpointerData;

    public GoogleEndpointer(GoogleEndpointerData data) {
        if (data == null || data.getNativePointer() == 0) {
            throw new IllegalArgumentException("Invalid GoogleEndpointerData");
        }
        this.endpointerData = data;
        this.nativeEndpointer = nativeNew(data);
        if (this.nativeEndpointer == 0) {
            throw new IllegalStateException("Failed to create native GoogleEndpointer");
        }
    }

    public GoogleEndpointerResult process(byte[] audioBytes, int offset, int length) {
        if (nativeEndpointer == 0) return null;
        return nativeProcess(nativeEndpointer, audioBytes, offset, length);
    }

    public void reset() {
        if (nativeEndpointer != 0) {
            nativeReset(nativeEndpointer);
        }
    }

    public void close() {
        if (nativeEndpointer != 0) {
            nativeClose(nativeEndpointer);
            nativeEndpointer = 0;
            endpointerData = null;
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

    private static native long nativeNew(GoogleEndpointerData data);
    private static native void nativeClose(long nativeEndpointer);
    private static native GoogleEndpointerResult nativeProcess(long nativeEndpointer, byte[] audioBytes, int offset, int length);
    private static native void nativeReset(long nativeEndpointer);
}
