package com.ptport.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app's own small settings (picture size, the frame counter), in a text file of "name=value" lines.
 *
 * The game runs in its own process (the manifest gives PTActivity android:process=":game"), and Android's SharedPreferences are not
 * shared reliably between two processes: a value written by the menu could be missed by the game, or the other way round. A file
 * that is read on every call and replaced whole on every write has no such problem. The values are few and read rarely.
 */
final class AppPrefs {
    static final String RENDER_HEIGHT = "render_height";
    /** 0 off, 1 the frame rate, 2 the frame rate with the GPU and CPU times. */
    static final String FPS_COUNTER = "fps_counter";
    /** 1 lets the game lower the picture size when it cannot keep its frame rate, and raise it again, up to the chosen size. */
    static final String AUTO_SIZE = "auto_size";

    private static final Object LOCK = new Object();

    private AppPrefs() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "pt-runtime/data/android-app.txt");
    }

    private static Map<String, String> read(Context context) {
        Map<String, String> all = new LinkedHashMap<String, String>();
        File file = file(context);
        if (!file.isFile()) return all;
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] bytes = new byte[(int) Math.min(file.length(), 16384)];
                int got = 0;
                while (got < bytes.length) {
                    int n = in.read(bytes, got, bytes.length - got);
                    if (n < 0) break;
                    got += n;
                }
                for (String line : new String(bytes, 0, got, "UTF-8").split("\r?\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) all.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            } finally {
                in.close();
            }
        } catch (IOException ignored) {
            // an unreadable file is the same as no file: the defaults
        }
        return all;
    }

    static int getInt(Context context, String key, int fallback) {
        synchronized (LOCK) {
            String value = read(context).get(key);
            if (value != null) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            // the picture size was kept in SharedPreferences before the game ran in its own process
            if (key.equals(RENDER_HEIGHT)) {
                try {
                    SharedPreferences old = context.getSharedPreferences("game", Context.MODE_PRIVATE);
                    if (old.contains("render_height")) return Math.max(0, old.getInt("render_height", fallback));
                } catch (RuntimeException ignored) {
                    // the default
                }
            }
            return fallback;
        }
    }

    static boolean getBool(Context context, String key, boolean fallback) {
        return getInt(context, key, fallback ? 1 : 0) != 0;
    }

    static void putInt(Context context, String key, int value) {
        synchronized (LOCK) {
            Map<String, String> all = read(context);
            // the old SharedPreferences value must not come back when a new one is written
            if (!all.containsKey(RENDER_HEIGHT) && !key.equals(RENDER_HEIGHT)) {
                try {
                    SharedPreferences old = context.getSharedPreferences("game", Context.MODE_PRIVATE);
                    if (old.contains("render_height")) all.put(RENDER_HEIGHT, String.valueOf(Math.max(0, old.getInt("render_height", 0))));
                } catch (RuntimeException ignored) {
                    // the default
                }
            }
            all.put(key, String.valueOf(value));
            File file = file(context);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : all.entrySet()) out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            File temporary = new File(dir, "android-app.tmp");
            try {
                FileOutputStream stream = new FileOutputStream(temporary);
                try {
                    stream.write(out.toString().getBytes("UTF-8"));
                } finally {
                    stream.close();
                }
                if (!temporary.renameTo(file)) temporary.delete();
            } catch (IOException ignored) {
                // a setting that could not be kept is asked again next time
            }
        }
    }
}
