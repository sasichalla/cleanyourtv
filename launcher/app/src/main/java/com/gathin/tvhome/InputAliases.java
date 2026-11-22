package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Names given to inputs on the TV: hold OK on a row in the Inputs menu, Rename. Keyed by the name the row had
 * (a CEC device's own name such as "Playback_1", or a port's label such as "AV" or "HDMI 2"), not by the input id,
 * because Android gives a CEC device a new id whenever it moves to another port or behind a receiver; a rename by
 * name follows the device. Kept in their own preferences file, so a theme push leaves them alone.
 */
final class InputAliases {

    private static final String PREFS = "input_names";

    private InputAliases() { }

    /** The name given to this input, or null when it has none. */
    static String get(Context ctx, String original) {
        if (original == null) return null;
        return prefs(ctx).getString(original, null);
    }

    /** Saves a name; an empty one removes it, so the TV's own name shows again. */
    static void set(Context ctx, String original, String alias) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (alias == null || alias.trim().isEmpty() || alias.trim().equals(original)) e.remove(original);
        else e.putString(original, alias.trim());
        e.apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
