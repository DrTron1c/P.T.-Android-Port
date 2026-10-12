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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;

import java.util.List;

/**
 * The main menu: Start, New game, Load save and Settings, over the hallway with its flickering bulb (MenuBackdrop, laid out by MenuStage).
 * The launcher (ImportActivity) checks for an update and for the game files first and then opens this; the game runs in its own process and
 * returns here when it ends.
 */
public class MenuActivity extends ThemedActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MenuEntry[] entries;
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

    /** The menu brings its own backdrop, the flickering hallway (MenuBackdrop, placed by MenuStage). */
    @Override
    protected View createBackdrop() {
        return null;
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
        choose(entries[0]);
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
        MenuBackdrop backdrop = new MenuBackdrop(this);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.menu_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_XY);
        logo.setContentDescription("P.T.");
        // the logo glows with the bulb
        backdrop.setFollower(logo);

        entries = new MenuEntry[] {
            entry("Start", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    startGame();
                }
            }),
            entry("New game", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    newGame();
                }
            }),
            entry("Load save", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    startActivity(new Intent(MenuActivity.this, SavesActivity.class));
                }
            }),
            entry("Settings", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    startActivity(new Intent(MenuActivity.this, SettingsActivity.class));
                }
            }),
        };
        choose(entries[0]);

        // the line under Start: what Start continues from
        startNote = new TextView(this);
        startNote.setTypeface(getResources().getFont(R.font.share_tech_mono));
        startNote.setIncludeFontPadding(false);
        startNote.setSingleLine(true);
        startNote.setTextColor(0xFFBABDB2);
        startNote.setLetterSpacing(0.05f);
        startNote.setClickable(true);
        startNote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startGame();
            }
        });

        // the small links along the bottom, and the build
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
        TextView version = MenuStyle.text(this, versionText(), 12, 0xFF7C766A, false);
        version.setTypeface(getResources().getFont(R.font.share_tech_mono));
        version.setShadowLayer(MenuStyle.dp(this, 3), 0, 0, 0xCC000000);
        version.setPadding(MenuStyle.dp(this, 12), 0, 0, 0);
        footer.addView(version);

        loading = MenuStyle.text(this, "Loading...", 22, MenuStyle.INK, false);
        loading.setTypeface(getResources().getFont(R.font.share_tech));
        loading.setLetterSpacing(0.2f);
        loading.setShadowLayer(MenuStyle.dp(this, 12), 0, 0, 0xAAC9A15B);
        loading.setVisibility(View.GONE);

        root.addView(new MenuStage(this, backdrop, logo, entries, startNote, footer, loading),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** One line of the menu. The line that is touched, or reached with the pad, becomes the chosen one. */
    private MenuEntry entry(String label, View.OnClickListener onClick) {
        final MenuEntry line = new MenuEntry(this, label);
        line.setOnClickListener(onClick);
        line.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) choose(line);
                return false;
            }
        });
        line.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) choose(line);
            }
        });
        return line;
    }

    private void choose(MenuEntry chosen) {
        for (MenuEntry line : entries) line.setLit(line == chosen);
    }

    private View link(String label, View.OnClickListener listener) {
        TextView view = MenuStyle.text(this, label, 12, MenuStyle.DIM, false);
        view.setTypeface(getResources().getFont(R.font.share_tech));
        view.setLetterSpacing(0.08f);
        view.setShadowLayer(MenuStyle.dp(this, 3), 0, 0, 0xCC000000);
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
