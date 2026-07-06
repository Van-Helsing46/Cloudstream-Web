package android.util;
// Stub JVM no-op per l'unica dipendenza Android del provider Arte.
public final class Log {
    public static int v(String t, String m){ System.out.println("[V/"+t+"] "+m); return 0; }
    public static int d(String t, String m){ System.out.println("[D/"+t+"] "+m); return 0; }
    public static int i(String t, String m){ System.out.println("[I/"+t+"] "+m); return 0; }
    public static int w(String t, String m){ System.out.println("[W/"+t+"] "+m); return 0; }
    public static int e(String t, String m){ System.out.println("[E/"+t+"] "+m); return 0; }
    public static int e(String t, String m, Throwable tr){ System.out.println("[E/"+t+"] "+m); tr.printStackTrace(); return 0; }
}
