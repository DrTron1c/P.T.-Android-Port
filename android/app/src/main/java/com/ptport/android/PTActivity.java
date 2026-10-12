package com.ptport.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

import java.io.File;

/**
 * The game: SDL's activity, which loads libSDL3.so and then libmain.so and calls SDL_main in it.
 *
 * The game draws into a surface of a fixed size smaller than the screen, and Android's compositor scales it up to the display
 * for free. A tablet's screen is 3000 pixels wide or more; the game's frame is built from many full-screen passes, so at that size
 * a mobile GPU ran it at a few frames a second. The size is chosen in the settings (SettingsPanel); 1080 lines by default.
 *
 * The settings panel opens over the game when the game's touch overlay SET button is pressed: the game sends the message
 * COMMAND_SETTINGS (SDL_SendAndroidMessage) and onUnhandledMessage shows it. Once a second the game sends COMMAND_FRAME_STATS, which
 * feeds the frame counter and the automatic picture size.
 *
 * The game runs in its own process (the manifest's android:process=":game"), so that leaving the game returns to the menu and
 * starting it again begins a fresh process: SDL cannot run its main function twice in one process. onDestroy ends the process.
 */
public class PTActivity extends SDLActivity {
    /** The height, in pixels, of the picture the game draws; 0 draws at the screen's own size. */
    static final int DEFAULT_RENDER_HEIGHT = 1080;
    /** The frame limit the game's first start sets (ApplyDeviceDefaults), until pt.ini has one. */
    static final int DEFAULT_FRAME_RATE = 45;
    /** The message the game sends to open the settings panel (SDL's user commands start at 0x8000). */
    static final int COMMAND_SETTINGS = 0x8001;
    /** The message the game sends once a second: the frame rate x10 (12 bits), GPU ms x4 (9 bits) and CPU ms x4 (9 bits) of a frame. */
    static final int COMMAND_FRAME_STATS = 0x8002;
    /** The picture sizes the automatic size moves between. */
    private static final int[] AUTO_STEPS = { 480, 540, 630, 720, 810, 900, 1080, 1260, 1440 };
    private static final int AUTO_TOP = 1440;

    /** The running game, for the settings panel's live changes (null when the game is not open). */
    private static PTActivity running;
    private View settingsPanel;

    // the frame counter and the automatic picture size
    private TextView counterView;
    private int counterMode;
    private boolean autoSize;
    private int frameTarget = DEFAULT_FRAME_RATE;
    private long frameTargetRead;
    private final long started = SystemClock.uptimeMillis();
    private int autoHeight;
    private int autoBad;
    private int autoGood;
    private long autoQuietUntil;
    private int autoBlockedHeight;
    private long autoBlockedUntil;

    /** The picture height chosen in the settings; 0 is the screen's own size. */
    static int renderHeight(Context context) {
        return Math.max(0, AppPrefs.getInt(context, AppPrefs.RENDER_HEIGHT, DEFAULT_RENDER_HEIGHT));
    }

    static void setRenderHeight(Context context, int height) {
        AppPrefs.putInt(context, AppPrefs.RENDER_HEIGHT, Math.max(0, height));
    }

