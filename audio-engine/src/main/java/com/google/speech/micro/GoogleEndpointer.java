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

    private long nativePointer;

    public GoogleEndpointer(GoogleEndpointerData endpointerData) {
        if (endpointerData == null || endpointerData.getNativePointer() == 0) {
            throw new IllegalArgumentException("Invalid GoogleEndpointerData");
        }
        this.nativePointer = nativeNew(endpointerData.getNativePointer());
        if (this.nativePointer == 0) {
            throw new IllegalStateException("Failed to create native GoogleEndpointer");
        }
    }

    public GoogleEndpointerResult process(byte[] audioBytes, int offset, int length) {
        if (nativePointer == 0) return null;
        return nativeProcess(nativePointer, audioBytes, offset, length);
    }

    public void reset() {
        if (nativePointer != 0) {
            nativeReset(nativePointer);
        }
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

    private static native long nativeNew(long endpointerDataPointer);
    private static native void nativeClose(long nativePointer);
    private static native GoogleEndpointerResult nativeProcess(long nativePointer, byte[] audioBytes, int offset, int length);
    private static native void nativeReset(long nativePointer);
}
