package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Remote buttons mapped on the TV (Launcher settings > Remote buttons), on top of app_keys from config.xml or a
 * theme. Entries use the app_keys format ("scan:419:com.example.app", "83:settings") and are appended after the
 * built-in list, so for the same key they win (RemoteKeys: last entry wins, a scancode beats a keycode). They are
 * kept in their own preferences file, which a theme push does not clear.
 *
 * Learning: while the remap screen waits for a button, the key helpers hand the next learnable key here instead of
 * acting on it, so even buttons the firmware routes elsewhere (global keys) can be captured. Navigation, Back,
 * Home, volume and power are never learnable, so the remote can't be locked out of the launcher.
 */
final class RemoteButtons {

    interface Learner {
        void learned(int keyCode, int scanCode);
    }

    static final String PREFS = "remote_buttons";
    private static final String ENTRIES = "entries";
    private static final String NAME_PREFIX = "name:";

    /** Set while the remap screen waits for a button; called on the main thread, once. */
    private static volatile Learner learner;
    /** Moves whenever a mapping changes, so the helpers re-read their maps on the next key. */
    private static volatile int generation;

    private RemoteButtons() { }

    static void startLearning(Learner l) { learner = l; }

    static void stopLearning() { learner = null; }

    static int generation() { return generation; }

    /** Keys that must keep their normal job: moving around, OK, Back, Home, volume, power. */
    static boolean learnable(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_TV_POWER: case KeyEvent.KEYCODE_UNKNOWN:
                return false;
            default:
                return true;
        }
    }

    /**
     * For the key helpers: true when the remap screen took this key (both its DOWN and UP are consumed, and the
     * screen hears about it on the UP). False passes the key on as usual.
     */
    static boolean learn(KeyEvent event) {
        Learner l = learner;
        if (l == null || !learnable(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            learner = null;
            int key = event.getKeyCode();
            int scan = event.getScanCode();
            new Handler(Looper.getMainLooper()).post(() -> l.learned(key, scan));
        }
        return true;
    }

    /** "scan:419" when the button has a scancode (more specific), else its keycode. */
    static String keySpec(int keyCode, int scanCode) {
        return scanCode > 0 ? "scan:" + scanCode : String.valueOf(keyCode);
    }

    /** The key part of an app_keys entry: "scan:419" or "1561"; null for hold:/front: entries or junk. */
    static String keyOf(String entry) {
        if (entry == null || entry.startsWith("hold:") || entry.startsWith("front:")) return null;
        String[] parts = entry.split(":", 3);
        if (parts[0].equals("scan")) return parts.length == 3 ? "scan:" + parts[1] : null;
        return parts.length >= 2 ? parts[0] : null;
    }

    /** The target part of an app_keys entry (package, action word, inputs or input:<id>). */
    static String targetOf(String entry) {
        String key = keyOf(entry);
        return key == null ? null : entry.substring(key.length() + 1);
    }

    static List<String> saved(Context ctx) {
        String all = prefs(ctx).getString(ENTRIES, "");
        if (all.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String line : all.split("\n")) if (keyOf(line) != null) out.add(line);
        return out;
    }

    /** Maps a button to a target, replacing this screen's earlier mapping for the same key. */
    static void put(Context ctx, String keySpec, String target) {
        List<String> entries = new ArrayList<>(saved(ctx));
        removeKey(entries, keySpec);
        entries.add(keySpec + ":" + target);
        store(ctx, entries);
    }

    /** Drops this screen's mapping for the key; a built-in one (config.xml or theme) applies again. */
    static void remove(Context ctx, String keySpec) {
        List<String> entries = new ArrayList<>(saved(ctx));
        removeKey(entries, keySpec);
        store(ctx, entries);
        prefs(ctx).edit().remove(NAME_PREFIX + keySpec).apply();
    }

    static String name(Context ctx, String keySpec) {
        return prefs(ctx).getString(NAME_PREFIX + keySpec, null);
    }

    static void setName(Context ctx, String keySpec, String name) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (name == null || name.trim().isEmpty()) e.remove(NAME_PREFIX + keySpec);
        else e.putString(NAME_PREFIX + keySpec, name.trim());
        e.apply();
    }

    /** A readable button name when none was given: "Key 1561", "Scancode 419", "Button 3", "Guide". */
    static String defaultLabel(String keySpec) {
        if (keySpec.startsWith("scan:")) return "Scancode " + keySpec.substring(5);
        String name;
        try {
            name = KeyEvent.keyCodeToString(Integer.parseInt(keySpec));
        } catch (NumberFormatException e) {
            name = keySpec;
        }
        if (!name.startsWith("KEYCODE_")) return "Key " + keySpec;
        String words = name.substring(8).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1) + " (" + keySpec + ")";
    }

    private static void removeKey(List<String> entries, String keySpec) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (keySpec.equals(keyOf(entries.get(i)))) entries.remove(i);
        }
    }

    private static void store(Context ctx, List<String> entries) {
        // commit, not apply: the helpers re-read on the very next key press.
        prefs(ctx).edit().putString(ENTRIES, String.join("\n", entries)).commit();
        generation++;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
