package com.ptport.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The second icon ("P.T. tools"): what the game's picture size and quality are set to and how fast it runs, without a computer.
 *
 * - The picture size (PTActivity draws into a surface of that many lines and Android scales it to the screen) and the quality
 *   preset (the native side applies it at the next start, android_platform.h) are set here, by buttons.
 * - The log: the device, the settings, the frame rate and the end of the game's log, to copy or share.
 */
public class ToolsActivity extends Activity {
    private static final int[] HEIGHTS = { 0, 1440, 1080, 900, 720, 540 };
    private static final String[] PRESETS = { "low", "medium", "original" };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView sizeText;
    private TextView presetText;
    private TextView logText;
    private Button copyButton;
    private String report = "";
    private String presetRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        showSize();
        showPreset();
        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
    }

    private static String sizeName(int height) {
        return height == 0 ? "the screen's own size" : height + " lines";
    }

    private void showSize() {
        sizeText.setText("Picture size: " + sizeName(PTActivity.renderHeight(this))
                + ". A smaller picture runs faster and looks softer. Takes effect when the game starts."
                + (PTActivity.renderHeight(this) == PTActivity.DEFAULT_RENDER_HEIGHT ? " (This is the default.)" : ""));
    }

    private File presetFile() {
        return new File(getFilesDir(), "pt-runtime/data/android-quality.txt");
    }

    private void showPreset() {
        String text;
        if (presetRequested != null) {
            text = "Quality: " + presetRequested + " is applied when the game starts next. After that, the game's own settings page "
                    + "(PC settings in its menu) changes single options.";
        } else {
            text = "Quality: pick a preset to apply it at the next start. Low turns off shadows' detail and the extra effects; "
                    + "Medium keeps the look with lighter shadows; Original is the desktop game's default.";
        }
        presetText.setText(text);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * density);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("P.T. tools");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        column.addView(title);

        Button start = button("Start the game", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(ToolsActivity.this, ImportActivity.class));
                finish();
            }
        });
        column.addView(start);

        sizeText = label(pad);
        column.addView(sizeText);
        LinearLayout sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        for (final int height : HEIGHTS) {
            sizes.addView(button(height == 0 ? "Screen" : String.valueOf(height), new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    PTActivity.setRenderHeight(ToolsActivity.this, height);
                    showSize();
                }
            }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        column.addView(sizes);

        presetText = label(pad);
        column.addView(presetText);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (final String preset : PRESETS) {
            presets.addView(button(Character.toUpperCase(preset.charAt(0)) + preset.substring(1), new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (writePreset(preset)) {
                        presetRequested = preset;
                        showPreset();
                    } else {
                        Toast.makeText(ToolsActivity.this, "Could not save the choice.", Toast.LENGTH_LONG).show();
                    }
                }
            }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        column.addView(presets);

        TextView logTitle = label(pad);
        logTitle.setText("The log: how the game is doing. Play for a minute first; a frame rate line is written every 10 seconds.");
        column.addView(logTitle);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = button("Copy", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("P.T. log", report));
                    copyButton.setText("Copied");
                    Toast.makeText(ToolsActivity.this, "Copied. Paste it into the chat.", Toast.LENGTH_SHORT).show();
                } catch (RuntimeException e) {
                    Toast.makeText(ToolsActivity.this, "Could not copy: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        row.addView(copyButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button("Share", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "P.T. PC Port log");
                send.putExtra(Intent.EXTRA_TEXT, report);
                try {
                    startActivity(Intent.createChooser(send, "Share the log"));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(ToolsActivity.this, "Nothing on this device can take it; use Copy.", Toast.LENGTH_LONG).show();
                }
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button("Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshLog();
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(row);

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(Color.rgb(200, 220, 200));
        logText.setTextIsSelectable(true);
        logText.setPadding(0, pad, 0, 0);
        column.addView(logText);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(column);
        setContentView(scroll);
    }

    private TextView label(int pad) {
        TextView text = new TextView(this);
        text.setTextSize(14);
        text.setTextColor(Color.LTGRAY);
        text.setPadding(0, pad, 0, pad / 3);
        return text;
    }

    private boolean writePreset(String preset) {
        try {
            File file = presetFile();
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write((preset + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void refreshLog() {
        logText.setText("Reading the log...");
        copyButton.setText("Copy");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = CrashReport.buildLog(ToolsActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        report = text;
                        logText.setText(text);
                    }
                });
            }
        }).start();
    }
}
