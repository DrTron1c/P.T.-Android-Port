package com.ptport.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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
 * COMMAND_SETTINGS (SDL_SendAndroidMessage) and onUnhandledMessage shows it.
 */
public class PTActivity extends SDLActivity {
    static final String PREFS = "game";
    static final String KEY_RENDER_HEIGHT = "render_height";
    /** The height, in pixels, of the picture the game draws; 0 draws at the screen's own size. */
    static final int DEFAULT_RENDER_HEIGHT = 1080;
    /** The frame limit the game's first start sets (ApplyDeviceDefaults), until pt.ini has one. */
    static final int DEFAULT_FRAME_RATE = 45;
    /** The message the game sends to open the settings panel (SDL's user commands start at 0x8000). */
    static final int COMMAND_SETTINGS = 0x8001;

    /** The running game, for the settings panel's live changes (null when the game is not open). */
    private static PTActivity running;
    private View settingsPanel;

    static int renderHeight(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return Math.max(0, prefs.getInt(KEY_RENDER_HEIGHT, DEFAULT_RENDER_HEIGHT));
        } catch (RuntimeException e) {
            return DEFAULT_RENDER_HEIGHT;
        }
    }

    static void setRenderHeight(Context context, int height) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_RENDER_HEIGHT, Math.max(0, height)).apply();
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
        int width = 0;
        int height = 0;
        try {
            int wanted = renderHeight(context);
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
                if (mSurface instanceof FixedSurface) {
                    int[] size = fixedSizeFor(activity);
                    ((FixedSurface) mSurface).resize(size[0], size[1]);
                }
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFrameRate(this);
    }

    @Override
    protected void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
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

    private void closeSettings() {
        if (settingsPanel != null && mLayout != null) mLayout.removeView(settingsPanel);
        settingsPanel = null;
    }

    /**
     * SDL's surface with a fixed buffer size. SDL turns a touch into a position between 0 and 1 by dividing it by the surface's size,
     * but the view the touch is reported in still has the screen's size: a touch here is scaled to the buffer's size first.
     */
    static final class FixedSurface extends SDLSurface {
        private int fixedWidth;
        private int fixedHeight;

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
