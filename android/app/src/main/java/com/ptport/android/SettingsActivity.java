package com.ptport.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

/**
 * Settings from the main menu: the same panel that opens over the game (SettingsPanel). With no game running the changes are kept and
 * the game takes them when it starts. Here, on an Adreno phone, the panel also installs a custom GPU driver (GpuDriver).
 */
public class SettingsActivity extends ThemedActivity {
    private static final int PICK_DRIVER = 1;

    private SettingsPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        android.widget.TextView title = MenuStyle.text(this, "SETTINGS", 24, MenuStyle.INK, true);
        title.setLetterSpacing(0.25f);
        title.setShadowLayer(MenuStyle.dp(this, 7), 0, 0, MenuStyle.GLOW);
        title.setPadding(pad, 0, 0, 0);
        header.addView(title);
        column.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        panel = new SettingsPanel(this);
        panel.setDriverPicker(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_DRIVER);
            }
        });
        scroll.addView(panel.createContent());
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = Math.min(getResources().getDisplayMetrics().widthPixels, MenuStyle.dp(this, 760));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        root.addView(column, params);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DRIVER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = GpuDriver.install(SettingsActivity.this, uri);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        panel.showDriver();
                        Toast.makeText(SettingsActivity.this, error != null ? error : "Driver installed. Start the game again to use it.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
