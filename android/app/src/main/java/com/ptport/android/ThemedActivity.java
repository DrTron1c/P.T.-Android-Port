package com.ptport.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The base of the menu screens: the dark backdrop (AtmosphereView), the system bars hidden, and the pad's buttons working on a screen
 * made of views (A presses the chosen one, B goes back).
 */
abstract class ThemedActivity extends Activity {
    protected FrameLayout root;

    /** True when the backdrop should hold still (screens with text to read). */
    protected boolean calmBackdrop() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MenuStyle.immersive(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF060504);
        root.addView(new AtmosphereView(this, calmBackdrop()),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
