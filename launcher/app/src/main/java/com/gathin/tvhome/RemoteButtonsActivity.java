package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Launcher settings > Remote buttons: map a remote button to an app, a TV input or an action from the TV itself,
 * without a computer. Press "Add or change a button", press the button, pick what it should do. Needs a key helper
 * (InputKeyService or HomeKeyService) bound with key filtering on, since only a helper sees the buttons the
 * firmware routes elsewhere. Mappings are stored by RemoteButtons and win over config.xml and themes.
 */
public final class RemoteButtonsActivity extends Activity {

    private LinearLayout list;
    private AlertDialog waiting;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPaddingRelative(dp(72), dp(38), dp(72), dp(38));
        scroll.addView(column);

        TextView title = text(getString(R.string.remote_buttons), 34, 0xFFFFFFFF);
        column.addView(title);
        TextView help = text(getString(R.string.remote_buttons_help), 14, 0xFFACB8C1);
        help.setPadding(0, dp(7), 0, 0);
        column.addView(help);

        TextView add = row(getString(R.string.remote_buttons_add));
        add.setOnClickListener(v -> startLearning());
        ((LinearLayout.LayoutParams) add.getLayoutParams()).topMargin = dp(28);
        column.addView(add);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(18), 0, 0);
        column.addView(list);

        setContentView(scroll);
        refresh();
        add.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        RemoteButtons.stopLearning();
        if (waiting != null) waiting.dismiss();
    }

    /** One row per button: built-in mappings (config.xml or theme) first, then the ones added here. */
    private void refresh() {
        list.removeAllViews();
        Map<String, String[]> buttons = buttons();
        if (buttons.isEmpty()) {
            list.addView(text(getString(R.string.remote_buttons_none), 16, 0xFFACB8C1));
            return;
        }
        for (Map.Entry<String, String[]> b : buttons.entrySet()) {
            String key = b.getKey();
            String target = b.getValue()[0];
            boolean onTv = b.getValue()[1] != null;
            String label = label(key) + "  →  " + targetLabel(target) + (onTv ? "  •" : "");
            TextView row = row(label);
            ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = dp(8);
            row.setOnClickListener(v -> showButtonMenu(key, onTv));
            list.addView(row);
        }
        TextView legend = text(getString(R.string.remote_buttons_legend), 14, 0xFFACB8C1);
        legend.setPadding(0, dp(12), 0, 0);
        list.addView(legend);
    }

    /** key spec -> {effective target, on-TV target or null}, in display order. */
    private Map<String, String[]> buttons() {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String entry : builtIn()) {
            String key = RemoteButtons.keyOf(entry);
            if (key != null) out.put(key, new String[]{RemoteButtons.targetOf(entry), null});
        }
        for (String entry : RemoteButtons.saved(this)) {
            String key = RemoteButtons.keyOf(entry);
            String target = RemoteButtons.targetOf(entry);
            out.put(key, new String[]{target, target});
        }
        return out;
    }

    /** The app_keys from config.xml or the theme: Config.load appends the on-TV ones last. */
    private List<String> builtIn() {
        List<String> all = Config.load(this).appKeys;
        int own = RemoteButtons.saved(this).size();
        return all.subList(0, Math.max(0, all.size() - own));
    }

    private void startLearning() {
        if (InputKeyService.running == null && HomeKeyService.running == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.remote_buttons)
                    .setMessage(R.string.remote_buttons_no_helper)
                    .setPositiveButton(R.string.remote_buttons_open_accessibility, (d, w) ->
                            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        waiting = new AlertDialog.Builder(this)
                .setTitle(R.string.remote_buttons_press)
                .setMessage(R.string.remote_buttons_press_help)
                .setNegativeButton(android.R.string.cancel, (d, w) -> RemoteButtons.stopLearning())
                .setOnCancelListener(d -> RemoteButtons.stopLearning())
                .show();
        RemoteButtons.startLearning((keyCode, scanCode) -> {
            if (waiting != null) waiting.dismiss();
            waiting = null;
            if (isFinishing()) return;
            String key = existingKey(keyCode, scanCode);
            showTargetPicker(key, label(key));
        });
    }

    /** The spec a built-in or earlier mapping already uses for this button, so a change overrides it in place. */
    private String existingKey(int keyCode, int scanCode) {
        for (String key : buttons().keySet()) {
            if (key.equals("scan:" + scanCode) && scanCode > 0) return key;
            if (key.equals(String.valueOf(keyCode))) return key;
        }
        return RemoteButtons.keySpec(keyCode, scanCode);
    }

    private void showButtonMenu(String key, boolean onTv) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.remote_buttons_change));
        items.add(getString(R.string.remote_buttons_rename));
        if (onTv) items.add(getString(R.string.remote_buttons_reset));
        new AlertDialog.Builder(this)
                .setTitle(label(key))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) showTargetPicker(key, label(key));
                    else if (which == 1) showRename(key);
                    else {
                        RemoteButtons.remove(this, key);
                        changed();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRename(String key) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        String current = RemoteButtons.name(this, key);
        if (current != null) field.setText(current);
        field.setHint(RemoteButtons.defaultLabel(key));
        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_buttons_rename)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    RemoteButtons.setName(this, key, field.getText().toString());
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Do nothing, the actions, the TV's inputs, then every app with a TV entry point by name. */
    private void showTargetPicker(String key, String buttonLabel) {
        List<String[]> targets = new ArrayList<>();   // {label, target}
        targets.add(new String[]{getString(R.string.remote_buttons_target_ignore), "ignore"});
        targets.add(new String[]{getString(R.string.settings), "settings"});
        targets.add(new String[]{getString(R.string.remote_buttons_target_screensaver), "screensaver"});
        targets.add(new String[]{getString(R.string.remote_buttons_target_inputs), RemoteKeys.INPUTS});
        targets.add(new String[]{getString(R.string.remote_buttons_target_home), "home"});
        targets.add(new String[]{getString(R.string.remote_buttons_target_mute), "mute"});
        targets.add(new String[]{getString(R.string.remote_buttons_target_sleep), "sleep"});
        targets.add(new String[]{getString(R.string.remote_buttons_target_wifi), "wifi"});
        for (String[] input : inputs()) targets.add(input);
        targets.addAll(apps());
        String[] labels = new String[targets.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = targets.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.remote_buttons_pick, buttonLabel))
                .setItems(labels, (d, which) -> {
                    RemoteButtons.put(this, key, targets.get(which)[1]);
                    changed();
                    Toast.makeText(this, getString(R.string.remote_buttons_saved,
                            buttonLabel, labels[which]), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The TV's own ports (HDMI, AV...), not the devices behind them, as "input:<id>" targets. */
    private List<String[]> inputs() {
        List<String[]> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return out;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.getParentId() != null || info.isHidden(this)) continue;
                out.add(new String[]{inputLabel(info), "input:" + info.getId()});
            }
        } catch (RuntimeException ignored) {
        }
        Collections.sort(out, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return out;
    }

    private String inputLabel(TvInputInfo info) {
        CharSequence custom = info.loadCustomLabel(this);
        CharSequence label = custom != null && custom.length() > 0 ? custom : info.loadLabel(this);
        return getString(R.string.remote_buttons_target_input, label);
    }

    private List<String[]> apps() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return apps;
    }

    private String label(String key) {
        String name = RemoteButtons.name(this, key);
        return name != null ? name : RemoteButtons.defaultLabel(key);
    }

    /** What a target does, in words: the app's name, the input's name or the action. */
    private String targetLabel(String target) {
        if (target == null) return "";
        switch (target) {
            case "ignore": return getString(R.string.remote_buttons_target_ignore);
            case "settings": return getString(R.string.settings);
            case "screensaver": return getString(R.string.remote_buttons_target_screensaver);
            case RemoteKeys.INPUTS: return getString(R.string.remote_buttons_target_inputs);
            case "home": return getString(R.string.remote_buttons_target_home);
            case "mute": return getString(R.string.remote_buttons_target_mute);
            case "sleep": return getString(R.string.remote_buttons_target_sleep);
            case "wifi": return getString(R.string.remote_buttons_target_wifi);
            default: break;
        }
        if (target.startsWith("input:")) {
            String id = target.substring(6);
            for (String[] input : inputs()) if (input[1].equals(target)) return input[0];
            return getString(R.string.remote_buttons_target_input, id);
        }
        String pkg = target.contains("/") ? target.substring(0, target.indexOf('/')) : target;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return target;
        }
    }

    /** A mapping changed: redraw, and move the settings count so Home re-reads its keys when it resumes. */
    private void changed() {
        android.content.SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .apply();
        setResult(RESULT_OK);
        refresh();
    }

    private TextView row(String label) {
        TextView row = new TextView(this, null, 0, R.style.SettingsRow);
        row.setText(label);
        row.setSingleLine(true);
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        return row;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
