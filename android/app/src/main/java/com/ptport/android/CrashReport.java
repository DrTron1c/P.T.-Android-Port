package com.ptport.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the player can copy when the game closed on its own last time.
 *
 * Android keeps the reason every earlier run of the app ended (ApplicationExitInfo, Android 11 and newer). When the newest
 * one is a crash, a kill for memory or a hang that the player has not been shown yet, the launcher shows a report instead of
 * starting the game again: why the process ended, the native crash's backtrace when there is one, the game's own log
 * (pt.log) and the app's logcat. The player copies or shares it. No computer or adb is needed to get at any of it.
 */
final class CrashReport {
    private static final String PREFS = "crash-report";
    private static final String KEY_SEEN = "seen";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    // the sizes of the parts, so the whole report stays something that can be pasted into a message
    private static final int TOMBSTONE_CHARS = 4500;
    private static final int LOG_HEAD_LINES = 22;
    private static final int LOG_TAIL_LINES = 48;
    private static final int LOG_CHARS = 7000;
    private static final int LOGCAT_LINES = 70;
    private static final int LOGCAT_CHARS = 6500;
    private static final int LINE_CHARS = 220;

    /** The earlier run that ended badly. */
    static final class Pending {
        final long time;
        final ApplicationExitInfo info;

        Pending(ApplicationExitInfo info) {
            this.info = info;
            this.time = info.getTimestamp();
        }
    }

    private CrashReport() {
    }

