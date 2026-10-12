package com.ptport.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** The look of the menu screens: a dark room, warm off-white words, one muted amber for what is chosen. */
final class MenuStyle {
    static final int INK = 0xFFE6DFD0;
    static final int DIM = 0xFF9A9384;
    static final int FAINT = 0xFF625C50;
    static final int AMBER = 0xFFC9A15B;
    static final int AMBER_DARK = 0xFF8C6A2F;
    static final int WARNING = 0xFFD9735F;

    private MenuStyle() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static String when(long time) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(time));
    }

    static Typeface light() {
        return Typeface.create("sans-serif-light", Typeface.NORMAL);
    }

    /** The system bars hidden; they come back with a swipe from the edge. */
    @SuppressWarnings("deprecation")
    static void immersive(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            activity.getWindow().setAttributes(params);
        }
    }

    static TextView text(Context context, String value, float sp, int color, boolean light) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (light) view.setTypeface(light());
        return view;
    }

    /** A line of the menu: plain light words; the chosen one (touched, or reached with the pad) is brighter with a bar beside it. */
    static final class Entry extends TextView {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);

        Entry(Context context, String label, float sp) {
            super(context);
            setText(label);
            setTextSize(sp);
            setTypeface(light());
            setLetterSpacing(0.06f);
            setTextColor(new ColorStateList(
                    new int[][] { { android.R.attr.state_pressed }, { android.R.attr.state_focused }, {} },
                    new int[] { 0xFFFFFFFF, 0xFFFFFFFF, INK }));
            setShadowLayer(dp(context, 9), 0, 0, 0x88C9A15B);
            setFocusable(true);
            setClickable(true);
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10));
            bar.setColor(AMBER);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (isPressed() || isFocused()) {
                float width = dp(getContext(), 3);
                canvas.drawRect(0, getHeight() * 0.18f, width, getHeight() * 0.82f, bar);
            }
        }
    }

    /** A flat dark button with a thin outline, for the screens that hold many actions. */
    static Button button(Context context, String label, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(light());
        button.setTextColor(INK);
        button.setMinHeight(dp(context, 44));
        button.setMinimumHeight(dp(context, 44));
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, buttonShape(context, 0xAA3A2C14, AMBER));
        states.addState(new int[] { android.R.attr.state_focused }, buttonShape(context, 0xAA3A2C14, AMBER));
        states.addState(new int[] {}, buttonShape(context, 0x66000000, FAINT));
        button.setBackground(states);
        button.setOnClickListener(listener);
        return button;
    }

    private static GradientDrawable buttonShape(Context context, int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, 4));
        shape.setStroke(Math.max(1, dp(context, 1)), stroke);
        return shape;
    }
}
