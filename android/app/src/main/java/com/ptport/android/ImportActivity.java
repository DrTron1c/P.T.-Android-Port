package com.ptport.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The launcher. The game reads its levels, models, textures and sounds from the player's own copy of P.T. (the PS4 files
 * chunk1.psarc and texture.qar, nothing of which is in this app). The first start asks for that copy and copies the
 * archives into the app's private storage, where the game finds them at pt-runtime/game/CUSA01127; every later start goes
 * straight to the game.
 *
 * Every start first asks GitHub whether a newer build exists (Updater). If one does, a banner at the top says so, shows the
 * download's progress, and Android's installer takes over; if not (or if GitHub cannot be reached within a few seconds),
 * the start goes on as above.
 */
public class ImportActivity extends Activity {
    private static final int REQUEST_TREE = 1;
    private static final int REQUEST_FILES = 2;

    // how long a start waits for the update check before it goes on without the answer
    private static final long CHECK_DEADLINE_MS = 6000;
    // how long an update problem stays readable before the start goes on
    private static final long FAILURE_SHOWN_MS = 3000;

    private static final String[] REQUIRED = { "chunk1.psarc", "texture.qar" };
    // only the optional enhanced textures need it, and they are not in the Android build yet; copied when it is found
    private static final String[] OPTIONAL = { "pathid_list_ps4.bin" };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button folderButton;
    private Button filesButton;
    private volatile boolean busy;

    private View content;
    private LinearLayout banner;
    private TextView bannerText;
    private ProgressBar bannerBar;
    private Updater updater;
    private boolean checkDone;
    private boolean proceeded;
    // an update found while this app may not install packages yet: the player is in Settings turning that on
    private Updater.Release waitingForPermission;

    private final Runnable checkDeadline = new Runnable() {
        @Override
        public void run() {
            if (checkDone) return;
            checkDone = true;
            proceed();
        }
    };

    private final Runnable proceedNow = new Runnable() {
        @Override
        public void run() {
            proceed();
        }
    };

    /** pt-runtime/game/CUSA01127 in the app's files: native code looks for the same place (android_platform.cpp). */
    static File gameDir(Context context) {
        return new File(context.getFilesDir(), "pt-runtime/game/CUSA01127");
    }

    static boolean hasGame(Context context) {
        File dir = gameDir(context);
        for (String name : REQUIRED) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        // the page below the banner waits for the update check, so a start that finds an update never flashes the game
        content.setVisibility(View.GONE);
        showBanner("Checking for updates...", -1, false);
        updater = new Updater(this, updateListener);
        updater.start();
        ui.postDelayed(checkDeadline, CHECK_DEADLINE_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForPermission != null) {
            // back from the Settings page for "install unknown apps"
            Updater.Release release = waitingForPermission;
            waitingForPermission = null;
            if (updater.canInstall()) updater.install(release);
            else proceed();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(checkDeadline);
        ui.removeCallbacks(proceedNow);
        if (updater != null) updater.close();
        super.onDestroy();
    }

    /** The start goes on without (or after) an update: the game when its files are here, else the page that asks for them. */
    private void proceed() {
        if (proceeded) return;
        proceeded = true;
        ui.removeCallbacks(checkDeadline);
        ui.removeCallbacks(proceedNow);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        banner.setVisibility(View.GONE);
        if (hasGame(this)) startGame();
        else content.setVisibility(View.VISIBLE);
    }

    private void startGame() {
        startActivity(new Intent(this, PTActivity.class));
        finish();
    }

    /** percent below 0 is an unmeasured wait (a moving bar); a failure is shown in red. */
    private void showBanner(String message, int percent, boolean failure) {
        banner.setBackgroundColor(failure ? Color.rgb(122, 31, 31) : Color.rgb(31, 58, 95));
        bannerText.setText(message);
        bannerBar.setIndeterminate(percent < 0);
        if (percent >= 0) bannerBar.setProgress(percent);
        banner.setVisibility(View.VISIBLE);
    }

    private final Updater.Listener updateListener = new Updater.Listener() {
        @Override
        public void onChecked(Updater.Release newer) {
            if (checkDone) return;   // the deadline already let the start go on
            checkDone = true;
            ui.removeCallbacks(checkDeadline);
            if (newer == null) {
                proceed();
                return;
            }
            if (!updater.canInstall()) {
                // Android keeps "install unknown apps" off for an app until the player turns it on, once
                waitingForPermission = newer;
                showBanner("Update available (build " + newer.build + "). Allow this app to install updates, then come back.", -1, false);
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) {
                    waitingForPermission = null;
                    onFailed("this device has no page to allow installs from this app");
                }
                return;
            }
            updater.install(newer);
        }

        @Override
        public void onDownloadStarting(Updater.Release release) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showBanner("Starting download... (build " + release.build + ")", -1, false);
        }

