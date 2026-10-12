package com.ptport.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The game's save files, seen from the menu.
 *
 * The game keeps its save in two alternating slot files, PT_Save_Data0 and PT_Save_Data1, in its data folder (save_data.cpp): each
 * write goes to the older slot with a higher sequence number, and a start reads the valid slot with the higher sequence. A file is
 * 0x80 bytes: magic 0x6E69777470 at 0x00, version 3 at 0x08, the floor's name at 0x40, five cleared flags at 0x68, the Game+ tag
 * 'NGP1' at 0x70 with the count of finished games after it, and the sequence number at 0x78.
 *
 * Nothing here runs while the game does: the menu is only on screen after the game's process has ended. Every change that replaces
 * a save first keeps a copy of it as a backup, so a mistaken tap loses nothing.
 */
final class SaveManager {
    private static final String SLOT = "PT_Save_Data";
    private static final int SIZE = 0x80;
    private static final long MAGIC = 0x6E69777470L;
    private static final int VERSION = 3;

    private SaveManager() {
    }

    /** What a save file says. */
    static final class Info {
        File file;
        int slot;
        long sequence;
        String floor = "";
        int cleared;
        boolean gamePlus;
        int finishes;
        long modified;

        /** One line for the screen: how far the player is. */
        String summary() {
            StringBuilder out = new StringBuilder();
            if (floor.isEmpty()) out.append("Start of the game");
            else out.append("Floor ").append(floor);
            out.append("  -  ").append(cleared).append(" of 5 cleared");
            if (gamePlus) out.append("  -  New Game+ ").append(Math.max(1, finishes));
            return out.toString();
        }
    }

    /** A kept copy of a save: a folder of the slot files, named by the time it was made. */
    static final class Backup {
        File dir;
        String label;
        Info info;
        long time;
    }

    static File dataDir(Context context) {
        return new File(context.getFilesDir(), "pt-runtime/data");
    }

    private static File backupsDir(Context context) {
        return new File(dataDir(context), "saves");
    }

    private static File slotFile(File dir, int slot) {
        return new File(dir, SLOT + slot);
    }

    private static long le(byte[] data, int at, int bytes) {
        long value = 0;
        for (int i = bytes - 1; i >= 0; --i) value = (value << 8) | (data[at + i] & 0xFF);
        return value;
    }

    private static byte[] readAll(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] data = new byte[(int) Math.min(file.length(), 4096)];
                int got = 0;
                while (got < data.length) {
                    int n = in.read(data, got, data.length - got);
                    if (n < 0) break;
                    got += n;
                }
                return got == data.length ? data : Arrays.copyOf(data, got);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** True when the bytes are a save of this version: the size, the magic and the version. */
    static boolean valid(byte[] data) {
        return data.length >= SIZE && le(data, 0, 8) == MAGIC && le(data, 8, 4) == VERSION;
    }

