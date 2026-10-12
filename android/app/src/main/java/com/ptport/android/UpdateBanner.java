package com.ptport.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The update banner of the main menu, in the look of the title screen: a strip of dark that fades into the hallway at its two ends, a thin
 * light rule above and below it, the words in Share Tech with a soft glow, and a thin line of light for the progress. It says that a check is
 * going on, that a version is available, how far the download is, and why an update did not happen; where a choice is needed it has words to
 * touch (Allow, Later).
 */
final class UpdateBanner extends LinearLayout {
    /** No line of light under the words. */
    static final int NO_BAR = -2;
    /** A short line of light that moves along: something is going on, how long it takes is not known. */
    static final int MOVING_BAR = -1;

    private static final int SHADE = 0xE0060504;
    private static final int INK = 0xFFFFFFFF;
    private static final int DETAIL = 0xFFBABDB2;
    private static final int TROUBLE = 0xFFE8A592;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextView title;
    private final TextView detail;
    private final LinearLayout actions;
    private final float density;
    private int bar = NO_BAR;
    private Shader backing;
    private Shader rule;
    private int shaderWidth = -1;
    private final Runnable dismissLater = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    UpdateBanner(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setVisibility(GONE);
        // the banner takes the touches that land on it, so they do not reach the menu behind
        setClickable(true);
        setPadding(MenuStyle.dp(context, 36), MenuStyle.dp(context, 14), MenuStyle.dp(context, 36), MenuStyle.dp(context, 20));

        title = new TextView(context);
        title.setTypeface(context.getResources().getFont(R.font.share_tech));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setLetterSpacing(0.2f);
        title.setIncludeFontPadding(false);
        title.setSingleLine(false);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        detail = new TextView(context);
        detail.setTypeface(context.getResources().getFont(R.font.share_tech_mono));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        detail.setLetterSpacing(0.05f);
        detail.setIncludeFontPadding(false);
        detail.setTextColor(DETAIL);
        detail.setPadding(0, MenuStyle.dp(context, 6), 0, 0);
        addView(detail, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        actions = new LinearLayout(context);
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.START);
        actions.setVisibility(GONE);
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /**
     * Shows the banner (or changes what it says). bar is NO_BAR, MOVING_BAR or a percentage from 0 to 100; trouble paints the words as a
     * problem. Actions set earlier stay until setActions is called.
     */
    void show(String heading, String line, int barValue, boolean trouble) {
        handler.removeCallbacks(dismissLater);
        animate().cancel();
        title.setText(heading);
        title.setTextColor(trouble ? TROUBLE : INK);
        title.setShadowLayer(MenuStyle.dp(getContext(), 9), 0f, 0f, trouble ? 0xAAD9735F : 0xCCFFEFC8);
        detail.setText(line);
        detail.setVisibility(line == null || line.isEmpty() ? GONE : VISIBLE);
        bar = barValue;
        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
        }
        animate().alpha(1f).setDuration(220).start();
        invalidate();
    }

    /** Words to touch under the text (none to remove them). */
    void setActions(String[] labels, final Runnable[] runs) {
        actions.removeAllViews();
        if (labels == null || labels.length == 0) {
            actions.setVisibility(GONE);
            return;
        }
        for (int i = 0; i < labels.length; ++i) {
            final Runnable run = runs[i];
            TextView word = new TextView(getContext());
            word.setText(labels[i]);
            word.setTypeface(getContext().getResources().getFont(R.font.share_tech));
            word.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            word.setLetterSpacing(0.15f);
            word.setIncludeFontPadding(false);
            word.setTextColor(0xFFCDCCC4);
            word.setShadowLayer(MenuStyle.dp(getContext(), 6), 0f, 0f, 0x88FFEFC8);
            word.setFocusable(true);
            word.setClickable(true);
            word.setPadding(0, MenuStyle.dp(getContext(), 12), MenuStyle.dp(getContext(), 26), MenuStyle.dp(getContext(), 6));
            word.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    ((TextView) view).setTextColor(hasFocus ? 0xFFFFFFFF : 0xFFCDCCC4);
                }
            });
            word.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    run.run();
                }
            });
            actions.addView(word);
        }
        actions.setVisibility(VISIBLE);
    }

    /** The banner fades away. */
    void dismiss() {
        handler.removeCallbacks(dismissLater);
        if (getVisibility() != VISIBLE) return;
        animate().cancel();
        animate().alpha(0f).setDuration(320).withEndAction(new Runnable() {
            @Override
            public void run() {
                setVisibility(GONE);
            }
        }).start();
    }

    /** The banner fades away after the given time, unless it is shown again before. */
    void dismissAfter(long milliseconds) {
        handler.removeCallbacks(dismissLater);
        handler.postDelayed(dismissLater, milliseconds);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(dismissLater);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        if (shaderWidth != width) {
            shaderWidth = width;
            backing = new LinearGradient(0f, 0f, width, 0f, new int[] { 0x00060504, SHADE, SHADE, 0x00060504 },
                    new float[] { 0f, 0.14f, 0.86f, 1f }, Shader.TileMode.CLAMP);
            rule = new LinearGradient(0f, 0f, width, 0f,
                    new int[] { 0x00FFF4DC, 0xB0FFF4DC, 0xB0FFF4DC, 0x00FFF4DC }, new float[] { 0f, 0.2f, 0.8f, 1f }, Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(backing);
        canvas.drawRect(0f, 0f, width, height, paint);
        float thick = Math.max(1.5f, 1.2f * density);
        paint.setShader(rule);
        canvas.drawRect(0f, 0f, width, thick, paint);
        canvas.drawRect(0f, height - thick, width, height, paint);
        paint.setShader(null);
        if (bar != NO_BAR) drawBar(canvas, width, height);
        if (bar == MOVING_BAR && getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    /** The thin line of light along the foot of the banner: a track, and on it the part done (or a short piece that goes to and fro). */
    private void drawBar(Canvas canvas, int width, int height) {
        float left = MenuStyle.dp(getContext(), 36);
        float right = width - left;
        float y = height - MenuStyle.dp(getContext(), 9);
        float line = Math.max(2f, 1.6f * density);
        paint.setColor(0x2EFFF4DC);
        canvas.drawRect(left, y - line / 2f, right, y + line / 2f, paint);
        float from;
        float to;
        if (bar == MOVING_BAR) {
            float span = right - left;
            float cycle = (SystemClock.uptimeMillis() % 1600) / 800f;
            float at = cycle <= 1f ? cycle : 2f - cycle;
            float length = span * 0.22f;
            from = left + (span - length) * at;
            to = from + length;
        } else {
            from = left;
            to = left + (right - left) * Math.max(0, Math.min(100, bar)) / 100f;
        }
        // a wider, fainter line under the bright one is the glow
        paint.setColor(0x30FFF4DC);
        canvas.drawRect(from, y - line * 2.2f, to, y + line * 2.2f, paint);
        paint.setColor(0xFFFFF4DC);
        canvas.drawRect(from, y - line / 2f, to, y + line / 2f, paint);
    }
}
