package com.ptport.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * One line of the main menu. The chosen line (touched, or reached with the pad; Start at first) is white, wider spaced and glowing, with a
 * thin light rule above and below it that fades out to the right, as in the title screen. All its measures come from the picture's scale
 * (MenuStage sets them), so the line looks the same on every screen.
 */
final class MenuEntry extends TextView {
    private static final int INK = 0xFFCDCCC4;
    private static final int LIT_INK = 0xFFFFFFFF;
    private static final int WARM = 0x00FFF4DC;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float unit = 1f;
    private boolean lit;
    private Shader rules;
    private Shader glow;

    MenuEntry(Context context, String label) {
        super(context);
        setText(label);
        setTypeface(context.getResources().getFont(R.font.share_tech));
        setIncludeFontPadding(false);
        setSingleLine(true);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        setFocusable(true);
        setClickable(true);
        setLit(false);
    }

    /** sizePx: the letters' size; unit: pixels of the screen for one pixel of the picture, for the measures of the rules and the glow. */
    void configure(float sizePx, float unit, int paddingLeft) {
        if (this.unit == unit && getTextSize() == sizePx && getPaddingLeft() == paddingLeft && rules != null) return;
        this.unit = unit;
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        setPadding(paddingLeft, 0, 0, 0);
        setShadowLayer(lit ? 9f * unit : 0f, 0f, 0f, 0xCCFFEFC8);
        // the rule: bright at its start, thinning out over some 400 pixels of the picture
        rules = new LinearGradient(0f, 0f, 400f * unit, 0f,
                new int[] { WARM, 0x80FFF4DC, 0x4DFFF4DC, 0x1AFFF4DC, 0x06FFF4DC, WARM },
                new float[] { 0f, 0.05f, 0.33f, 0.58f, 0.88f, 1f }, Shader.TileMode.CLAMP);
        // the pool of light the line sits in: a soft ellipse, wider than it is tall
        glow = new RadialGradient(0f, 0f, 300f * unit,
                new int[] { 0x16FFF2D8, 0x0BFFF2D8, 0x00FFF2D8 }, new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP);
    }

    void setLit(boolean value) {
        lit = value;
        setTextColor(value ? LIT_INK : INK);
        setLetterSpacing(value ? 0.26f : 0.15f);
        setShadowLayer(value ? 9f * unit : 0f, 0f, 0f, 0xCCFFEFC8);
        invalidate();
    }

    boolean isLit() {
        return lit;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (lit && rules != null) {
            float base = getBaseline();
            float top = base - 27.6f * unit;
            float bottom = base + 8.4f * unit;
            // the glow, an ellipse squashed from a circle
            canvas.save();
            canvas.translate(60f * unit, (top + bottom) / 2f);
            canvas.scale(1f, 56f / 300f);
            paint.setShader(glow);
            canvas.drawCircle(0f, 0f, 300f * unit, paint);
            canvas.restore();
            // the two rules
            float thick = Math.max(1.5f, 1.4f * unit);
            paint.setShader(rules);
            paint.setAlpha(210);
            canvas.drawRect(0f, top - thick / 2f, 400f * unit, top + thick / 2f, paint);
            paint.setAlpha(255);
            canvas.drawRect(0f, bottom - thick / 2f, 400f * unit, bottom + thick / 2f, paint);
            paint.setShader(null);
        }
        super.onDraw(canvas);
    }
}
