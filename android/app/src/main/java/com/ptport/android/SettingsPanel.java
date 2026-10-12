package com.ptport.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The settings, each as a row of choices in the look of the title screen (Share Tech letters, off-white words, a thin rule under each
 * heading, a glowing outline on the chosen choice): the speed (picture size, frame rate, v-sync), the upscaler, the lighting, the textures
 * and the effects, the menu's sound, and four presets that set most of them at once. It is shown over the game by the touch overlay's SET
 * button (PTActivity) and by the main menu's Settings (SettingsActivity).
 *
 * A game setting is passed to the game as a "name=number" line in android-settings.txt of its data folder; the game takes the file
 * about twice a second while it runs, applies each line as a tap on the same row of its own settings page would, and writes pt.ini.
 * Without a running game the file waits for the next start. The picture size, the frame rate hint, the frame counter, the
 * automatic picture size and the menu sound are the app's own (PTActivity, AppPrefs).
 */
final class SettingsPanel {
    private static final String PICTURE = "picture";
    private static final String FPS = "fps_limit";
    private static final String COUNTER = AppPrefs.FPS_COUNTER;
    private static final String AUTO = AppPrefs.AUTO_SIZE;
    private static final String MENU_SOUND = AppPrefs.MENU_SOUND;

    private static final class Setting {
        final String key;
        final String title;
        final String note;
        final String[] labels;
        final int[] values;
        final List<Button> buttons = new ArrayList<Button>();

        Setting(String key, String title, String note, String[] labels, int[] values) {
            this.key = key;
            this.title = title;
            this.note = note;
            this.labels = labels;
            this.values = values;
        }
    }

    /** What a preset sets: the picture's height in lines, the frame rate and the game settings (name, number). */
    private static final class Preset {
        final String name;
        final String note;
        final int height;
        final int fps;
        final String[] pairs;

        Preset(String name, String note, int height, int fps, String... pairs) {
            this.name = name;
            this.note = note;
            this.height = height;
            this.fps = fps;
            this.pairs = pairs;
        }
    }

    private static final String[] OFF_ON = { "Off", "On" };
    private static final int[] ZERO_ONE = { 0, 1 };

    private static final Preset[] PRESETS = {
        new Preset("Fastest", "540 lines, 60 frames a second, no shadows and no effects", 540, 60,
                "shadows=0", "ambient_occlusion=0", "reflections=0", "bloom=0", "depth_of_field=0", "motion_blur=0",
                "lens_distortion=0", "lens_ghosts=0", "film_grain=0", "texture_detail=0", "anisotropy=0", "clarity=0"),
        new Preset("Fast", "720 lines, held at 45 frames a second, light shadows only", 720, 45,
                "shadows=1", "ambient_occlusion=0", "reflections=0", "bloom=0", "depth_of_field=0", "motion_blur=0",
                "lens_distortion=0", "lens_ghosts=0", "film_grain=1", "texture_detail=0", "anisotropy=0", "clarity=0"),
        new Preset("Balanced", "900 lines, held at 30 frames a second, medium shadows and the main effects", 900, 30,
                "shadows=2", "ambient_occlusion=0", "reflections=0", "bloom=1", "depth_of_field=0", "motion_blur=0",
                "lens_distortion=1", "lens_ghosts=1", "film_grain=2", "texture_detail=1", "anisotropy=2", "clarity=0"),
        new Preset("Original", "1080 lines, no limit, everything as on the PS4", 1080, 0,
                "shadows=3", "ambient_occlusion=1", "reflections=1", "bloom=1", "depth_of_field=1", "motion_blur=1",
                "lens_distortion=1", "lens_ghosts=1", "film_grain=4", "texture_detail=1", "anisotropy=0", "clarity=0"),
    };

    private final Context context;
    private final Typeface font;
    private final Typeface mono;
    private final List<Setting> settings = new ArrayList<Setting>();
    private final Map<String, Integer> values = new LinkedHashMap<String, Integer>();