    /**
     * The frame rate the screen is asked to match: the game's own frame limit, from what waits in android-settings.txt for the game
     * to take, else from pt.ini; 45 before the game has written one (what its first start sets). 0 asks for nothing.
     */
    static int frameRateTarget(Context context) {
        try {
            File dir = new File(context.getFilesDir(), "pt-runtime/data");
            File pending = new File(dir, "android-settings.txt");
            if (pending.isFile()) {
                for (String line : readText(pending).split("\r?\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).trim().equals("fps_limit")) return Math.max(0, Integer.parseInt(line.substring(eq + 1).trim()));
                }
            }
            File ini = new File(dir, "pt.ini");
            if (!ini.isFile()) return DEFAULT_FRAME_RATE;
            boolean display = false;
            for (String line : readText(ini).split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    display = trimmed.equals("[display]");
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (display && eq > 0 && trimmed.substring(0, eq).trim().equals("fps_limit")) {
                    return Math.max(0, Integer.parseInt(trimmed.substring(eq + 1).trim()));
                }
            }
            return 0;
        } catch (RuntimeException e) {
            return DEFAULT_FRAME_RATE;
        }
    }

    private static String readText(File file) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] bytes = new byte[(int) Math.min(file.length(), 65536)];
                int got = 0;
                while (got < bytes.length) {
                    int n = in.read(bytes, got, bytes.length - got);
                    if (n < 0) break;
                    got += n;
                }
                return new String(bytes, 0, got, "UTF-8");
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /**
     * Tells Android how fast the game draws so the display can run at that rate or a multiple of it: a game at 45 frames a second on
     * a screen that stays at 144 shows frames for 3 refreshes and 4 refreshes by turns, which looks choppy; at 144 or 90 it is even.
     * Android 11 and later; a request for 0 takes the hint away.
     */
    static void hintFrameRate(Surface surface, int fps) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || surface == null || !surface.isValid()) return;
        try {
            surface.setFrameRate((float) fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (RuntimeException ignored) {
            // a surface that went away
        }
    }

    static void applyFrameRate(Context context) {
        final PTActivity activity = running;
        if (activity == null) return;
        final int fps = frameRateTarget(context);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mSurface != null) hintFrameRate(mSurface.getHolder().getSurface(), fps);
            }
        });
    }

    /** The surface size for the chosen picture height: {width, height}, or {0, 0} for the screen's own size. */
    static int[] fixedSizeFor(Context context) {
        return fixedSizeFor(context, renderHeight(context));
    }

    static int[] fixedSizeFor(Context context, int wanted) {
        int width = 0;
        int height = 0;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
            int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
            if (wanted > 0 && shortSide > wanted + 16) {
                // landscape: the long side is the width. Both sides even.
                height = wanted & ~1;
                width = (int) Math.round((double) longSide * wanted / shortSide) & ~1;
            }
        } catch (RuntimeException ignored) {
            width = 0;
            height = 0;
        }
        return new int[] { width, height };
    }

    /** Takes the picture size chosen in the settings into the running game at once (the game sees a window of the new size). */
    static void applyPictureSize(Context context) {
        final PTActivity activity = running;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // the chosen size is the top of the automatic range: it starts again from there
                activity.autoHeight = 0;
                activity.autoBad = 0;
                activity.autoGood = 0;
                activity.autoQuietUntil = SystemClock.uptimeMillis() + 8000;
                if (mSurface instanceof FixedSurface) {
                    int[] size = fixedSizeFor(activity);
                    ((FixedSurface) mSurface).resize(size[0], size[1]);
                }
            }
        });
    }

    /** The frame counter and the automatic size were changed in the settings: the running game reads them again. */
    static void applyOverlay(Context context) {
        final PTActivity activity = running;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.readOverlayPrefs();
            }
        });
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        running = this;
        // a game on a touch screen is not touched for long stretches while the stick is held or the pad is used
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // the game writes its frame limit into pt.ini a moment after it starts (its first start sets one): the hint follows it
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                applyFrameRate(PTActivity.this);
            }
        }, 4000);
        readOverlayPrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFrameRate(this);
    }

    @Override
    protected void onDestroy() {
        if (running == this) running = null;
        final boolean leaving = isFinishing();
        super.onDestroy();
        // SDL runs the game's main function once per process (its native state is not reset), so the game's process ends with the
        // game and the menu's next start gets a new one. A normal exit (status 0): the crash report does not take it for a crash.
        if (leaving) Runtime.getRuntime().exit(0);
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        int[] size = fixedSizeFor(context);
        return new FixedSurface(context, size[0], size[1]);
    }

    @Override
    protected boolean onUnhandledMessage(int command, Object param) {
        if (command == COMMAND_SETTINGS) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    toggleSettings();
                }
            });
            return true;
        }
        if (command == COMMAND_FRAME_STATS && param instanceof Integer) {
            final int packed = (Integer) param;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onFrameStats(packed);
                }
            });
            return true;
        }
        return super.onUnhandledMessage(command, param);
    }

    @Override
    public void onBackPressed() {
        if (settingsPanel != null) {
            closeSettings();
            return;
        }
        super.onBackPressed();
    }

    // ---- the frame counter ----

    private void readOverlayPrefs() {
        counterMode = AppPrefs.getInt(this, AppPrefs.FPS_COUNTER, 0);
        autoSize = AppPrefs.getBool(this, AppPrefs.AUTO_SIZE, false);
        if (!autoSize) {
            // leaving the automatic size puts the chosen one back
            if (autoHeight != 0) applyPictureSize(this);
            autoHeight = 0;
        }
        frameTarget = frameRateTarget(this);
        frameTargetRead = SystemClock.uptimeMillis();
        if (counterMode == 0 && counterView != null && mLayout != null) {
            mLayout.removeView(counterView);
            counterView = null;
        }
    }

    private void showCounter(float fps, float gpu, float cpu) {
        if (counterMode == 0 || mLayout == null) return;
        if (counterView == null) {
            float density = getResources().getDisplayMetrics().density;
            counterView = new TextView(this);
            counterView.setTextSize(13);
            counterView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            counterView.setShadowLayer(3f, 1f, 1f, Color.BLACK);
            counterView.setBackgroundColor(0x80000000);
            counterView.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));
            counterView.setGravity(Gravity.START);
            counterView.setClickable(false);
            counterView.setFocusable(false);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            params.setMargins((int) (10 * density), (int) (8 * density), 0, 0);
            mLayout.addView(counterView, params);
        }
        float target = frameTarget > 0 ? frameTarget : 60f;
        int color = fps >= target * 0.95f ? 0xFF7CFF8A : fps >= target * 0.8f ? 0xFFFFE066 : 0xFFFF6B5E;
        counterView.setTextColor(color);
        StringBuilder text = new StringBuilder();
        text.append(Math.round(fps)).append(" fps");
        if (counterMode >= 2) {
            text.append("\nGPU ").append(String.format(java.util.Locale.US, "%.1f", gpu)).append(" ms  CPU ")
                    .append(String.format(java.util.Locale.US, "%.1f", cpu)).append(" ms");
            if (mSurface instanceof FixedSurface) {
                FixedSurface surface = (FixedSurface) mSurface;
                if (surface.fixedWidth > 0) {
                    text.append("\n").append(surface.fixedWidth).append("x").append(surface.fixedHeight).append(autoSize ? " auto" : "");
                }
            }
        }
        counterView.setText(text.toString());
    }

    // ---- the automatic picture size ----

    private void onFrameStats(int packed) {
        float fps = (packed & 0xFFF) / 10f;
        float gpu = ((packed >> 12) & 0x1FF) / 4f;
        float cpu = ((packed >> 21) & 0x1FF) / 4f;
        long now = SystemClock.uptimeMillis();
        if (now - frameTargetRead > 5000) {
            frameTarget = frameRateTarget(this);
            frameTargetRead = now;
        }
        showCounter(fps, gpu, cpu);
        if (autoSize) adjustPictureSize(fps, gpu, now);
    }

    /**
     * Lowers the picture one step when the game ran below its frame rate with the GPU as the cause for four seconds in a row, and raises
     * it a step after twenty seconds of ease, never above the size chosen in the settings. A size that just failed is not tried again
     * for 90 seconds. Every change rebuilds the game's render targets (a short pause), so changes are rare.
     */
    private void adjustPictureSize(float fps, float gpu, long now) {
        if (now - started < 25000 || now < autoQuietUntil || settingsPanel != null) return;
        int cap = renderHeight(this);
        if (cap <= 0 || cap > AUTO_TOP) cap = AUTO_TOP;
        if (autoHeight <= 0 || autoHeight > cap) autoHeight = cap;
        float target = frameTarget > 0 ? frameTarget : 60f;
        float interval = 1000f / Math.max(fps, 1f);
        boolean struggling = fps < target * 0.93f && gpu > 0.78f * interval;
        boolean easy = fps >= target * 0.97f && gpu < 0.55f * (1000f / target);
        if (struggling) {
            autoBad++;
            autoGood = 0;
        } else if (easy) {
            autoGood++;
            autoBad = 0;
        } else {
            autoBad = 0;
            autoGood = 0;
        }
        int next = 0;
        if (autoBad >= 4) {
            for (int step : AUTO_STEPS) {
                if (step < autoHeight) next = step;
            }
            if (next > 0) {
                autoBlockedHeight = autoHeight;
                autoBlockedUntil = now + 90000;
            }
        } else if (autoGood >= 20) {
            for (int step : AUTO_STEPS) {
                if (step > autoHeight && step <= cap) {
                    next = step;
                    break;
                }
            }
            if (next == 0 && autoHeight < cap) next = cap;
            if (next > 0 && next >= autoBlockedHeight && now < autoBlockedUntil) next = 0;
        }
        autoBad = next > 0 || autoBad >= 4 ? 0 : autoBad;
        autoGood = next > 0 || autoGood >= 20 ? 0 : autoGood;
        if (next > 0 && mSurface instanceof FixedSurface) {
            autoHeight = next;
            autoQuietUntil = now + 10000;
            int[] size = fixedSizeFor(this, next);
            ((FixedSurface) mSurface).resize(size[0], size[1]);
        }
    }

    private void toggleSettings() {
        if (settingsPanel != null) {
            closeSettings();
            return;
        }
        if (mLayout == null) return;
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int pad = (int) (14 * density);

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(0xF0101010);
        // the panel takes the touches over it, the game keeps the rest of the screen
        frame.setClickable(true);
        frame.setPadding(pad, pad, pad, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button menu = new Button(this);
        menu.setText("Main menu");
        menu.setAllCaps(false);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                leaveGame();
            }
        });
        header.addView(menu, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closeSettings();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(new SettingsPanel(this).createContent());
        frame.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = Math.min(screenWidth, Math.max((int) (screenWidth * 0.46f), (int) (440 * density)));
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        mLayout.addView(frame, params);
        settingsPanel = frame;
    }

    /**
     * Back to the main menu: after a question, since a tap on the wrong button should not end the game. finish() makes SDLActivity send the game
     * its quit (the game saves, shuts down and ends its process); the menu is the screen under this one.
     */
    private void leaveGame() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Back to the main menu?")
                .setMessage("The game saves as you play. Anything since the last save is lost.")
                .setNegativeButton("Stay", null)
                .setPositiveButton("Leave", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    private void closeSettings() {
        if (settingsPanel != null && mLayout != null) mLayout.removeView(settingsPanel);
        settingsPanel = null;
    }

    /**
     * SDL's surface with a fixed buffer size. SDL turns a touch into a position between 0 and 1 by dividing it by the surface's size,
     * but the view the touch is reported in still has the screen's size: a touch here is scaled to the buffer's size first.
     */
    static final class FixedSurface extends SDLSurface {
        int fixedWidth;
        int fixedHeight;

        FixedSurface(Context context, int width, int height) {
            super(context);
            resize(width, height);
        }

        void resize(int width, int height) {
            fixedWidth = width;
            fixedHeight = height;
            if (width > 0 && height > 0) {
                // SDL adds the surface to its layout without layout parameters, which makes it wrap its content, and a surface
                // with a fixed size wraps to exactly that size: a 1620x1080 picture in a corner of the screen. Filling the
                // layout is what makes Android scale the fixed-size buffer up to the screen.
                setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                getHolder().setFixedSize(width, height);
            } else {
                getHolder().setSizeFromLayout();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.surfaceChanged(holder, format, width, height);
            // a new surface has no frame rate hint
            hintFrameRate(holder.getSurface(), frameRateTarget(getContext()));
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int viewWidth = getWidth();
            int viewHeight = getHeight();
            if (fixedWidth > 0 && viewWidth > 0 && viewHeight > 0 && (viewWidth != fixedWidth || viewHeight != fixedHeight)) {
                MotionEvent scaled = MotionEvent.obtain(event);
                try {
                    Matrix matrix = new Matrix();
                    matrix.setScale((float) fixedWidth / viewWidth, (float) fixedHeight / viewHeight);
                    scaled.transform(matrix);
                    return super.onTouch(view, scaled);
                } finally {
                    scaled.recycle();
                }
            }
            return super.onTouch(view, event);
        }
    }
}
