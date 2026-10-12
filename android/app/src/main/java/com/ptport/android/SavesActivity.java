package com.ptport.android;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Load save: the game's current save, what can be done with it, and the backups.
 *
 * The game loads its own save when it starts, so "loading" is choosing which save that is: continue the current one, put a backup
 * back, bring in a save file from another device (a PC save works), or start again. Whatever a choice replaces is kept as a backup
 * first (SaveManager). The current save can also be written out to a file.
 */
public class SavesActivity extends ThemedActivity {
    private static final int REQUEST_IMPORT = 11;
    private static final int REQUEST_EXPORT = 12;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = MenuStyle.dp(this, 20);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        MenuStyle.Entry back = new MenuStyle.Entry(this, "<  Back", 18);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        header.addView(back);
        TextView title = MenuStyle.text(this, "LOAD SAVE", 24, MenuStyle.INK, true);
        title.setLetterSpacing(0.25f);
        title.setPadding(pad, 0, 0, 0);
        header.addView(title);
        column.addView(header);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = Math.min(getResources().getDisplayMetrics().widthPixels, MenuStyle.dp(this, 760));
        root.addView(column, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        refresh();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = MenuStyle.dp(this, 8);
        return params;
    }

    private TextView heading(String text) {
        TextView view = MenuStyle.text(this, text, 14, MenuStyle.AMBER, true);
        view.setLetterSpacing(0.18f);
        view.setPadding(0, MenuStyle.dp(this, 26), 0, 0);
        return view;
    }

    private void refresh() {
        list.removeAllViews();

        list.addView(heading("CURRENT SAVE"));
        final SaveManager.Info info = SaveManager.current(this);
        if (info == null) {
            list.addView(MenuStyle.text(this, SaveManager.hasAnyFile(this)
                    ? "The save file cannot be read (it may be from an older version). The game will start a new one."
                    : "There is no save yet. The game starts a new one and saves as you play.", 15, MenuStyle.INK, true), rowParams());
        } else {
            list.addView(MenuStyle.text(this, info.summary(), 17, MenuStyle.INK, true), rowParams());
            list.addView(MenuStyle.text(this, "Saved " + MenuStyle.when(info.modified), 13, MenuStyle.DIM, true));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(action(info == null ? "Start a new game" : "Continue", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SavesActivity.this, PTActivity.class));
            }
        }));
        if (info != null) {
            actions.addView(action("Back up now", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toast(SaveManager.backup(SavesActivity.this) ? "Backed up." : "Could not back up the save.");
                    refresh();
                }
            }));
            actions.addView(action("Save to a file", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, "PT_Save_Data");
                    startActivityForResult(intent, REQUEST_EXPORT);
                }
            }));
        }
        list.addView(actions, rowParams());

        list.addView(heading("OTHER SAVES"));
        list.addView(MenuStyle.text(this, "A save file from another device (the PC version's PT_Save_Data works) replaces the current one. "
                + "The current one is kept as a backup first.", 13, MenuStyle.DIM, true));
        LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.HORIZONTAL);
        more.addView(action("Choose a save file", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQUEST_IMPORT);
            }
        }));
        more.addView(action("New game", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirm("Start the story again?", "The current save is kept as a backup, so it can be put back from this screen.", "Start again",
                        new Runnable() {
                            @Override
                            public void run() {
                                toast(SaveManager.newGame(SavesActivity.this) ? "Done. The game starts from the beginning." : "Could not clear the save.");
                                refresh();
                            }
                        });
            }
        }));
        list.addView(more, rowParams());

        list.addView(heading("BACKUPS"));
        List<SaveManager.Backup> backups = SaveManager.backups(this);
        if (backups.isEmpty()) {
            list.addView(MenuStyle.text(this, "None yet. A backup is made before a save is replaced, and whenever you tap Back up now.", 13,
                    MenuStyle.DIM, true));
        }
        for (final SaveManager.Backup backup : backups) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, MenuStyle.dp(this, 10), 0, MenuStyle.dp(this, 4));
            row.addView(MenuStyle.text(this, backup.label, 15, MenuStyle.INK, true));
            row.addView(MenuStyle.text(this, backup.info.summary(), 13, MenuStyle.DIM, true));
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.addView(action("Put back", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirm("Put this save back?", "It replaces the current save, which is kept as a backup.", "Put back", new Runnable() {
                        @Override
                        public void run() {
                            toast(SaveManager.restore(SavesActivity.this, backup) ? "Put back." : "Could not put the save back.");
                            refresh();
                        }
                    });
                }
            }));
            buttons.addView(action("Delete", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirm("Delete this backup?", "This cannot be undone.", "Delete", new Runnable() {
                        @Override
                        public void run() {
                            SaveManager.deleteBackup(backup);
                            refresh();
                        }
                    });
                }
            }));
            row.addView(buttons, rowParams());
            list.addView(row);
        }
        View end = new View(this);
        list.addView(end, new LinearLayout.LayoutParams(1, MenuStyle.dp(this, 30)));
    }

    private View action(String label, View.OnClickListener listener) {
        android.widget.Button button = MenuStyle.button(this, label, listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = MenuStyle.dp(this, 8);
        button.setLayoutParams(params);
        return button;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void confirm(String title, String message, String yes, final Runnable then) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        then.run();
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            toast(SaveManager.exportSave(this, uri) ? "Saved to the file." : "Could not write the file.");
        } else if (requestCode == REQUEST_IMPORT) {
            final byte[] bytes = SaveManager.readPicked(this, uri);
            if (bytes == null) {
                toast("That is not a save file of this game (it should be 128 bytes, named PT_Save_Data).");
                return;
            }
            confirm("Use this save?", "It replaces the current save, which is kept as a backup.", "Use it", new Runnable() {
                @Override
                public void run() {
                    toast(SaveManager.importSave(SavesActivity.this, bytes) ? "Done." : "Could not use that file.");
                    refresh();
                }
            });
        }
    }
}
