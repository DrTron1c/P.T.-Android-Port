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

/**
 * The second icon ("P.T. tools"): the game's settings and how fast it runs, without a computer.
 *
 * - The settings (SettingsPanel): picture size, frame rate, and each graphics option on its own, or a preset for all of them. The
 *   same panel opens over the game from its SET button. Changes wait for the game's next start when it is not running.
 * - The log: the device, the settings, the frame rate and the end of the game's log, to copy or share.
 */
public class ToolsActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logText;
    private Button copyButton;
    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
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

        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("Settings");
        settingsTitle.setTextSize(20);
        settingsTitle.setTextColor(Color.WHITE);
        settingsTitle.setPadding(0, pad, 0, 0);
        column.addView(settingsTitle);
        column.addView(new SettingsPanel(this).createContent());

        TextView logTitle = new TextView(this);
        logTitle.setText("The log: how the game is doing. Play for a minute first; a frame rate line is written every 10 seconds.");
        logTitle.setTextSize(14);
        logTitle.setTextColor(Color.LTGRAY);
        logTitle.setPadding(0, pad, 0, pad / 3);
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
