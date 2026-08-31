package android.content;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Stub JVM per le estensioni che persistono piccoli dati (token, cookie di sessione) via
// SharedPreferences invece che in memoria — in-process, per-nome-preferenza, senza scrittura
// su disco: sul nostro backend basta sopravvivere alla vita del processo, non a un riavvio
// (le estensioni che ci finiscono dentro ri-derivano comunque quei valori se mancanti).
public final class SharedPreferences {
    private static final Map<String, SharedPreferences> INSTANCES = new ConcurrentHashMap<>();
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    private SharedPreferences() {}

    static SharedPreferences forName(String name) {
        return INSTANCES.computeIfAbsent(name, n -> new SharedPreferences());
    }

    public String getString(String key, String defValue) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    public long getLong(String key, long defValue) {
        Object v = values.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    public int getInt(String key, int defValue) {
        Object v = values.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public Editor edit() {
        return new Editor(this);
    }

    public static final class Editor {
        private final SharedPreferences prefs;

        private Editor(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        public Editor putString(String key, String value) {
            prefs.values.put(key, value);
            return this;
        }

        public Editor putLong(String key, long value) {
            prefs.values.put(key, value);
            return this;
        }

        public Editor putInt(String key, int value) {
            prefs.values.put(key, value);
            return this;
        }

        public Editor putBoolean(String key, boolean value) {
            prefs.values.put(key, value);
            return this;
        }

        public Editor remove(String key) {
            prefs.values.remove(key);
            return this;
        }

        public Editor clear() {
            prefs.values.clear();
            return this;
        }

        public void apply() {
            // No-op: writes already landed synchronously in the backing map.
        }

        public boolean commit() {
            return true;
        }
    }
}
