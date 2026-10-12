package com.ptport.android;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Settings from the main menu: the same panel that opens over the game (SettingsPanel). With no game running the changes are kept and
 * the game takes them when it starts.
 */
public class SettingsActivity extends ThemedActivity {
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
        title.setPadding(pad, 0, 0, 0);
        header.addView(title);
        column.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(new SettingsPanel(this).createContent());
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = Math.min(getResources().getDisplayMetrics().widthPixels, MenuStyle.dp(this, 760));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        root.addView(column, params);
    }
}
