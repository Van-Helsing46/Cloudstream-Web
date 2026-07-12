package android.util;

import java.nio.charset.StandardCharsets;

// Stub JVM per le estensioni che importano android.util.Base64 (es. VidguardExtractor in
// AnimeWorld): delega a java.util.Base64, che sulla JVM offre la stessa codifica.
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {}

    public static byte[] decode(byte[] input, int flags) {
        java.util.Base64.Decoder decoder = (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlDecoder()
            : java.util.Base64.getMimeDecoder();
        return decoder.decode(input);
    }

    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(StandardCharsets.UTF_8), flags);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encoder(flags).encode(input);
    }

    public static String encodeToString(byte[] input, int flags) {
        return encoder(flags).encodeToString(input);
    }

    private static java.util.Base64.Encoder encoder(int flags) {
        java.util.Base64.Encoder enc = (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlEncoder()
            : java.util.Base64.getEncoder();
        return (flags & NO_PADDING) != 0 ? enc.withoutPadding() : enc;
    }
}
