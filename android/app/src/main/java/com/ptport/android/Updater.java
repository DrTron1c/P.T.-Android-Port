package com.ptport.android;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Looks on GitHub for a newer build of this app, downloads it and hands it to Android's installer.
 *
 * The CI (.github/workflows/android.yml) publishes every build as a release tagged android-build-N, with the APK attached,
 * and N is also the APK's versionCode. So "newer" means a published release whose N is above the installed versionCode.
 * The APK is streamed straight into a PackageInstaller session (no second copy on disk). Android only accepts it if it is
 * signed with the same key as the installed app, which is the committed keystore every build uses.
 *
 * Every Listener call happens on the main thread.
 */
final class Updater {
    static final class Release {
        int build;
        String name;
        String url;
        long size;
        String sha256;   // lower-case hex, or null when GitHub gave none
    }

    interface Listener {
        /** The answer to the check: the newer release, or null when this build is current or GitHub could not be reached. */
        void onChecked(Release newer);
        void onDownloadStarting(Release release);
        void onDownloadProgress(int percent);
        /** The whole file is with Android's installer; it may ask the player to confirm. */
        void onInstalling();
        /** The update did not happen (cancelled, corrupt, no space...); the game can start as it is. */
        void onFailed(String message);
    }

    private static final String TAG = "PTUpdate";
    private static final String TAG_PREFIX = "android-build-";
    private static final String ASSET_NAME = "P.T.PC.Port-Android-arm64.apk";
    private static final String ACTION_STATUS = "com.ptport.android.UPDATE_STATUS";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private boolean receiverRegistered;

    Updater(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Registers for the installer's answers and starts the check in the background. */
    void start() {
        IntentFilter filter = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(statusReceiver, filter);
        receiverRegistered = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Release newer = null;
                try {
                    newer = findNewer();
                } catch (Exception e) {
                    Log.w(TAG, "update check failed: " + e);
                }
                final Release result = newer;
                post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onChecked(result);
                    }
                });
            }
        }, "pt-update-check").start();
    }

    /** Stops everything; no Listener call comes after this. A download in flight is abandoned. */
    void close() {
        closed = true;
        if (receiverRegistered) {
            receiverRegistered = false;
            try {
                context.unregisterReceiver(statusReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** Whether Android lets this app install packages (the "install unknown apps" switch, off until the player turns it on). */
    boolean canInstall() {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** Downloads the release and gives it to the installer. */
    void install(final Release release) {
        post(new Runnable() {
            @Override
            public void run() {
                listener.onDownloadStarting(release);
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadAndCommit(release);
                } catch (Exception e) {
                    Log.w(TAG, "update failed: " + e);
                    final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFailed(why);
                        }
                    });
                }
            }
        }, "pt-update-download").start();
    }

    private void post(final Runnable action) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) action.run();
            }
        });
    }

    private long installedBuild() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return Long.MAX_VALUE;   // cannot tell which build this is: offer nothing
        }
    }

    private static HttpURLConnection open(String address, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", "PT-PC-Port-Android");
        return connection;
    }

    /** The newest published android-build-N release with the APK attached, if its N is above the installed versionCode. */
    private Release findNewer() throws Exception {
        HttpURLConnection connection = open("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases?per_page=30", 6000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("GitHub answered " + connection.getResponseCode());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) body.write(buffer, 0, read);
            }
            JSONArray releases = new JSONArray(body.toString("UTF-8"));
            Release best = null;
            for (int i = 0; i < releases.length(); ++i) {
                Release candidate = parse(releases.getJSONObject(i));
                if (candidate != null && (best == null || candidate.build > best.build)) best = candidate;
            }
            return best != null && best.build > installedBuild() ? best : null;
        } finally {
            connection.disconnect();
        }
    }

    private static Release parse(JSONObject release) {
        if (release.optBoolean("draft")) return null;
        String tag = release.optString("tag_name", "");
        if (!tag.startsWith(TAG_PREFIX)) return null;
        int build;
        try {
            build = Integer.parseInt(tag.substring(TAG_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); ++i) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !ASSET_NAME.equals(asset.optString("name"))) continue;
            if (!"uploaded".equals(asset.optString("state"))) continue;
            Release result = new Release();
            result.build = build;
            result.name = release.optString("name", tag);
            result.url = asset.optString("browser_download_url", "");
            result.size = asset.optLong("size", 0);
            String digest = asset.optString("digest", "");
            if (digest.startsWith("sha256:")) result.sha256 = digest.substring(7).toLowerCase(Locale.US);
            return result.url.isEmpty() || result.size <= 0 ? null : result;
        }
        return null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(Locale.US, "%02x", value & 0xff));
        return text.toString();
    }

    private void downloadAndCommit(Release release) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(release.size);
        // where Android allows it (a later update of an app this one installed), no confirmation is asked
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        HttpURLConnection connection = null;
        try {
            connection = open(release.url, 15000);
            if (connection.getResponseCode() != 200) throw new IOException("the download answered " + connection.getResponseCode());
            MessageDigest digest = release.sha256 == null ? null : MessageDigest.getInstance("SHA-256");
            try (InputStream in = connection.getInputStream(); OutputStream out = session.openWrite("pt.apk", 0, release.size)) {
                byte[] buffer = new byte[256 * 1024];
                long done = 0;
                int lastPercent = -1;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (closed) throw new IOException("cancelled");
                    out.write(buffer, 0, read);
                    if (digest != null) digest.update(buffer, 0, read);
                    done += read;
                    final int percent = (int) Math.min(100, done * 100 / release.size);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onDownloadProgress(percent);
                            }
                        });
                    }
                }
                if (done != release.size) throw new IOException("the download was cut off at " + done + " of " + release.size + " bytes");
                if (digest != null && !hex(digest.digest()).equals(release.sha256)) throw new IOException("the download is corrupt (checksum differs)");
                session.fsync(out);
            }
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onInstalling();
                }
            });
            Intent statusIntent = new Intent(ACTION_STATUS).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, 1, statusIntent, flags);
            session.commit(pending.getIntentSender());
            committed = true;
        } finally {
            if (connection != null) connection.disconnect();
            if (!committed) session.abandon();
            session.close();
        }
    }

    /** The installer's answers: it asks for a confirmation, then reports success (the process is usually replaced first) or why not. */
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context receiverContext, Intent intent) {
            if (closed) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    listener.onFailed("Android did not offer to install it");
                    return;
                }
                try {
                    context.startActivity(confirm);
                } catch (Exception e) {
                    listener.onFailed("cannot open Android's installer: " + e.getMessage());
                }
                return;
            }
            case PackageInstaller.STATUS_SUCCESS:
                return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                listener.onFailed("cancelled");
                return;
            default:
                listener.onFailed(message == null ? "Android refused the update (status " + status + ")" : message);
            }
        }
    };
}
