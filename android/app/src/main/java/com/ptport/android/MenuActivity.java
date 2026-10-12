package com.ptport.android;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;

import java.util.List;

/**
 * The main menu: Start, New game, Load save and Settings, over the dark backdrop. The launcher (ImportActivity) checks for an update and for
 * the game files first and then opens this; the game runs in its own process and returns here when it ends.
 */
public class MenuActivity extends ThemedActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView startNote;
    private TextView loading;
    private boolean starting;
    // the newest game ending that was shown as a crash report, so backing out of the report does not bring it back at once
    private static long reportedCrash;

    private final Runnable crashCheck = new Runnable() {
        @Override
        public void run() {
            CrashReport.Pending crashed = CrashReport.find(MenuActivity.this);
            if (crashed != null && crashed.time > reportedCrash) {
                reportedCrash = crashed.time;
                startActivity(new Intent(MenuActivity.this, ImportActivity.class).putExtra(ImportActivity.EXTRA_MODE, ImportActivity.MODE_CRASH));
            }
        }
    };

    @Override
    protected boolean calmBackdrop() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ImportActivity.hasGame(this)) {
            // the game files are gone (the app's storage was cleared): the launcher asks for them again
            startActivity(new Intent(this, ImportActivity.class));
            finish();
            return;
        }
        starting = false;
        loading.setVisibility(View.GONE);
        showSaveNote();
        // the game's ending is recorded by Android a moment after it happens
        ui.removeCallbacks(crashCheck);
        ui.postDelayed(crashCheck, 1200);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(crashCheck);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void showSaveNote() {
        SaveManager.Info info = SaveManager.current(this);
        if (info == null) {
            startNote.setText("A new game");
        } else {
            startNote.setText("Continue: " + info.summary());
        }
    }

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int side = Math.max(MenuStyle.dp(this, 32), (int) (screenWidth * 0.09f));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = MenuStyle.text(this, "P.T. PC PORT", 40, MenuStyle.INK, true);
        title.setLetterSpacing(0.28f);
        title.setShadowLayer(MenuStyle.dp(this, 14), 0, 0, 0x99C9A15B);
        column.addView(title);

        TextView subtitle = MenuStyle.text(this, "Runs from your own copy of the game", 13, MenuStyle.FAINT, true);
        subtitle.setLetterSpacing(0.08f);
        subtitle.setPadding(MenuStyle.dp(this, 3), MenuStyle.dp(this, 6), 0, MenuStyle.dp(this, 36));
        column.addView(subtitle);

        MenuStyle.Entry start = new MenuStyle.Entry(this, "Start", 30);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startGame();
            }
        });
        column.addView(start);
        startNote = MenuStyle.text(this, "", 12, MenuStyle.DIM, true);
        startNote.setPadding(MenuStyle.dp(this, 18), 0, 0, MenuStyle.dp(this, 14));
        column.addView(startNote);

        MenuStyle.Entry fresh = new MenuStyle.Entry(this, "New game", 30);
        fresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                newGame();
            }
        });
        column.addView(fresh);
        View freshGap = new View(this);
        column.addView(freshGap, new LinearLayout.LayoutParams(1, MenuStyle.dp(this, 14)));

        MenuStyle.Entry load = new MenuStyle.Entry(this, "Load save", 30);
        load.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MenuActivity.this, SavesActivity.class));
            }
        });
        column.addView(load);
        View gap = new View(this);
        column.addView(gap, new LinearLayout.LayoutParams(1, MenuStyle.dp(this, 14)));

        MenuStyle.Entry settings = new MenuStyle.Entry(this, "Settings", 30);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MenuActivity.this, SettingsActivity.class));
            }
        });
        column.addView(settings);

        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START);
        columnParams.leftMargin = side;
        root.addView(column, columnParams);

        // the small links along the bottom
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(link("Game files", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MenuActivity.this, ImportActivity.class).putExtra(ImportActivity.EXTRA_MODE, ImportActivity.MODE_IMPORT));
            }
        }));
        footer.addView(link("Check for updates", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MenuActivity.this, ImportActivity.class));
            }
        }));
        footer.addView(link("Log", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MenuActivity.this, ToolsActivity.class));
            }
        }));
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        footerParams.leftMargin = side - MenuStyle.dp(this, 12);
        footerParams.bottomMargin = MenuStyle.dp(this, 22);
        root.addView(footer, footerParams);

        TextView version = MenuStyle.text(this, versionText(), 12, MenuStyle.FAINT, true);
        FrameLayout.LayoutParams versionParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        versionParams.rightMargin = MenuStyle.dp(this, 26);
        versionParams.bottomMargin = MenuStyle.dp(this, 30);
        root.addView(version, versionParams);

        loading = MenuStyle.text(this, "Loading...", 22, MenuStyle.INK, true);
        loading.setLetterSpacing(0.2f);
        loading.setShadowLayer(MenuStyle.dp(this, 12), 0, 0, 0xAAC9A15B);
        loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        loadingParams.rightMargin = MenuStyle.dp(this, 36);
        loadingParams.bottomMargin = MenuStyle.dp(this, 70);
        root.addView(loading, loadingParams);
    }

    private View link(String label, View.OnClickListener listener) {
        TextView view = MenuStyle.text(this, label, 13, MenuStyle.DIM, true);
        view.setFocusable(true);
        view.setClickable(true);
        view.setPadding(MenuStyle.dp(this, 12), MenuStyle.dp(this, 10), MenuStyle.dp(this, 12), MenuStyle.dp(this, 10));
        view.setOnClickListener(listener);
        return view;
    }

    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "build " + info.versionName.substring(info.versionName.lastIndexOf('.') + 1);
        } catch (Exception e) {
            return "";
        }
    }

    // ---- starting the game ----

    /** The story from the beginning. With a save, after a question: the save is kept as a backup (Load save puts it back). */
    private void newGame() {
        if (starting) return;
        if (SaveManager.current(this) == null && !SaveManager.hasAnyFile(this)) {
            startGame();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Start a new game?")
                .setMessage("Your current save is kept as a backup, and Load save can put it back.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("New game", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (SaveManager.newGame(MenuActivity.this)) {
                            showSaveNote();
                            startGame();
                        } else {
                            Toast.makeText(MenuActivity.this, "Could not clear the save.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void startGame() {
        if (starting) return;
        starting = true;
        loading.setVisibility(View.VISIBLE);
        waitForGameProcess(System.currentTimeMillis() + 4000);
    }

    /**
     * The game's process ends a moment after its screen closes. A start that came before it ended would reuse the dying process, whose
     * native state is spent; it waits (4 s at most) for the process to be gone.
     */
    private void waitForGameProcess(final long deadline) {
        if (isFinishing()) return;
        if (!gameProcessAlive() || System.currentTimeMillis() >= deadline) {
            startActivity(new Intent(this, PTActivity.class));
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                waitForGameProcess(deadline);
            }
        }, 100);
    }

    private boolean gameProcessAlive() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = manager.getRunningAppProcesses();
            if (list == null) return false;
            String name = getPackageName() + ":game";
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (name.equals(info.processName)) return true;
            }
        } catch (RuntimeException ignored) {
            // no answer is the same as no process
        }
        return false;
    }
}
