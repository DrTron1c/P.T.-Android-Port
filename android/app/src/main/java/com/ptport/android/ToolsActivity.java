package com.ptport.android;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The log, from the main menu: the device, the settings, the frame rate and the end of the game's log, to copy or share. The menu's
 * Settings has the settings themselves.
 */
public class ToolsActivity extends ThemedActivity {
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

    private void buildUi() {
        int pad = MenuStyle.dp(this, 20);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        MenuStyle.Entry back = new MenuStyle.Entry(this, "<  Back", 18);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        header.addView(back);
        TextView title = MenuStyle.text(this, "LOG", 24, MenuStyle.INK, true);
        title.setLetterSpacing(0.25f);
        title.setPadding(pad, 0, 0, 0);
        header.addView(title);
        column.addView(header);

        TextView hint = MenuStyle.text(this, "How the game is doing. Play for a minute first; a frame rate line is written every 10 seconds. "
                + "Copy it and paste it into the chat if something is wrong.", 13, MenuStyle.DIM, true);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        column.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = MenuStyle.button(this, "Copy", new View.OnClickListener() {
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
        row.addView(copyButton, weighted());
        row.addView(MenuStyle.button(this, "Share", new View.OnClickListener() {
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
        }), weighted());
        row.addView(MenuStyle.button(this, "Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshLog();
            }
        }), weighted());
        column.addView(row);

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(0xFFC8DCC8);
        logText.setTextIsSelectable(true);
        logText.setPadding(0, pad, 0, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logText);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = Math.min(getResources().getDisplayMetrics().widthPixels, MenuStyle.dp(this, 900));
        root.addView(column, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = MenuStyle.dp(this, 8);
        return params;
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
