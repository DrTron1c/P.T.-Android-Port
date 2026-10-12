package com.ptport.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * The game: SDL's activity, which loads libSDL3.so and then libmain.so and calls SDL_main in it.
 *
 * The game draws into a surface of a fixed size smaller than the screen, and Android's compositor scales it up to the display
 * for free. A tablet's screen is 3000 pixels wide or more; the game's frame is built from many full-screen passes, so at that size
 * a mobile GPU ran it at a few frames a second. The size is chosen on the tools screen (ToolsActivity); 1080 lines by default.
 */
public class PTActivity extends SDLActivity {
    static final String PREFS = "game";
    static final String KEY_RENDER_HEIGHT = "render_height";
    /** The height, in pixels, of the picture the game draws; 0 draws at the screen's own size. */
    static final int DEFAULT_RENDER_HEIGHT = 1080;

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

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
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
        return new FixedSurface(context, width, height);
    }

    /**
     * SDL's surface with a fixed buffer size. SDL turns a touch into a position between 0 and 1 by dividing it by the surface's size,
     * but the view the touch is reported in still has the screen's size: a touch here is scaled to the buffer's size first.
     */
    static final class FixedSurface extends SDLSurface {
        private final int fixedWidth;
        private final int fixedHeight;

        FixedSurface(Context context, int width, int height) {
            super(context);
            fixedWidth = width;
            fixedHeight = height;
            if (width > 0 && height > 0) getHolder().setFixedSize(width, height);
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
