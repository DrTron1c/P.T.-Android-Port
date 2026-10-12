package com.ptport.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The menu's backdrop: a dark room with faint wallpaper stripes, one weak lamp that flickers and now and then goes out, drifting
 * dust, a heavy vignette and film grain. Everything is drawn by code: no image is used.
 *
 * It redraws about 30 times a second while it is on screen. In calm mode (the settings, the saves and the log, where text is read) the
 * lamp holds steady and the grain moves slowly.
 */
final class AtmosphereView extends View {
    private static final int GRAIN = 160;
    private static final int DUST = 22;

    private final Random random = new Random();
    private final Paint fill = new Paint();
    private final Paint stripes = new Paint();
    private final Paint lampPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint();
    private final Paint grainPaint = new Paint();
    private final Paint dustPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlay = new Paint();
    private final Matrix grainMatrix = new Matrix();
    private BitmapShader grainShader;
    private Bitmap grain;
    private RadialGradient lamp;
    private RadialGradient vignette;

    private boolean calm;
    private boolean running;
    private float flicker = 1f;
    private float flickerTarget = 1f;
    private long nextFlicker;
    private long nextBlackout;
    private long blackoutUntil;
    private long nextBand;
    private long bandUntil;
    private float bandY;
    private float grainX;
    private float grainY;
    private long lastGrain;
    private final float[] dustX = new float[DUST];
    private final float[] dustY = new float[DUST];
    private final float[] dustSpeed = new float[DUST];
    private final float[] dustSize = new float[DUST];
    private final float[] dustAlpha = new float[DUST];
    private long lastFrame;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, calm ? 66 : 33);
        }
    };

    AtmosphereView(Context context, boolean calm) {
        super(context);
        this.calm = calm;
        setWillNotDraw(false);
        stripes.setColor(0x16B59A6A);
        stripes.setStrokeWidth(Math.max(2f, 2f * context.getResources().getDisplayMetrics().density));
        fill.setColor(0xFF060504);
        overlay.setColor(0xFF000000);
        dustPaint.setColor(0xFFE8D9B5);
        // one square of random grey specks, tiled and moved by a random step each frame
        grain = Bitmap.createBitmap(GRAIN, GRAIN, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[GRAIN * GRAIN];
        for (int i = 0; i < pixels.length; ++i) {
            int level = random.nextInt(256);
            int alpha = random.nextInt(100) < 55 ? 0 : 8 + random.nextInt(46);
            pixels[i] = Color.argb(alpha, level, level, level);
        }
        grain.setPixels(pixels, 0, GRAIN, 0, 0, GRAIN, GRAIN);
        grainShader = new BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        grainPaint.setShader(grainShader);
        for (int i = 0; i < DUST; ++i) {
            dustX[i] = random.nextFloat();
            dustY[i] = random.nextFloat();
            dustSpeed[i] = 0.004f + random.nextFloat() * 0.012f;
            dustSize[i] = 0.6f + random.nextFloat() * 1.4f;
            dustAlpha[i] = 0.10f + random.nextFloat() * 0.22f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float reach = Math.max(w, h);
        // the lamp hangs above the left of the screen, where the menu's words are
        lamp = new RadialGradient(w * 0.30f, -h * 0.06f, reach * 0.85f,
                new int[] { 0xB0FFC27A, 0x50B07A3C, 0x00000000 }, new float[] { 0f, 0.38f, 1f }, Shader.TileMode.CLAMP);
        lampPaint.setShader(lamp);
        vignette = new RadialGradient(w * 0.38f, h * 0.42f, (float) Math.hypot(w, h) * 0.62f,
                new int[] { 0x00000000, 0x66000000, 0xF2000000 }, new float[] { 0.30f, 0.72f, 1f }, Shader.TileMode.CLAMP);
        vignettePaint.setShader(vignette);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        long now = lastFrame;
        nextBlackout = now + 4000 + random.nextInt(6000);
        nextBand = now + 6000 + random.nextInt(8000);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        boolean show = visibility == View.VISIBLE;
        if (show && !running && isAttachedToWindow()) {
            running = true;
            post(tick);
        } else if (!show) {
            running = false;
            removeCallbacks(tick);
        }
    }

    private void step(long now) {
        if (now >= nextFlicker) {
            if (calm) {
                flickerTarget = 0.94f + random.nextFloat() * 0.06f;
                nextFlicker = now + 400 + random.nextInt(700);
            } else {
                // mostly a small tremble; sometimes the lamp sags
                flickerTarget = random.nextInt(9) == 0 ? 0.45f + random.nextFloat() * 0.25f : 0.80f + random.nextFloat() * 0.20f;
                nextFlicker = now + 50 + random.nextInt(130);
            }
        }
        flicker += (flickerTarget - flicker) * 0.45f;
        if (!calm && now >= nextBlackout) {
            blackoutUntil = now + 70 + random.nextInt(170);
            // sometimes it stutters back out once more
            nextBlackout = random.nextInt(3) == 0 ? blackoutUntil + 90 + random.nextInt(120) : now + 7000 + random.nextInt(12000);
        }
        if (!calm && now >= nextBand) {
            bandUntil = now + 90 + random.nextInt(140);
            bandY = random.nextFloat();
            nextBand = now + 5000 + random.nextInt(11000);
        }
        long gap = calm ? 140 : 45;
        if (now - lastGrain >= gap) {
            lastGrain = now;
            grainX = random.nextInt(GRAIN);
            grainY = random.nextInt(GRAIN);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || lamp == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        step(now);
        boolean out = now < blackoutUntil;

        canvas.drawRect(0, 0, w, h, fill);
        // wallpaper stripes, lit by the lamp below
        float gap = 30f * getResources().getDisplayMetrics().density;
        for (float x = gap * 0.5f; x < w; x += gap) canvas.drawLine(x, 0, x, h, stripes);

        lampPaint.setAlpha(out ? 6 : Math.max(0, Math.min(255, Math.round(255f * flicker))));
        canvas.drawRect(0, 0, w, h, lampPaint);

        // dust drifting up through the light
        if (!out) {
            for (int i = 0; i < DUST; ++i) {
                dustY[i] -= dustSpeed[i] * dt;
                dustX[i] += (float) Math.sin(now / 1900.0 + i) * 0.0009f;
                if (dustY[i] < -0.02f) {
                    dustY[i] = 1.02f;
                    dustX[i] = random.nextFloat();
                }
                float px = dustX[i] * w;
                float py = dustY[i] * h;
                float fade = Math.max(0f, 1f - (float) Math.hypot(px - w * 0.30f, py) / (Math.max(w, h) * 0.9f));
                dustPaint.setAlpha(Math.round(255f * dustAlpha[i] * fade * flicker));
                canvas.drawCircle(px, py, dustSize[i] * getResources().getDisplayMetrics().density, dustPaint);
            }
        }

        canvas.drawRect(0, 0, w, h, vignettePaint);

        if (now < bandUntil) {
            // a faint tracking line, as on worn tape
            fill.setColor(0x22FFFFFF);
            canvas.drawRect(0, bandY * h, w, bandY * h + 2f * getResources().getDisplayMetrics().density, fill);
            fill.setColor(0xFF060504);
        }

        grainMatrix.setTranslate(grainX, grainY);
        grainShader.setLocalMatrix(grainMatrix);
        grainPaint.setAlpha(calm ? 150 : 230);
        canvas.drawRect(0, 0, w, h, grainPaint);

        if (out) {
            overlay.setAlpha(150);
            canvas.drawRect(0, 0, w, h, overlay);
        }
    }
}