        @Override
        public void onDownloadProgress(int percent) {
            showBanner("Downloading update: " + percent + "%", percent, false);
        }

        @Override
        public void onInstalling() {
            showBanner("Download complete. Confirm the install when Android asks; the app closes while it updates, then open it again.", 100, false);
        }

        @Override
        public void onFailed(String message) {
            if (proceeded) return;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showBanner("Update " + ("cancelled".equals(message) ? "cancelled." : "failed: " + message + "."), 0, true);
            ui.postDelayed(proceedNow, FAILURE_SHOWN_MS);
        }
    };

    private View buildBanner(int pad) {
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setVisibility(View.GONE);

        bannerText = new TextView(this);
        bannerText.setTextSize(15);
        bannerText.setTextColor(Color.WHITE);
        bannerText.setPadding(pad, pad / 2, pad, pad / 2);
        banner.addView(bannerText);

        bannerBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bannerBar.setMax(100);
        banner.addView(bannerBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (4 * getResources().getDisplayMetrics().density)));
        return banner;
    }

    private void buildUi() {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("P.T. PC Port");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        column.addView(title);

        TextView text = new TextView(this);
        text.setText("This app contains no game files. It runs from your own copy of P.T. (US release, CUSA01127), dumped from "
                + "your PS4: the files chunk1.psarc and texture.qar.\n\n"
                + "Copy the dump folder to this device, then choose it below. The two files are copied into the app's own "
                + "storage once (several GB), so keep the screen on until it says it is done. You can delete the copy on "
                + "your device afterwards.");
        text.setTextSize(15);
        text.setTextColor(Color.LTGRAY);
        text.setPadding(0, pad / 2, 0, pad / 2);
        column.addView(text);

        folderButton = new Button(this);
        folderButton.setText("Choose the dump folder");
        folderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseFolder();
            }
        });
        column.addView(folderButton);

        filesButton = new Button(this);
        filesButton.setText("Or choose the files themselves");
        filesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseFiles();
            }
        });
        column.addView(filesButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        column.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.WHITE);
        status.setPadding(0, pad / 2, 0, 0);
        column.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        content = scroll;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(buildBanner(pad), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void chooseFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_TREE);
    }

    private void chooseFiles() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || busy) return;
        final Map<String, Uri> found = new LinkedHashMap<String, Uri>();
        if (requestCode == REQUEST_TREE && data.getData() != null) {
            setBusy(true, "Looking for the game files...");
            final Uri tree = data.getData();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    findInTree(tree, found);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            beginCopy(found);
                        }
                    });
                }
            }).start();
        } else if (requestCode == REQUEST_FILES) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); ++i) addPicked(clip.getItemAt(i).getUri(), found);
            } else if (data.getData() != null) {
                addPicked(data.getData(), found);
            }
            beginCopy(found);
        }
    }

    private void addPicked(Uri uri, Map<String, Uri> found) {
        String name = displayName(uri);
        if (name != null && isWanted(name) && !found.containsKey(name)) found.put(name, uri);
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private long size(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[] { OpenableColumns.SIZE }, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    private static boolean isWanted(String name) {
        for (String required : REQUIRED) if (required.equals(name)) return true;
        for (String optional : OPTIONAL) if (optional.equals(name)) return true;
        return false;
    }

    /** Breadth first through the picked folder, a few levels down, for the files by name. */
    private void findInTree(Uri tree, Map<String, Uri> found) {
        ContentResolver resolver = getContentResolver();
        ArrayDeque<String> ids = new ArrayDeque<String>();
        ArrayDeque<Integer> depths = new ArrayDeque<Integer>();
        ids.add(DocumentsContract.getTreeDocumentId(tree));
        depths.add(0);
        int visited = 0;
        while (!ids.isEmpty() && visited < 4000) {
            String id = ids.poll();
            int depth = depths.poll();
            ++visited;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            Cursor cursor = null;
            try {
                cursor = resolver.query(children, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null);
                while (cursor != null && cursor.moveToNext()) {
                    String childId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (depth < 6) {
                            ids.add(childId);
                            depths.add(depth + 1);
                        }
                    } else if (name != null && isWanted(name) && !found.containsKey(name)) {
                        found.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, childId));
                    }
                }
            } catch (Exception ignored) {
                // a folder that cannot be listed is skipped
            } finally {
                if (cursor != null) cursor.close();
            }
        }
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        folderButton.setEnabled(!value);
        filesButton.setEnabled(!value);
        status.setText(message);
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void beginCopy(final Map<String, Uri> found) {
        for (String required : REQUIRED) {
            if (!found.containsKey(required)) {
                setBusy(false, "Could not find " + required + " there. Choose the folder that holds the dump's chunk1.psarc "
                        + "and texture.qar (or pick both files).");
                progress.setVisibility(View.GONE);
                return;
            }
        }
        long total = 0;
        for (Uri uri : found.values()) {
            long length = size(uri);
            if (length > 0) total += length;
        }
        File dir = gameDir(this);
        dir.mkdirs();
        if (total > 0 && dir.getParentFile().getUsableSpace() < total + (64L << 20)) {
            setBusy(false, String.format(Locale.US, "Not enough free space: the files take %.1f GB.", total / 1073741824.0));
            return;
        }
        final long totalBytes = total;
        setBusy(true, "Copying...");
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    copyAll(found, totalBytes);
                } catch (IOException e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String message = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (message == null && hasGame(ImportActivity.this)) {
                            startGame();
                        } else {
                            progress.setVisibility(View.GONE);
                            setBusy(false, "Copy failed: " + (message == null ? "the files are incomplete" : message));
                        }
                    }
                });
            }
        }).start();
    }

    private void copyAll(Map<String, Uri> found, long totalBytes) throws IOException {
        File dir = gameDir(this);
        long done = 0;
        long lastReport = 0;
        byte[] buffer = new byte[1 << 20];
        for (Map.Entry<String, Uri> entry : found.entrySet()) {
            final String name = entry.getKey();
            File target = new File(dir, name);
            File part = new File(dir, name + ".part");
            InputStream in = getContentResolver().openInputStream(entry.getValue());
            if (in == null) throw new IOException("cannot open " + name);
            OutputStream out = new FileOutputStream(part);
            try {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (done - lastReport >= (16L << 20)) {
                        lastReport = done;
                        report(name, done, totalBytes);
                    }
                }
            } finally {
                try { in.close(); } catch (IOException ignored) { }
                out.close();
            }
            if (target.exists() && !target.delete()) throw new IOException("cannot replace " + name);
            if (!part.renameTo(target)) throw new IOException("cannot finish " + name);
        }
    }

    private void report(final String name, final long done, final long total) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (total > 0) progress.setProgress((int) (done * 1000 / total));
                status.setText(String.format(Locale.US, "Copying %s: %.1f of %.1f GB", name, done / 1073741824.0,
                        total / 1073741824.0));
            }
        });
    }
}
