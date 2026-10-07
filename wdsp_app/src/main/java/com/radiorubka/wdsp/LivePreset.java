package com.radiorubka.wdsp;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Preset values the screen has changed and the disk has not stored yet.
 *
 * <h2>Why (owner, 07.10.2026)</h2>
 *
 * The author's 1.0.2 writes a preset to disk 300 ms after the last slider tick instead of on every
 * tick - each write re-serialises the whole preferences file. But the service applies a preset FROM
 * the preferences, so with the write delayed the chip heard a slider only after the finger stopped.
 * The owner: «Чип слідує за пальцем - це ж правильне рішення… Чи можна виправити конфлікт, без
 * регресу?» So the two are split: the screen publishes each tick's values here and the service hears
 * them at once, through the same view it reads the stored preset with; the disk gets the same
 * snapshot once per pause, and then this layer forgets what the disk now holds.
 *
 * <p>Same process only: MainActivity publishes, McuService reads. The values are taken when the
 * slider moves, never when the delayed write fires - a preset loaded in between cannot hand its
 * values to the one that was being edited.
 */
public final class LivePreset {

    /** Told the keys whose value changed, on the publishing thread; it posts its own work. */
    public interface Listener {
        void onLiveKeys(Set<String> keys);
    }

    /** Key to value (Integer, Boolean, ...); never null values. */
    private static final Map<String, Object> pending = new ConcurrentHashMap<>();
    private static volatile Listener listener;

    private LivePreset() {
    }

    public static void setListener(@Nullable Listener l) {
        listener = l;
    }

    /**
     * One tick of the screen: a preset's values as they are now. The keys that differ from what the
     * service would otherwise read reach the listener.
     */
    public static void publish(SharedPreferences stored, Map<String, Object> values) {
        final Map<String, ?> disk = stored.getAll();
        final Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null) continue;
            final Object before = pending.containsKey(e.getKey()) ? pending.get(e.getKey()) : disk.get(e.getKey());
            if (!e.getValue().equals(before)) changed.add(e.getKey());
            pending.put(e.getKey(), e.getValue());
        }
        final Listener l = listener;
        if (l != null && !changed.isEmpty()) l.onLiveKeys(changed);
    }

    /** The disk holds these now: forget each one that still has the same value. */
    public static void stored(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() != null) pending.remove(e.getKey(), e.getValue());
        }
    }

    /** Reads through the values not stored yet, then the store. */
    public static SharedPreferences readView(SharedPreferences base) {
        return base instanceof View ? base : new View(base);
    }

    /**
     * A {@link SharedPreferences.Editor} that only remembers what was put into it: the screen's way
     * of saying "this is the preset now" without touching the disk. Nothing in a preset is removed,
     * so neither is anything here.
     */
    public static final class Snapshot implements SharedPreferences.Editor {
        public final Map<String, Object> values = new LinkedHashMap<>();

        /** Puts everything remembered into a real editor. */
        public void writeTo(SharedPreferences.Editor e) {
            for (Map.Entry<String, Object> v : values.entrySet()) {
                final Object o = v.getValue();
                if (o instanceof Integer) e.putInt(v.getKey(), (Integer) o);
                else if (o instanceof Boolean) e.putBoolean(v.getKey(), (Boolean) o);
                else if (o instanceof Float) e.putFloat(v.getKey(), (Float) o);
                else if (o instanceof Long) e.putLong(v.getKey(), (Long) o);
                else if (o instanceof String) e.putString(v.getKey(), (String) o);
                else if (o instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> s = (Set<String>) o;
                    e.putStringSet(v.getKey(), s);
                }
            }
        }

        @Override
        public SharedPreferences.Editor putString(String key, @Nullable String value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, @Nullable Set<String> value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            throw new UnsupportedOperationException("a preset snapshot only records values");
        }

        @Override
        public SharedPreferences.Editor clear() {
            throw new UnsupportedOperationException("a preset snapshot only records values");
        }

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void apply() {
        }
    }

    private static final class View implements SharedPreferences {
        private final SharedPreferences base;

        View(SharedPreferences base) {
            this.base = base;
        }

        @Override
        public Map<String, ?> getAll() {
            final Map<String, Object> all = new HashMap<>(base.getAll());
            all.putAll(pending);
            return all;
        }

        @Nullable
        @Override
        public String getString(String key, @Nullable String defValue) {
            final Object v = pending.get(key);
            return v instanceof String ? (String) v : base.getString(key, defValue);
        }

        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
            final Object v = pending.get(key);
            return v instanceof Set ? (Set<String>) v : base.getStringSet(key, defValues);
        }

        @Override
        public int getInt(String key, int defValue) {
            final Object v = pending.get(key);
            return v instanceof Integer ? (Integer) v : base.getInt(key, defValue);
        }

        @Override
        public long getLong(String key, long defValue) {
            final Object v = pending.get(key);
            return v instanceof Long ? (Long) v : base.getLong(key, defValue);
        }

        @Override
        public float getFloat(String key, float defValue) {
            final Object v = pending.get(key);
            return v instanceof Float ? (Float) v : base.getFloat(key, defValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            final Object v = pending.get(key);
            return v instanceof Boolean ? (Boolean) v : base.getBoolean(key, defValue);
        }

        @Override
        public boolean contains(String key) {
            return pending.containsKey(key) || base.contains(key);
        }

        @Override
        public Editor edit() {
            return base.edit();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
            base.registerOnSharedPreferenceChangeListener(l);
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
            base.unregisterOnSharedPreferenceChangeListener(l);
        }
    }
}
