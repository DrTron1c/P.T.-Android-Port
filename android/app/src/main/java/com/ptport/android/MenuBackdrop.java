package com.ptport.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The main menu's picture: the hallway with its one bulb (res/drawable-nodpi/menu_hall.webp, a still of the title screen with the logo and
 * the words taken out; the logo and the entries are views on top). The bulb flickers: the whole picture is lit by a level that mostly holds
 * near 1, now and then stutters through a few quick dips, and once in a while goes nearly out for a moment.
 *
 * The picture is as tall as the screen and as wide as it needs to be (1672 x 941 pixels, 16:9). On a wider screen it sits in the middle and
 * fades into the dark at its edges; on a narrower one its right side is cut off, because the entries are on the left.
 */
final class MenuBackdrop extends View {
    static final float PICTURE_WIDTH = 1672f;
    static final float PICTURE_HEIGHT = 941f;
    private static final int ROOM = 0xFF060504;

    private final Bitmap picture;
    private final Paint picturePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint edgePaint = new Paint();
    private Shader leftFade;
    private Shader rightFade;
    private final ColorMatrix gain = new ColorMatrix();
    private final RectF target = new RectF();
    private final Random random = new Random();
    private final float[] plan = new float[40];
    private int planLength;
    private int planAt;

    private float level = 1f;
    private float goal = 1f;
    private long segmentEnd;
    private long nextEvent;
    private long nextOut;
    private long lastStep;
    private float shownLevel = -1f;
    private boolean running;
    private boolean calm;
    private View follower;
    private MenuSound sound;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            step(now);
            if (sound != null) sound.setLight(level);
            if (Math.abs(level - shownLevel) > 0.004f) {
                shownLevel = level;
                invalidate();
                if (follower != null) follower.setAlpha(0.8f + 0.2f * Math.max(0f, Math.min(1f, level)));
            }
            postDelayed(this, calm ? 100 : planAt < planLength || level < 0.9f ? 16 : 55);
        }
    };

    MenuBackdrop(Context context) {
        super(context);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap decoded = null;
        try {
            decoded = BitmapFactory.decodeResource(context.getResources(), R.drawable.menu_hall, options);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // no picture: the dark room is all there is
        }
        picture = decoded;
        setWillNotDraw(false);
    }

    /** The sound that is told the bulb's brightness (the buzz follows it). */
    void setSound(MenuSound value) {
        sound = value;
    }

    /**
     * The calm picture of the screens that hold words to read (Settings, Load save, the log): the bulb burns steadily and a little dim, and
     * nothing flickers.
     */
    void setCalm(boolean value) {
        calm = value;
        if (value) {
            level = 0.55f;
            goal = 0.55f;
        }
        shownLevel = -1f;
    }

    /** A view that follows the flicker a little (the logo glows with the bulb). */
    void setFollower(View view) {
        follower = view;
    }

    /** Pixels of the screen for one pixel of the picture: the picture is as tall as the screen. */
    static float scale(int height) {
        return height / PICTURE_HEIGHT;
    }

    /** Where the picture's left edge is: in the middle of a wide screen, at the screen's edge when the screen is narrower. */
    static float left(int width, int height) {
        float pictureWidth = PICTURE_WIDTH * scale(height);
        return pictureWidth < width ? (width - pictureWidth) / 2f : 0f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (running || !isAttachedToWindow()) return;
        running = true;
        long now = SystemClock.uptimeMillis();
        lastStep = now;
        if (nextEvent == 0) {
            // the first stutter comes soon, so the menu is seen to flicker
            nextEvent = now + 900 + random.nextInt(900);
            nextOut = now + 9000 + random.nextInt(9000);
        }
        post(tick);
    }

    private void stop() {
        running = false;
        removeCallbacks(tick);
    }

    // ---- the flicker ----

    private void addSegment(float target, int milliseconds) {
        if (planLength + 2 > plan.length) return;
        plan[planLength++] = target;
        plan[planLength++] = milliseconds;
    }

    /** A run of quick dips and recoveries, each a few hundredths of a second. */
    private void planStutter() {
        planLength = 0;
        planAt = 0;
        int dips = 2 + random.nextInt(5);
        for (int i = 0; i < dips; ++i) {
            addSegment(0.12f + random.nextFloat() * 0.5f, 25 + random.nextInt(95));
            addSegment(0.72f + random.nextFloat() * 0.42f, 20 + random.nextInt(85));
        }
        addSegment(1f, 120);
    }

    /** The bulb nearly goes out for a moment, then comes back unsteadily. */
    private void planOut() {
        planLength = 0;
        planAt = 0;
        addSegment(0.05f, 260 + random.nextInt(520));
        addSegment(0.7f, 45 + random.nextInt(60));
        addSegment(0.08f, 40 + random.nextInt(80));
        addSegment(0.95f, 60 + random.nextInt(70));
        addSegment(0.3f, 35 + random.nextInt(50));
        addSegment(1f, 150);
    }

    private void step(long now) {
        long dt = Math.max(1, Math.min(100, now - lastStep));
        lastStep = now;
        if (calm) {
            goal = 0.55f;
            level += (goal - level) * (1f - (float) Math.exp(-dt / 140.0));
            return;
        }
        if (planAt >= planLength) {
            if (now >= nextOut) {
                planOut();
                nextOut = now + 14000 + random.nextInt(16000);
                nextEvent = now + 2500 + random.nextInt(3000);
            } else if (now >= nextEvent) {
                planStutter();
                nextEvent = now + 1400 + random.nextInt(4200);
            }
        }
        if (now >= segmentEnd) {
            if (planAt < planLength) {
                goal = plan[planAt++];
                segmentEnd = now + (long) plan[planAt++];
            } else {
                // between stutters the bulb hums: a barely noticeable tremble around full
                goal = 1f + (random.nextFloat() - 0.5f) * 0.05f;
                segmentEnd = now + 70 + random.nextInt(110);
            }
        }
        // the level follows the goal within a frame or two: a bulb is quick
        float follow = 1f - (float) Math.exp(-dt / 12.0);
        level += (goal - level) * follow;
    }

    // ---- drawing ----

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float scale = scale(height);
        float left = left(width, height);
        float fade = PICTURE_WIDTH * scale * 0.12f;
        float right = left + PICTURE_WIDTH * scale;
        leftFade = new LinearGradient(left, 0f, left + fade, 0f, ROOM, 0x00060504, Shader.TileMode.CLAMP);
        rightFade = new LinearGradient(right - fade, 0f, right, 0f, 0x00060504, ROOM, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;
        canvas.drawColor(ROOM);
        if (picture == null) return;
        float scale = scale(height);
        float left = left(width, height);
        target.set(left, 0f, left + PICTURE_WIDTH * scale, height);
        // the light dims every colour, the blue and the green a little more: a dim bulb is a warm one
        float g = Math.max(0f, level);
        gain.setScale(g, (float) Math.pow(g, 1.07), (float) Math.pow(g, 1.2), 1f);
        picturePaint.setColorFilter(new ColorMatrixColorFilter(gain));
        canvas.drawBitmap(picture, null, target, picturePaint);
        // on a wide screen the picture fades into the room at its sides
        if (left > 0f && leftFade != null) {
            float fade = PICTURE_WIDTH * scale * 0.12f;
            float right = left + PICTURE_WIDTH * scale;
            edgePaint.setShader(leftFade);
            canvas.drawRect(left - 1f, 0f, left + fade, height, edgePaint);
            edgePaint.setShader(rightFade);
            canvas.drawRect(right - fade, 0f, right + 1f, height, edgePaint);
        }
    }
}
