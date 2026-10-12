package com.ptport.android;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The base of the menu screens: the hallway of the title screen behind them, burning steadily and dim (MenuBackdrop in its calm mode, under a
 * dark veil so the words can be read), or the screen's own backdrop; the system bars hidden; and the pad's buttons working on a screen made of
 * views (A presses the chosen one, B goes back).
 */
abstract class ThemedActivity extends Activity {
    protected FrameLayout root;

    /** The backdrop behind the screen; null for a screen that brings its own (the main menu's flickering hallway). */
    protected View createBackdrop() {
        MenuBackdrop backdrop = new MenuBackdrop(this);
        backdrop.setCalm(true);
        return backdrop;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MenuStyle.immersive(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF060504);
        View backdrop = createBackdrop();
        if (backdrop != null) {
            root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // the veil: darkest in the middle, where the words are
            View veil = new View(this);
            veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { 0xB8060504, 0xEE060504, 0xB8060504 }));
            root.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) MenuStyle.immersive(this);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            View focused = getCurrentFocus();
            if (focused != null && focused.isClickable()) {
                focused.performClick();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onBackPressed();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
