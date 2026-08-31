package android.content;

// Stub JVM per le estensioni che dichiarano un campo `Context` (quasi sempre un residuo
// dell'app Android, mai realmente usato lato scraping) o che leggono SharedPreferences
// tramite di esso — vedi android.content.SharedPreferences per quel percorso.
public class Context {
    public static final int MODE_PRIVATE = 0;

    public Context getApplicationContext() {
        return this;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return SharedPreferences.forName(name);
    }
}