    SettingsPanel(Context context) {
        this.context = context;
        this.font = MenuStyle.font(context);
        this.mono = MenuStyle.mono(context);
        settings.add(new Setting(PICTURE, "Picture size",
                "How many lines the game draws; Android scales it to the screen. Fewer lines run faster and look softer.",
                new String[] { "Screen", "1440", "1080", "900", "720", "540", "480" }, new int[] { 0, 1440, 1080, 900, 720, 540, 480 }));
        settings.add(new Setting(AUTO, "Automatic picture size",
                "On: when the game cannot keep its frame rate, it lowers the picture size by a step, and raises it again when there is room, never above "
                        + "the size chosen above. Each change pauses the picture for a moment.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("upscaler", "Upscaler",
                "Draws the game at a smaller size and rebuilds a sharp picture from several frames, so it can run faster at the same Picture size. "
                        + "Experimental.",
                new String[] { "Off", "Built-in" }, new int[] { 0, 1 }));
        settings.add(new Setting("upscale_quality", "Upscaler quality",
                "How small the game draws before the upscaler rebuilds the picture: Quality is 67% of each side, Performance 50%. Smaller is faster and softer. "
                        + "Native keeps the full size and only smooths the edges.",
                new String[] { "Native", "Quality", "Balanced", "Performance", "Ultra" }, new int[] { 0, 1, 2, 3, 4 }));
        settings.add(new Setting("upscale_sharpness", "Upscaler sharpness", "Sharpens the upscaled picture. Off leaves it as the upscaler made it.",
                new String[] { "Off", "20%", "40%", "60%", "80%", "100%" }, new int[] { 0, 2, 4, 6, 8, 10 }));
        settings.add(new Setting(FPS, "Frame rate",
                "The most frames a second the game draws. A steady lower rate looks smoother than a rate that rises and falls; the screen is asked to match it.",
                new String[] { "No limit", "30", "40", "45", "60", "90", "120" }, new int[] { 0, 30, 40, 45, 60, 90, 120 }));
        settings.add(new Setting(COUNTER, "Frame counter",
                "Shows the frame rate in the corner of the game. Detailed adds the GPU and CPU time of a frame and the picture size.",
                new String[] { "Off", "Frame rate", "Detailed" }, new int[] { 0, 1, 2 }));
        settings.add(new Setting(MENU_SOUND, "Menu sound",
                "The buzz of the flickering light, the creaks, the footsteps and the low music in the main menu. Off keeps the menu silent.",
                OFF_ON, ZERO_ONE));
        settings.add(new Setting("vsync", "V-sync", "Waits for the screen's refresh. Off can tear but lets the game run as fast as it can.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("shadows", "Shadows", "The detail of the shadow maps. Off is the fastest.",
                new String[] { "Off", "Low", "Medium", "High" }, new int[] { 0, 1, 2, 3 }));
        settings.add(new Setting("ambient_occlusion", "Ambient occlusion", "Darkens corners and creases.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("reflections", "Reflections", "The reflections in the floor.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("texture_detail", "Texture detail", "Reduced uses blurrier textures at a distance; Fine keeps more detail.",
                new String[] { "Reduced", "Original", "Fine" }, new int[] { 0, 1, 2 }));
        settings.add(new Setting("anisotropy", "Anisotropic filtering", "Sharper floors and walls seen at a slant.",
                new String[] { "Off", "2x", "4x", "8x", "16x" }, new int[] { 0, 1, 2, 3, 4 }));
        settings.add(new Setting("bloom", "Bloom", "The glow around lights.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("depth_of_field", "Depth of field", "Blurs what is out of focus.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("motion_blur", "Motion blur", "Blur when turning and moving.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("lens_distortion", "Lens distortion", "The lens's curve and colour fringes at the edges.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("lens_ghosts", "Lens flare ghosts", "The large reflections across the screen near a lamp.", OFF_ON, ZERO_ONE));
        settings.add(new Setting("film_grain", "Film grain", "The strength of the grain over the picture.",
                new String[] { "Off", "25%", "50%", "75%", "100%" }, new int[] { 0, 1, 2, 3, 4 }));
        settings.add(new Setting("clarity", "Clarity", "A little sharpening of the picture; Off keeps it as it was.",
                new String[] { "Off", "30%", "50%", "70%", "100%" }, new int[] { 0, 3, 5, 7, 10 }));
        loadCurrent();
    }

    // ---- the current values ----

    private File dataDir() {
        return new File(context.getFilesDir(), "pt-runtime/data");
    }

    private File pendingFile() {
        return new File(dataDir(), "android-settings.txt");
    }

    private static String text(File file, int limit) {
        try {
            RandomAccessFile in = new RandomAccessFile(file, "r");
            try {
                long size = in.length();
                long start = Math.max(0, size - limit);
                byte[] bytes = new byte[(int) (size - start)];
                in.seek(start);
                in.readFully(bytes);
                return new String(bytes, "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** pt.ini as "section.name" to its text. */
    private Map<String, String> readIni() {
        Map<String, String> ini = new LinkedHashMap<String, String>();
        File file = new File(dataDir(), "pt.ini");
        if (!file.isFile()) return ini;
        String section = "";
        for (String line : text(file, 65536).split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length() - 1).trim();
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            ini.put(section + "." + trimmed.substring(0, eq).trim(), value);
        }
        return ini;
    }

    private static int number(Map<String, String> ini, String key, int fallback) {
        String value = ini.get(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float decimal(Map<String, String> ini, String key, float fallback) {
        String value = ini.get(key);
        if (value == null) return fallback;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int flag(Map<String, String> ini, String key, boolean fallback) {
        String value = ini.get(key);
        if (value == null) return fallback ? 1 : 0;
        String v = value.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on") ? 1 : 0;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    /** What the settings are now: pt.ini (the game writes it at every change), then what waits in the file for the game to take. */
    private void loadCurrent() {
        Map<String, String> ini = readIni();
        // without a pt.ini the game has not run yet and starts on its fast preset
        boolean fresh = ini.isEmpty();
        values.put(PICTURE, PTActivity.renderHeight(context));
        values.put(AUTO, AppPrefs.getInt(context, AUTO, 0) != 0 ? 1 : 0);
        values.put(COUNTER, clamp(AppPrefs.getInt(context, COUNTER, 0), 0, 2));
        values.put(MENU_SOUND, AppPrefs.getInt(context, MENU_SOUND, 1) != 0 ? 1 : 0);
        values.put(FPS, number(ini, "display.fps_limit", 0));
        String upscaler = ini.get("upscaling.upscaler");
        values.put("upscaler", upscaler != null && upscaler.trim().equalsIgnoreCase("builtin") ? 1 : 0);
        String quality = ini.get("upscaling.quality");
        String q = quality == null ? "quality" : quality.trim().toLowerCase();
        values.put("upscale_quality", q.equals("native") ? 0 : q.equals("balanced") ? 2 : q.equals("performance") ? 3 : q.equals("ultra_performance") ? 4 : 1);
        values.put("upscale_sharpness", clamp(Math.round(decimal(ini, "upscaling.sharpness", 0.0f) * 10.0f), 0, 10));
        values.put("vsync", flag(ini, "display.vsync", true));
        values.put("shadows", clamp(number(ini, "graphics.shadow_quality", fresh ? 1 : 3), 0, 3));
        values.put("ambient_occlusion", flag(ini, "graphics.ambient_occlusion", !fresh));
        values.put("reflections", flag(ini, "graphics.reflections", !fresh));
        values.put("texture_detail", clamp(number(ini, "graphics.texture_detail", fresh ? 0 : 1), 0, 2));
        int aniso = number(ini, "graphics.anisotropy", 0);
        values.put("anisotropy", aniso >= 16 ? 4 : aniso >= 8 ? 3 : aniso >= 4 ? 2 : aniso >= 2 ? 1 : 0);
        values.put("bloom", flag(ini, "graphics.bloom", !fresh));
        values.put("depth_of_field", flag(ini, "graphics.depth_of_field", !fresh));
        values.put("motion_blur", flag(ini, "graphics.motion_blur", !fresh));
        values.put("lens_distortion", flag(ini, "graphics.lens_distortion", !fresh));
        values.put("lens_ghosts", flag(ini, "graphics.lens_ghosts", !fresh));
        values.put("film_grain", clamp(Math.round(decimal(ini, "graphics.film_grain", fresh ? 0.25f : 1.0f) * 4.0f), 0, 4));
        values.put("clarity", clamp(Math.round(decimal(ini, "graphics.clarity", 0.0f) * 10.0f), 0, 10));
        File pending = pendingFile();
        if (pending.isFile()) {
            for (Map.Entry<String, Integer> change : parsePending(text(pending, 8192)).entrySet()) {
                values.put(change.getKey(), change.getValue());
            }
        }
    }

    private static Map<String, Integer> parsePending(String content) {
        Map<String, Integer> changes = new LinkedHashMap<String, Integer>();
        for (String line : content.split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String name = line.substring(0, eq).trim();
            // the upscaler's test settings of earlier builds are gone: a line left over from one is dropped
            if (name.equals("upscale_method") || name.equals("upscale_debug") || name.equals("upscale_path")) continue;
            try {
                changes.put(name, Integer.parseInt(line.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // a line that is not a number is left out
            }
        }
        return changes;
    }

    // ---- changing them ----

    /** Adds the changes to the file the game takes from (what waits there stays); written whole, then moved into place. */
    private boolean writeChanges(Map<String, Integer> changes) {
        try {
            File dir = dataDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File file = pendingFile();
            Map<String, Integer> all = file.isFile() ? parsePending(text(file, 8192)) : new LinkedHashMap<String, Integer>();
            all.putAll(changes);
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Integer> entry : all.entrySet()) out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            File temporary = new File(dir, "android-settings.tmp");
            FileOutputStream stream = new FileOutputStream(temporary);
            try {
                stream.write(out.toString().getBytes("UTF-8"));
            } finally {
                stream.close();
            }
            return temporary.renameTo(file);
        } catch (IOException e) {
            return false;
        }
    }

    private void choose(Setting setting, int value) {
        values.put(setting.key, value);
        if (setting.key.equals(PICTURE)) {
            PTActivity.setRenderHeight(context, value);
            PTActivity.applyPictureSize(context);
        } else if (setting.key.equals(AUTO) || setting.key.equals(COUNTER)) {
            AppPrefs.putInt(context, setting.key, value);
            PTActivity.applyOverlay(context);
        } else if (setting.key.equals(MENU_SOUND)) {
            // only the main menu reads it, when it comes back to the screen
            AppPrefs.putInt(context, setting.key, value);
        } else {
            Map<String, Integer> change = new LinkedHashMap<String, Integer>();
            change.put(setting.key, value);
            writeChanges(change);
            if (setting.key.equals(FPS)) PTActivity.applyFrameRate(context);
        }
        showValues();
    }

    private void applyPreset(Preset preset) {
        Map<String, Integer> change = new LinkedHashMap<String, Integer>();
        for (String pair : preset.pairs) {
            int eq = pair.indexOf('=');
            change.put(pair.substring(0, eq), Integer.parseInt(pair.substring(eq + 1)));
        }
        change.put(FPS, preset.fps);
        writeChanges(change);
        values.putAll(change);
        values.put(PICTURE, preset.height);
        PTActivity.setRenderHeight(context, preset.height);
        PTActivity.applyPictureSize(context);
        PTActivity.applyFrameRate(context);
        showValues();
    }

    // ---- the view ----

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, int size, int color, Typeface face, float spacing) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setLetterSpacing(spacing);
        return view;
    }

    private Button choiceButton(String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTypeface(font);
        button.setLetterSpacing(0.06f);
        button.setTextSize(12.5f);
        button.setSingleLine(true);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        return button;
    }

    private GradientDrawable shape(int fill, int stroke, int strokeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(1));
        shape.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return shape;
    }

    /** The chosen choice has a bright outline and glowing white letters; the others are dim; one touched (or reached with the pad) lights up. */
    private void paint(Button button, boolean selected) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable touched = shape(0x55FFF4DC, 0xFFE9E5D8, 1);
        states.addState(new int[] { android.R.attr.state_pressed }, touched);
        states.addState(new int[] { android.R.attr.state_focused }, touched);
        states.addState(new int[] {}, selected ? shape(0x38FFF4DC, 0xFFE9E5D8, 1) : shape(0x99060504, 0x50FFF4DC, 1));
        button.setBackground(states);
        button.setTextColor(selected ? 0xFFFFFFFF : MenuStyle.INK);
        button.setShadowLayer(selected ? dp(6) : 0f, 0f, 0f, MenuStyle.GLOW);
    }

    private LinearLayout buttonRow(int height) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        row.setMinimumHeight(height);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    /** A heading in capitals with the thin rule under it. */
    private void heading(LinearLayout column, String title) {
        TextView view = label(title.toUpperCase(Locale.US), 14, MenuStyle.AMBER, font, 0.3f);
        view.setShadowLayer(dp(6), 0f, 0f, MenuStyle.GLOW);
        view.setPadding(0, dp(26), 0, dp(6));
        column.addView(view);
        column.addView(MenuStyle.rule(context), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
    }

    private void settingRow(LinearLayout column, final Setting setting) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);
        box.addView(label(setting.title, 16, MenuStyle.INK, font, 0.08f));
        TextView note = label(setting.note, 11, MenuStyle.DIM, mono, 0.03f);
        note.setPadding(0, dp(2), 0, 0);
        box.addView(note);
        LinearLayout row = buttonRow(dp(42));
        for (int i = 0; i < setting.labels.length; ++i) {
            final int value = setting.values[i];
            Button button = choiceButton(setting.labels[i]);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    choose(setting, value);
                }
            });
            setting.buttons.add(button);
            row.addView(button, weighted());
        }
        box.addView(row);
        column.addView(box);
    }

    private void showValues() {
        for (Setting setting : settings) {
            Integer current = values.get(setting.key);
            for (int i = 0; i < setting.buttons.size(); ++i) {
                paint(setting.buttons.get(i), current != null && current == setting.values[i]);
            }
        }
    }

    private Setting find(String key) {
        for (Setting setting : settings) {
            if (setting.key.equals(key)) return setting;
        }
        throw new IllegalArgumentException(key);
    }

    /** The settings' rows in a column; the host puts it in a scroll view. */
    View createContent() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        heading(column, "Presets");
        TextView about = label("Each sets the picture size, frame rate and the options below at once; change single ones after.", 11, MenuStyle.DIM, mono, 0.03f);
        about.setPadding(0, dp(10), 0, 0);
        column.addView(about);
        LinearLayout presets = buttonRow(dp(42));
        for (final Preset preset : PRESETS) {
            Button button = choiceButton(preset.name);
            paint(button, false);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    applyPreset(preset);
                }
            });
            presets.addView(button, weighted());
        }
        column.addView(presets);
        for (Preset preset : PRESETS) {
            TextView note = label(preset.name + ": " + preset.note, 11, MenuStyle.FAINT, mono, 0.03f);
            note.setPadding(0, dp(3), 0, 0);
            column.addView(note);
        }

        heading(column, "Speed");
        settingRow(column, find(PICTURE));
        settingRow(column, find(AUTO));
        settingRow(column, find(FPS));
        settingRow(column, find(COUNTER));
        settingRow(column, find("vsync"));
        heading(column, "Upscaler");
        settingRow(column, find("upscaler"));
        settingRow(column, find("upscale_quality"));
        settingRow(column, find("upscale_sharpness"));
        heading(column, "Lighting");
        settingRow(column, find("shadows"));
        settingRow(column, find("ambient_occlusion"));
        settingRow(column, find("reflections"));
        heading(column, "Textures");
        settingRow(column, find("texture_detail"));
        settingRow(column, find("anisotropy"));
        heading(column, "Effects");
        settingRow(column, find("bloom"));
        settingRow(column, find("depth_of_field"));
        settingRow(column, find("motion_blur"));
        settingRow(column, find("lens_distortion"));
        settingRow(column, find("lens_ghosts"));
        settingRow(column, find("film_grain"));
        settingRow(column, find("clarity"));
        heading(column, "Main menu");
        settingRow(column, find(MENU_SOUND));
        TextView end = label("Changes act at once in a running game and are kept. The picture size also changes at once; if the picture "
                + "ever looks wrong after changing it, close the game and start it again.", 11, MenuStyle.FAINT, mono, 0.03f);
        end.setPadding(0, dp(22), 0, dp(28));
        column.addView(end);

        showValues();
        return column;
    }
}