    private static Info parse(File file, int slot) {
        if (!file.isFile()) return null;
        byte[] data = readAll(file);
        if (!valid(data)) return null;
        Info info = new Info();
        info.file = file;
        info.slot = slot;
        info.modified = file.lastModified();
        int end = 0;
        while (end < 7 && data[0x40 + end] != 0) end++;
        try {
            info.floor = new String(data, 0x40, end, "US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            info.floor = "";
        }
        for (int i = 0; i < 5; ++i) {
            if (data[0x68 + i] != 0) info.cleared++;
        }
        info.gamePlus = le(data, 0x70, 4) == 0x3150474EL;
        info.finishes = info.gamePlus ? (int) (le(data, 0x74, 4) & 0xFFFF) : 0;
        info.sequence = le(data, 0x78, 4);
        return info;
    }

    /** The save the game would load from this folder: the valid slot with the higher sequence; null with none. */
    private static Info best(File dir) {
        Info best = null;
        for (int slot = 0; slot < 2; ++slot) {
            Info info = parse(slotFile(dir, slot), slot);
            if (info != null && (best == null || info.sequence > best.sequence)) best = info;
        }
        return best;
    }

    /** The game's current save; null when there is none (the game starts a new one). */
    static Info current(Context context) {
        return best(dataDir(context));
    }

    static boolean hasAnyFile(Context context) {
        return slotFile(dataDir(context), 0).exists() || slotFile(dataDir(context), 1).exists();
    }

    // ---- backups ----

    static List<Backup> backups(Context context) {
        List<Backup> list = new ArrayList<Backup>();
        File[] dirs = backupsDir(context).listFiles();
        if (dirs == null) return list;
        SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault());
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            Info info = best(dir);
            if (info == null) continue;
            Backup backup = new Backup();
            backup.dir = dir;
            backup.info = info;
            backup.time = dir.lastModified();
            backup.label = format.format(new Date(backup.time));
            list.add(backup);
        }
        Collections.sort(list, new Comparator<Backup>() {
            @Override
            public int compare(Backup a, Backup b) {
                return a.time < b.time ? 1 : a.time > b.time ? -1 : 0;
            }
        });
        return list;
    }

    private static boolean copy(File from, File to) {
        File temporary = new File(to.getParentFile(), to.getName() + ".tmp");
        try {
            FileInputStream in = new FileInputStream(from);
            try {
                FileOutputStream out = new FileOutputStream(temporary);
                try {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (to.exists() && !to.delete()) return false;
            return temporary.renameTo(to);
        } catch (IOException e) {
            temporary.delete();
            return false;
        }
    }

    /** Keeps a copy of the current save; true when there was nothing to keep or the copy was made. */
    static boolean backup(Context context) {
        File data = dataDir(context);
        if (best(data) == null) return true;
        File root = backupsDir(context);
        if (!root.isDirectory() && !root.mkdirs()) return false;
        String base = "backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(root, base);
        int attempt = 1;
        while (dir.exists()) dir = new File(root, base + "-" + (++attempt));
        if (!dir.mkdirs()) return false;
        boolean copied = false;
        for (int slot = 0; slot < 2; ++slot) {
            File source = slotFile(data, slot);
            if (parse(source, slot) != null) {
                if (!copy(source, slotFile(dir, slot))) return false;
                copied = true;
            }
        }
        return copied;
    }

    /** Puts a backup back as the current save (the current one is kept as a backup first). */
    static boolean restore(Context context, Backup backup) {
        if (!backup(context)) return false;
        File data = dataDir(context);
        if (!data.isDirectory() && !data.mkdirs()) return false;
        for (int slot = 0; slot < 2; ++slot) {
            File source = slotFile(backup.dir, slot);
            File target = slotFile(data, slot);
            if (parse(source, slot) != null) {
                if (!copy(source, target)) return false;
            } else if (target.exists() && !target.delete()) {
                return false;
            }
        }
        return true;
    }

    static boolean deleteBackup(Backup backup) {
        File[] files = backup.dir.listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
        return backup.dir.delete();
    }

    /** Starts the story again: the current save is kept as a backup and both slots are removed. The game's options start fresh too. */
    static boolean newGame(Context context) {
        if (!backup(context)) return false;
        File data = dataDir(context);
        boolean ok = true;
        for (int slot = 0; slot < 2; ++slot) {
            File file = slotFile(data, slot);
            if (file.exists() && !file.delete()) ok = false;
        }
        return ok;
    }

    // ---- files the player moves in and out ----

    /** Reads a picked file whole; null when it is not a save of this game. */
    static byte[] readPicked(Context context, Uri uri) {
        try {
            ContentResolver resolver = context.getContentResolver();
            InputStream in = resolver.openInputStream(uri);
            if (in == null) return null;
            try {
                byte[] data = new byte[SIZE + 8];
                int got = 0;
                while (got < data.length) {
                    int n = in.read(data, got, data.length - got);
                    if (n < 0) break;
                    got += n;
                }
                // a save is exactly its 0x80 bytes: anything larger is some other file
                if (in.read() >= 0) return null;
                byte[] exact = Arrays.copyOf(data, got);
                return valid(exact) && got == SIZE ? exact : null;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Makes a picked save the current one (the current one is kept as a backup first); the other slot is removed. */
    static boolean importSave(Context context, byte[] data) {
        if (!valid(data) || !backup(context)) return false;
        File dir = dataDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File target = slotFile(dir, 0);
        File temporary = new File(dir, SLOT + "0.tmp");
        try {
            FileOutputStream out = new FileOutputStream(temporary);
            try {
                out.write(data, 0, SIZE);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            temporary.delete();
            return false;
        }
        File other = slotFile(dir, 1);
        if (other.exists() && !other.delete()) {
            temporary.delete();
            return false;
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            return false;
        }
        return temporary.renameTo(target);
    }

    /** Writes the current save to a file the player picked. */
    static boolean exportSave(Context context, Uri uri) {
        Info info = current(context);
        if (info == null) return false;
        byte[] data = readAll(info.file);
        if (!valid(data)) return false;
        try {
            OutputStream out = context.getContentResolver().openOutputStream(uri);
            if (out == null) return false;
            try {
                out.write(data, 0, SIZE);
            } finally {
                out.close();
            }
            return true;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
