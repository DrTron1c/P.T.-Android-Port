package com.ptport.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * The look of the menu screens, taken from the title screen: a dark room, off-white words in Share Tech, a thin light rule and a soft white glow for
 * what is chosen. (The names AMBER and AMBER_DARK are from an earlier, warmer look; they are the accent and the dark accent now.)
 */
final class MenuStyle {
    static final int INK = 0xFFCDCCC4;
    static final int DIM = 0xFF8E8C84;
    static final int FAINT = 0xFF55534D;
    static final int AMBER = 0xFFE9E5D8;
    static final int AMBER_DARK = 0xFF5A584F;
    static final int WARNING = 0xFFD9735F;
    /** The glow around lit words. */
    static final int GLOW = 0xCCFFEFC8;

    private MenuStyle() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static String when(long time) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(time));
    }

    /** Share Tech, the title screen's letters. */
    static Typeface font(Context context) {
        return context.getResources().getFont(R.font.share_tech);
    }

    /** Share Tech Mono, for the small lines of explanation and the numbers. */
    static Typeface mono(Context context) {
        return context.getResources().getFont(R.font.share_tech_mono);
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

    /** A line of words; with styled true they are in Share Tech (every screen of the menu asks for it). */
    static TextView text(Context context, String value, float sp, int color, boolean styled) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (styled) view.setTypeface(font(context));
        return view;
    }

    /** A thin rule that is bright at its left and fades out to the right, as under a chosen line of the title screen. */
    static View rule(Context context) {
        View view = new View(context);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { 0xB0FFF4DC, 0x40FFF4DC, 0x00FFF4DC });
        view.setBackground(shape);
        view.setMinimumHeight(Math.max(1, dp(context, 1)));
        return view;
    }

    /** A line of the menu: off-white words; the chosen one (touched, or reached with the pad) is white and glowing with a thin rule under it. */
    static final class Entry extends TextView {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader rule;
        private int ruleWidth = -1;

        Entry(Context context, String label, float sp) {
            super(context);
            setText(label);
            setTextSize(sp);
            setTypeface(font(context));
            setLetterSpacing(0.12f);
            setTextColor(INK);
            setFocusable(true);
            setClickable(true);
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 12));
        }

        private boolean lit() {
            return isPressed() || isFocused();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            setTextColor(lit() ? 0xFFFFFFFF : INK);
            setShadowLayer(lit() ? dp(getContext(), 8) : 0f, 0f, 0f, GLOW);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!lit()) return;
            int width = getWidth();
            if (rule == null || ruleWidth != width) {
                ruleWidth = width;
                rule = new LinearGradient(0f, 0f, width, 0f, new int[] { 0xB0FFF4DC, 0x40FFF4DC, 0x00FFF4DC }, new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP);
            }
            line.setShader(rule);
            float thick = Math.max(1.5f, dp(getContext(), 1));
            canvas.drawRect(0f, getHeight() - thick - dp(getContext(), 2), width, getHeight() - dp(getContext(), 2), line);
        }
    }

    /** A flat dark button with a thin outline that lights up when it is touched or reached with the pad. */
    static Button button(Context context, String label, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(font(context));
        button.setLetterSpacing(0.08f);
        button.setTextColor(INK);
        button.setMinHeight(dp(context, 44));
        button.setMinimumHeight(dp(context, 44));
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, buttonShape(context, 0xCC1A1916, 0xFFE9E5D8));
        states.addState(new int[] { android.R.attr.state_focused }, buttonShape(context, 0xCC1A1916, 0xFFE9E5D8));
        states.addState(new int[] {}, buttonShape(context, 0x99060504, 0x55FFF4DC));
        button.setBackground(states);
        button.setOnClickListener(listener);
        return button;
    }

    private static GradientDrawable buttonShape(Context context, int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, 1));
        shape.setStroke(Math.max(1, dp(context, 1)), stroke);
        return shape;
    }
}