    /** The newest bad end of an earlier run that has not been shown yet; null when the last runs ended normally. */
    static Pending find(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            long seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0);
            long installed = 0;
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                installed = info.lastUpdateTime;
            } catch (Exception ignored) {
            }
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            // newest first
            for (ApplicationExitInfo info : exits) {
                if (info.getPid() == android.os.Process.myPid()) continue;
                // an end from before this version was installed is the update itself closing the app
                if (info.getTimestamp() <= seen || info.getTimestamp() <= installed) break;
                if (isBad(info)) return new Pending(info);
            }
        } catch (Throwable ignored) {
            // no report is better than a launcher that cannot start
        }
        return null;
    }

    /** The report has been shown and dealt with: this end (and every older one) is not offered again. */
    static void markSeen(Context context, long time) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (time > prefs.getLong(KEY_SEEN, 0)) prefs.edit().putLong(KEY_SEEN, time).apply();
    }

    private static boolean isBad(ApplicationExitInfo info) {
        switch (info.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return true;
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return info.getStatus() != 0;
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case ApplicationExitInfo.REASON_SIGNALED:
                // Android clears an app out of the background all the time: only an end while the player was using it counts
                return info.getImportance() < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
            default:
                return false;
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the report

    /** Builds the whole report text (reads files and runs logcat: not for the main thread) and keeps a copy in the app's files. */
    static String build(Context context, Pending pending) {
        StringBuilder out = new StringBuilder(16384);
        ApplicationExitInfo info = pending.info;
        out.append("P.T. PC Port crash report\n");
        try {
            header(context, out);
        } catch (Throwable t) {
            out.append("(header: ").append(t).append(")\n");
        }
        try {
            exitInfo(info, out);
        } catch (Throwable t) {
            out.append("(exit info: ").append(t).append(")\n");
        }
        try {
            gameFiles(context, out);
        } catch (Throwable t) {
            out.append("(game files: ").append(t).append(")\n");
        }
        try {
            out.append(frameRate(context));
        } catch (Throwable t) {
            out.append("(frame rate: ").append(t).append(")\n");
        }
        try {
            out.append(problems(context));
        } catch (Throwable t) {
            out.append("(problems: ").append(t).append(")\n");
        }
        try {
            out.append(keyLines(context));
        } catch (Throwable t) {
            out.append("(key lines: ").append(t).append(")\n");
        }
        try {
            String tombstone = tombstone(info);
            if (!tombstone.isEmpty()) out.append("\n== native crash ==\n").append(tombstone);
        } catch (Throwable t) {
            out.append("\n== native crash ==\n(could not read it: ").append(t).append(")\n");
        }
        try {
            String log = gameLog(context);
            out.append("\n== pt.log (the game's own log) ==\n").append(log.isEmpty() ? "(none)\n" : log);
        } catch (Throwable t) {
            out.append("\n== pt.log ==\n(could not read it: ").append(t).append(")\n");
        }
        try {
            String logcat = logcat(info.getPid());
            out.append("\n== logcat ==\n").append(logcat.isEmpty() ? "(nothing)\n" : logcat);
        } catch (Throwable t) {
            out.append("\n== logcat ==\n(could not read it: ").append(t).append(")\n");
        }
        String text = out.toString();
        try {
            FileOutputStream file = new FileOutputStream(new File(context.getFilesDir(), "last-crash-report.txt"));
            try {
                file.write(text.getBytes(UTF8));
            } finally {
                file.close();
            }
        } catch (IOException ignored) {
        }
        return text;
    }

    private static void header(Context context, StringBuilder out) throws Exception {
        PackageInfo app = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        out.append("app: ").append(app.versionName).append(" (build ").append(app.getLongVersionCode()).append(")\n");
        out.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        out.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.DISPLAY).append('\n');
        out.append("abi: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
        if (Build.VERSION.SDK_INT >= 31) out.append(", soc: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL);
        out.append('\n');
        FeatureInfo[] features = context.getPackageManager().getSystemAvailableFeatures();
        if (features != null) {
            for (FeatureInfo feature : features) {
                if (feature.name != null && feature.name.startsWith("android.hardware.vulkan")) {
                    out.append(feature.name.substring("android.hardware.".length())).append(": ");
                    if (feature.name.endsWith("version")) {
                        out.append(feature.version >> 22).append('.').append((feature.version >> 12) & 0x3ff)
                                .append(" (0x").append(Integer.toHexString(feature.version)).append(")\n");
                    } else {
                        out.append(feature.version).append('\n');
                    }
                }
            }
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            out.append("memory: ").append(memory.totalMem >> 20).append(" MB total, ").append(memory.availMem >> 20)
                    .append(" MB free now; app heap limit ").append(manager.getMemoryClass()).append(" MB (")
                    .append(manager.getLargeMemoryClass()).append(" large)\n");
        }
        out.append("free storage: ").append(context.getFilesDir().getUsableSpace() >> 20).append(" MB\n");
    }

    private static void exitInfo(ApplicationExitInfo info, StringBuilder out) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        out.append("\n== how the last run ended ==\n");
        out.append("time: ").append(format.format(new Date(info.getTimestamp()))).append('\n');
        out.append("reason: ").append(reasonName(info.getReason())).append(", status ").append(info.getStatus());
        String signal = signalName(info.getStatus());
        if (signal != null && (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                || info.getReason() == ApplicationExitInfo.REASON_SIGNALED)) out.append(" (").append(signal).append(')');
        out.append('\n');
        String description = info.getDescription();
        if (description != null && !description.isEmpty()) out.append("description: ").append(cut(description, 400)).append('\n');
        out.append("process: ").append(info.getProcessName()).append(", pid ").append(info.getPid())
                .append(", importance ").append(info.getImportance()).append('\n');
        out.append("memory then: pss ").append(info.getPss() >> 10).append(" MB, rss ").append(info.getRss() >> 10).append(" MB\n");
    }

    private static void gameFiles(Context context, StringBuilder out) {
        File dir = ImportActivity.gameDir(context);
        out.append("\n== game files ==\n");
        String[] names = { "chunk1.psarc", "texture.qar", "pathid_list_ps4.bin" };
        for (String name : names) {
            File file = new File(dir, name);
            out.append(name).append(": ").append(file.isFile() ? file.length() + " bytes" : "missing").append('\n');
        }
        File source = new File(dir, "source.txt");
        if (source.isFile()) {
            try {
                out.append(cut(new String(readAll(source, 1024), UTF8).trim(), 600)).append('\n');
            } catch (IOException ignored) {
            }
        }
    }

    /** The log screen's text: no crash, just how the game is doing (device, settings, frame rate, the log). Not for the main thread. */
    static String buildLog(Context context) {
        StringBuilder out = new StringBuilder(12288);
        out.append("P.T. PC Port log\n");
        try {
            header(context, out);
        } catch (Throwable t) {
            out.append("(header: ").append(t).append(")\n");
        }
        try {
            gameFiles(context, out);
        } catch (Throwable t) {
            out.append("(game files: ").append(t).append(")\n");
        }
        try {
            out.append(settingsSummary(context));
        } catch (Throwable t) {
            out.append("(settings: ").append(t).append(")\n");
        }
        try {
            out.append(frameRate(context));
        } catch (Throwable t) {
            out.append("(frame rate: ").append(t).append(")\n");
        }
        try {
            out.append(problems(context));
        } catch (Throwable t) {
            out.append("(problems: ").append(t).append(")\n");
        }
        try {
            out.append(keyLines(context));
        } catch (Throwable t) {
            out.append("(key lines: ").append(t).append(")\n");
        }
        try {
            String log = gameLog(context);
            out.append("\n== pt.log (the game's own log) ==\n").append(log.isEmpty() ? "(none yet: the game has not been started)\n" : log);
        } catch (Throwable t) {
            out.append("\n== pt.log ==\n(could not read it: ").append(t).append(")\n");
        }
        return out.toString();
    }

    /** The graphics-related part of pt.ini, without its comments. */
    private static String settingsSummary(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "pt-runtime/data/pt.ini");
        if (!file.isFile()) return "";
        StringBuilder out = new StringBuilder("\n== settings (pt.ini) ==\n");
        String section = "";
        for (String line : new String(readAll(file, 65536), UTF8).split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("[")) {
                section = trimmed;
                continue;
            }
            if (section.equals("[display]") || section.equals("[upscaling]") || section.equals("[graphics]")
                    || section.equals("[raytracing]")) {
                out.append(section.substring(1, section.length() - 1)).append('.').append(trimmed).append('\n');
            }
        }
        File preset = new File(context.getFilesDir(), "pt-runtime/data/android-defaults-1");
        if (preset.isFile()) out.append("android preset applied: ").append(new String(readAll(preset, 64), UTF8).trim()).append('\n');
        return out.toString();
    }

    /** The lines of pt.log that say what the renderer and the stage did: the picture's size, the exposure, how many lights and draws. */
    private static String keyLines(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "pt-runtime/data/pt.log");
        if (!file.isFile()) return "";
        RandomAccessFile in = new RandomAccessFile(file, "r");
        String text;
        try {
            long size = in.length();
            long start = Math.max(0, size - 1048576);
            byte[] bytes = new byte[(int) (size - start)];
            in.seek(start);
            in.readFully(bytes);
            text = new String(bytes, UTF8);
        } finally {
            in.close();
        }
        // the renderer's own lines first (they are few, and say what the picture is made of); the stage's lines flood the log, so
        // only the last few of those
        String[] renderer = { "exposure", "luminance", "vulkan: swapchain", "scene renderer", "shadow atlas", "preset", "upscale", "renderer:" };
        String[] stage = { "stage", "lighting", "probes", "textures" };
        ArrayDeque<String> rendererLines = new ArrayDeque<String>();
        ArrayDeque<String> stageLines = new ArrayDeque<String>();
        for (String line : text.split("\r?\n")) {
            if (line.contains(" status: ")) continue;
            if (matchesAny(line, renderer)) {
                rendererLines.add(cut(line, LINE_CHARS));
                if (rendererLines.size() > 22) rendererLines.removeFirst();
            } else if (matchesAny(line, stage)) {
                stageLines.add(cut(line, LINE_CHARS));
                if (stageLines.size() > 8) stageLines.removeFirst();
            }
        }
        StringBuilder out = new StringBuilder("\n== renderer and stage lines in pt.log ==\n");
        if (rendererLines.isEmpty() && stageLines.isEmpty()) out.append("(none)\n");
        for (String line : rendererLines) out.append(line).append('\n');
        for (String line : stageLines) out.append(line).append('\n');
        return out.toString();
    }

    /** The warnings and errors in the game's log (the last distinct ones): what went wrong without the thousands of lines around it. */
    private static String problems(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "pt-runtime/data/pt.log");
        if (!file.isFile()) return "";
        RandomAccessFile in = new RandomAccessFile(file, "r");
        String text;
        try {
            long size = in.length();
            long start = Math.max(0, size - 1048576);
            byte[] bytes = new byte[(int) (size - start)];
            in.seek(start);
            in.readFully(bytes);
            text = new String(bytes, UTF8);
        } finally {
            in.close();
        }
        // the same line repeated (a warning every frame) is shown once, with how many times it came
        java.util.LinkedHashMap<String, Integer> seen = new java.util.LinkedHashMap<String, Integer>();
        for (String line : text.split("\r?\n")) {
            int level = line.indexOf("] ");
            if (level < 0 || level > 14) continue;
            String rest = line.substring(level + 2);
            if (!(rest.startsWith("warn ") || rest.startsWith("error"))) continue;
            Integer count = seen.get(rest);
            seen.put(rest, count == null ? 1 : count + 1);
        }
        StringBuilder out = new StringBuilder("\n== warnings and errors in pt.log ==\n");
        if (seen.isEmpty()) out.append("(none)\n");
        int skip = Math.max(0, seen.size() - 30);
        int index = 0;
        for (java.util.Map.Entry<String, Integer> entry : seen.entrySet()) {
            if (index++ < skip) continue;
            out.append(cut(entry.getKey(), LINE_CHARS)).append(entry.getValue() > 1 ? "  (x" + entry.getValue() + ")" : "").append('\n');
        }
        return out.toString();
    }

    /** The game's status lines ("status: 24 fps, gpu 31.2 ms, ..., 1620x1080, ..."), one per 10 seconds of play: the last ones. */
    private static String frameRate(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "pt-runtime/data/pt.log");
        if (!file.isFile()) return "";
        RandomAccessFile in = new RandomAccessFile(file, "r");
        String text;
        try {
            long size = in.length();
            long start = Math.max(0, size - 524288);
            byte[] bytes = new byte[(int) (size - start)];
            in.seek(start);
            in.readFully(bytes);
            text = new String(bytes, UTF8);
        } finally {
            in.close();
        }
        ArrayDeque<String> lines = new ArrayDeque<String>();
        for (String line : text.split("\r?\n")) {
            if (line.contains(" status: ")) {
                lines.add(cut(line, 260));
                if (lines.size() > 16) lines.removeFirst();
            }
        }
        StringBuilder out = new StringBuilder("\n== frame rate (a line every 10 seconds of play) ==\n");
        if (lines.isEmpty()) out.append("(none: the game has not run for 10 seconds)\n");
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the native crash (the tombstone Android saved with the exit reason)

    private static String tombstone(ApplicationExitInfo info) throws IOException {
        if (info.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) return "";
        InputStream stream = info.getTraceInputStream();
        if (stream == null) return "(Android kept no tombstone for it)\n";
        byte[] data;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int read;
            while ((read = stream.read(chunk)) > 0 && buffer.size() < (2 << 20)) buffer.write(chunk, 0, read);
            data = buffer.toByteArray();
        } finally {
            stream.close();
        }
        if (data.length == 0) return "(the tombstone is empty)\n";
        if (data[0] != 0x08) {
            // older Android: a text tombstone
            return cut(new String(data, UTF8), TOMBSTONE_CHARS) + "\n";
        }
        try {
            return cut(describeTombstone(data), TOMBSTONE_CHARS) + "\n";
        } catch (RuntimeException e) {
            return "(the tombstone could not be read: " + e + ")\n";
        }
    }

    /** The few fields of the tombstone (system/core/debuggerd/proto/tombstone.proto) that say what crashed and where. */
    private static String describeTombstone(byte[] data) {
        StringBuilder out = new StringBuilder();
        long crashingThread = -1;
        String abortMessage = null;
        Proto signal = null;
        List<String> causes = new ArrayList<String>();
        List<Proto> threads = new ArrayList<Proto>();
        List<Proto> buffers = new ArrayList<Proto>();
        Proto top = new Proto(data, 0, data.length);
        while (top.next()) {
            if (top.wire == 0 && top.field == 6) {
                crashingThread = top.num;
            } else if (top.wire == 2) {
                switch (top.field) {
                    case 10: signal = top.sub(); break;
                    case 14: abortMessage = top.string(); break;
                    case 15: {
                        Proto cause = top.sub();
                        while (cause.next()) if (cause.field == 1 && cause.wire == 2) causes.add(cause.string());
                        break;
                    }
                    case 16: threads.add(top.sub()); break;
                    case 18: buffers.add(top.sub()); break;
                    default: break;
                }
            }
        }
        if (signal != null) {
            long number = 0, code = 0, address = 0;
            String name = "", codeName = "";
            boolean hasAddress = false;
            while (signal.next()) {
                if (signal.wire == 0) {
                    if (signal.field == 1) number = signal.num;
                    else if (signal.field == 3) code = signal.num;
                    else if (signal.field == 8) hasAddress = signal.num != 0;
                    else if (signal.field == 9) address = signal.num;
                } else if (signal.wire == 2) {
                    if (signal.field == 2) name = signal.string();
                    else if (signal.field == 4) codeName = signal.string();
                }
            }
            out.append("signal ").append(number).append(" (").append(name).append("), code ").append(code).append(" (")
                    .append(codeName).append(')');
            if (hasAddress) out.append(", fault address 0x").append(Long.toHexString(address));
            out.append('\n');
        }
        if (abortMessage != null && !abortMessage.isEmpty()) out.append("abort message: ").append(cut(abortMessage, 600)).append('\n');
        for (String cause : causes) out.append("cause: ").append(cut(cause, 300)).append('\n');
        // the crashing thread's backtrace
        for (Proto entry : threads) {
            Proto thread = null;
            while (entry.next()) if (entry.field == 2 && entry.wire == 2) thread = entry.sub();
            if (thread == null) continue;
            long id = -1;
            String name = "";
            List<String> frames = new ArrayList<String>();
            while (thread.next()) {
                if (thread.field == 1 && thread.wire == 0) id = thread.num;
                else if (thread.field == 2 && thread.wire == 2) name = thread.string();
                else if (thread.field == 4 && thread.wire == 2) frames.add(frame(frames.size(), thread.sub()));
            }
            if (id != crashingThread) continue;
            out.append("thread ").append(id).append(" \"").append(name).append("\":\n");
            for (int i = 0; i < frames.size() && i < 32; ++i) out.append("  ").append(frames.get(i)).append('\n');
            if (frames.size() > 32) out.append("  ... ").append(frames.size() - 32).append(" more\n");
            break;
        }
        // what the process logged just before: the tombstone holds the last lines of its logcat
        List<String> lines = new ArrayList<String>();
        for (Proto buffer : buffers) {
            String bufferName = "";
            List<Proto> messages = new ArrayList<Proto>();
            while (buffer.next()) {
                if (buffer.field == 1 && buffer.wire == 2) bufferName = buffer.string();
                else if (buffer.field == 2 && buffer.wire == 2) messages.add(buffer.sub());
            }
            if (!"main".equals(bufferName) && !"crash".equals(bufferName)) continue;
            for (Proto message : messages) {
                String tag = "", text = "";
                while (message.next()) {
                    if (message.field == 5 && message.wire == 2) tag = message.string();
                    else if (message.field == 6 && message.wire == 2) text = message.string();
                }
                lines.add(cut(tag + ": " + text, LINE_CHARS));
            }
        }
        if (!lines.isEmpty()) {
            out.append("last log lines of the process:\n");
            for (int i = Math.max(0, lines.size() - 18); i < lines.size(); ++i) out.append("  ").append(lines.get(i)).append('\n');
        }
        return out.toString();
    }

    private static String frame(int index, Proto frame) {
        long relPc = 0, offset = 0;
        String function = "", file = "";
        while (frame.next()) {
            if (frame.wire == 0) {
                if (frame.field == 1) relPc = frame.num;
                else if (frame.field == 5) offset = frame.num;
            } else if (frame.wire == 2) {
                if (frame.field == 4) function = frame.string();
                else if (frame.field == 6) file = frame.string();
            }
        }
        int slash = file.lastIndexOf('/');
        if (slash >= 0) file = file.substring(slash + 1);
        StringBuilder out = new StringBuilder();
        out.append('#').append(index < 10 ? "0" : "").append(index).append(" pc ").append(String.format(Locale.US, "%016x", relPc))
                .append("  ").append(file.isEmpty() ? "?" : file);
        if (!function.isEmpty()) out.append(" (").append(cut(function, 160)).append('+').append(offset).append(')');
        return out.toString();
    }

    /** A small reader for protocol buffer messages: the next() field, then num, string() or sub() by its wire type. */
    private static final class Proto {
        private final byte[] bytes;
        private int position;
        private final int end;
        int field;
        int wire;
        long num;
        private int offset;
        private int length;

        Proto(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.position = start;
            this.end = end;
        }

        private long varint() {
            long value = 0;
            int shift = 0;
            while (true) {
                if (position >= end) throw new IllegalStateException("truncated");
                int b = bytes[position++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
                if (shift > 63) throw new IllegalStateException("bad number");
            }
        }

        boolean next() {
            if (position >= end) return false;
            long tag = varint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            switch (wire) {
                case 0:
                    num = varint();
                    break;
                case 1:
                case 5: {
                    int size = wire == 1 ? 8 : 4;
                    if (position + size > end) throw new IllegalStateException("truncated");
                    long value = 0;
                    for (int i = size - 1; i >= 0; --i) value = (value << 8) | (bytes[position + i] & 0xff);
                    num = value;
                    position += size;
                    break;
                }
                case 2: {
                    long size = varint();
                    if (size < 0 || position + size > end) throw new IllegalStateException("truncated");
                    offset = position;
                    length = (int) size;
                    position += length;
                    break;
                }
                default:
                    throw new IllegalStateException("unknown field type " + wire);
            }
            return true;
        }

        String string() {
            return new String(bytes, offset, length, UTF8);
        }

        Proto sub() {
            return new Proto(bytes, offset, offset + length);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the game's log and logcat

    /** The first lines of pt.log (version, GPU, driver) and the last ones (what happened before the end). */
    private static String gameLog(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "pt-runtime/data/pt.log");
        if (!file.isFile()) return "";
        RandomAccessFile in = new RandomAccessFile(file, "r");
        String headText, tailText;
        boolean overlap;
        try {
            long size = in.length();
            byte[] head = new byte[(int) Math.min(size, 8192)];
            in.readFully(head);
            headText = new String(head, UTF8);
            long tailStart = Math.max(0, size - 16384);
            byte[] tail = new byte[(int) (size - tailStart)];
            in.seek(tailStart);
            in.readFully(tail);
            tailText = new String(tail, UTF8);
            overlap = tailStart <= head.length;
            if (tailStart > 0) {
                int newline = tailText.indexOf('\n');
                if (newline >= 0) tailText = tailText.substring(newline + 1);
            }
        } finally {
            in.close();
        }
        String[] headLines = headText.split("\r?\n");
        String[] tailLines = tailText.split("\r?\n");
        StringBuilder out = new StringBuilder();
        if (overlap) {
            // a short log: all of it
            int from = Math.max(0, tailLines.length - (LOG_HEAD_LINES + LOG_TAIL_LINES));
            for (int i = from; i < tailLines.length; ++i) out.append(cut(tailLines[i], LINE_CHARS)).append('\n');
        } else {
            for (int i = 0; i < headLines.length && i < LOG_HEAD_LINES; ++i) out.append(cut(headLines[i], LINE_CHARS)).append('\n');
            out.append("[...]\n");
            for (int i = Math.max(0, tailLines.length - LOG_TAIL_LINES); i < tailLines.length; ++i) {
                out.append(cut(tailLines[i], LINE_CHARS)).append('\n');
            }
        }
        return keepEnd(out.toString(), LOG_CHARS);
    }

    /** The app's logcat from the run that ended: that process's lines when they are still there, else everything but this launcher's. */
    private static String logcat(int crashedPid) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500").redirectErrorStream(true).start();
        final ArrayDeque<String> lines = new ArrayDeque<String>();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF8));
                    try {
                        String line;
                        while ((line = in.readLine()) != null) {
                            synchronized (lines) {
                                lines.add(line);
                                if (lines.size() > 1500) lines.removeFirst();
                            }
                        }
                    } finally {
                        in.close();
                    }
                } catch (IOException ignored) {
                }
            }
        });
        reader.start();
        reader.join(5000);
        process.destroy();
        List<String> all;
        synchronized (lines) {
            all = new ArrayList<String>(lines);
        }
        int me = android.os.Process.myPid();
        List<String> theirs = new ArrayList<String>();
        List<String> others = new ArrayList<String>();
        for (String line : all) {
            // 10-12 14:03:11.123  1234  1250 I Tag: message
            String[] parts = line.split("\\s+", 6);
            if (parts.length < 6) continue;
            int pid;
            try {
                pid = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == me) continue;
            others.add(line);
            if (pid == crashedPid) theirs.add(line);
        }
        List<String> use = theirs.size() >= 8 ? theirs : others;
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, use.size() - LOGCAT_LINES); i < use.size(); ++i) {
            String line = use.get(i);
            // the date and the process ids add nothing the report needs
            String[] parts = line.split("\\s+", 6);
            String time = parts[1];
            out.append(time.length() > 8 ? time.substring(0, 8) : time).append(' ').append(parts[4]).append(' ').append(parts[5]);
            out.append('\n');
        }
        return keepEnd(cutLines(out.toString()), LOGCAT_CHARS);
    }

    // ----------------------------------------------------------------------------------------------------------------

    private static byte[] readAll(File file, int max) throws IOException {
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            byte[] bytes = new byte[(int) Math.min(in.length(), max)];
            in.readFully(bytes);
            return bytes;
        } finally {
            in.close();
        }
    }

    private static boolean matchesAny(String line, String[] keys) {
        for (String key : keys) {
            if (line.contains(key)) return true;
        }
        return false;
    }

    private static String cut(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    /** Every line of the text cut to LINE_CHARS. */
    private static String cutLines(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n")) out.append(cut(line, LINE_CHARS)).append('\n');
        return out.toString();
    }

    /** The end of the text, from a whole line on. */
    private static String keepEnd(String text, int max) {
        if (text.length() <= max) return text;
        String end = text.substring(text.length() - max);
        int newline = end.indexOf('\n');
        return "[...]\n" + (newline >= 0 ? end.substring(newline + 1) : end);
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "ANR (the app stopped answering)";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (a Java exception)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE (a native crash)";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF (the app exited by itself)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (killed for memory)";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED (killed by a signal)";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            default: return "UNKNOWN (" + reason + ")";
        }
    }

    private static String signalName(int number) {
        switch (number) {
            case 4: return "SIGILL";
            case 5: return "SIGTRAP";
            case 6: return "SIGABRT";
            case 7: return "SIGBUS";
            case 8: return "SIGFPE";
            case 9: return "SIGKILL";
            case 11: return "SIGSEGV";
            case 15: return "SIGTERM";
            default: return null;
        }
    }
}
