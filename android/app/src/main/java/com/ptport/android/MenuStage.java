package com.ptport.android;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The main menu's layout. Everything is placed by the picture's own measures (it is 1672 x 941 pixels; the logo, the entries and the line
 * about the save sit where they do in the title screen it comes from), scaled to the screen's height. The entries and the line about the
 * save grow a little on a small screen, so they stay easy to read and to touch, and the rows move apart by half as much.
 */
final class MenuStage extends ViewGroup {
    // the picture's measures, in its own pixels
    private static final float LOGO_X = 119f;
    private static final float LOGO_Y = 79f;
    private static final float LOGO_WIDTH = 258f;
    private static final float LOGO_HEIGHT = 388f;
    private static final float TEXT_X = 190f;
    private static final float RULE_BEFORE_TEXT = 22f;
    private static final float ENTRY_SIZE = 28f;
    private static final float NOTE_SIZE = 16.5f;
    private static final float ROW_WIDTH = 480f;
    private static final float ROW_PITCH = 68f;
    private static final float NOTE_BASELINE = 593f;
    // the baselines of Start, New game, Load save and Settings
    private static final float[] BASELINES = { 559.6f, 653.6f, 721.6f, 789.6f };
    // the smallest the entries get, so they can be read and touched
    private static final float MIN_ENTRY_DP = 14f;

    private final MenuBackdrop backdrop;
    private final ImageView logo;
    private final MenuEntry[] entries;
    private final TextView note;
    private final View footer;
    private final View loading;

    private float scale;
    private float grow;
    private float spread;

    MenuStage(Context context, MenuBackdrop backdrop, ImageView logo, MenuEntry[] entries, TextView note, View footer, View loading) {
        super(context);
        this.backdrop = backdrop;
        this.logo = logo;
        this.entries = entries;
        this.note = note;
        this.footer = footer;
        this.loading = loading;
        // the rules and the glow of a chosen entry reach past the entry's own box
        setClipChildren(false);
        addView(backdrop);
        addView(logo);
        for (MenuEntry entry : entries) addView(entry);
        addView(note);
        addView(footer);
        addView(loading);
    }

    private static int exact(float pixels) {
        return MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(pixels)), MeasureSpec.EXACTLY);
    }

    private float baseline(float pictureBaseline) {
        return scale * (BASELINES[0] + (pictureBaseline - BASELINES[0]) * spread);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        float density = getResources().getDisplayMetrics().density;
        scale = MenuBackdrop.scale(height);
        grow = Math.max(1f, Math.min(1.3f, MIN_ENTRY_DP * density / (ENTRY_SIZE * scale)));
        spread = 1f + (grow - 1f) * 0.5f;
        float unit = scale * grow;

        backdrop.measure(exact(width), exact(height));
        logo.measure(exact(LOGO_WIDTH * scale), exact(LOGO_HEIGHT * scale));
        for (MenuEntry entry : entries) {
            entry.configure(ENTRY_SIZE * unit, unit, Math.round(RULE_BEFORE_TEXT * scale));
            entry.measure(exact(ROW_WIDTH * unit), exact(ROW_PITCH * scale * spread));
        }
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, NOTE_SIZE * unit);
        note.measure(MeasureSpec.makeMeasureSpec(Math.round(ROW_WIDTH * unit), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
        footer.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        loading.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        float left = MenuBackdrop.left(width, height);
        backdrop.layout(0, 0, width, height);
        int logoLeft = Math.round(left + LOGO_X * scale);
        int logoTop = Math.round(LOGO_Y * scale);
        logo.layout(logoLeft, logoTop, logoLeft + logo.getMeasuredWidth(), logoTop + logo.getMeasuredHeight());

        int entryLeft = Math.round(left + (TEXT_X - RULE_BEFORE_TEXT) * scale);
        for (int i = 0; i < entries.length; ++i) {
            MenuEntry entry = entries[i];
            int top = Math.round(baseline(BASELINES[i]) - entry.getBaseline());
            entry.layout(entryLeft, top, entryLeft + entry.getMeasuredWidth(), top + entry.getMeasuredHeight());
        }
        int noteLeft = Math.round(left + TEXT_X * scale);
        int noteTop = Math.round(baseline(NOTE_BASELINE) - note.getBaseline());
        note.layout(noteLeft, noteTop, noteLeft + note.getMeasuredWidth(), noteTop + note.getMeasuredHeight());

        float density = getResources().getDisplayMetrics().density;
        int footerRight = width - Math.round(14 * density);
        int footerBottom = height - Math.round(6 * density);
        footer.layout(footerRight - footer.getMeasuredWidth(), footerBottom - footer.getMeasuredHeight(), footerRight, footerBottom);
        int loadingRight = width - Math.round(26 * density);
        int loadingBottom = footer.getTop() - Math.round(6 * density);
        loading.layout(loadingRight - loading.getMeasuredWidth(), loadingBottom - loading.getMeasuredHeight(), loadingRight, loadingBottom);
    }
}
