package net.micode.notes.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import net.micode.notes.R;
import net.micode.notes.data.NotePinning;
import net.micode.notes.data.Notes;
import net.micode.notes.data.NotesDatabaseHelper;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.QuickReminder;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NoteEditActivity;
import net.micode.notes.ui.NoteItemData;
import net.micode.notes.ui.NotesListActivity;
import net.micode.notes.ui.NotesListItem;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;

/** Exercises production provider, SQLite migration, list binding and editor coexistence. */
public class PinInstrumentation extends Instrumentation {
    private int passed;
    private Context context;
    private ContentResolver resolver;
    private long first, second;
    private Bundle args;

    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    private void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        passed++;
        Bundle progress = new Bundle();
        progress.putString("stream", "PASS " + name + "\n");
        sendStatus(0, progress);
    }
    private void main(Runnable action) { runOnMainSync(action); waitForIdleSync(); }
    private HashSet<Long> ids(Long... values) { return new HashSet<Long>(Arrays.asList(values)); }
    private long create(String text) {
        WorkingNote note = WorkingNote.createEmptyNote(context, Notes.ID_ROOT_FOLDER,
                AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_WIDGET_INVALIDE, ResourceParser.YELLOW);
        note.setWorkingText(text);
        if (!note.saveNote()) throw new AssertionError("Cannot save fixture");
        return note.getNoteId();
    }
    private long value(long id, String column) {
        Cursor c = resolver.query(Notes.CONTENT_NOTE_URI, new String[] { column },
                Notes.NoteColumns.ID + "=?", new String[] { String.valueOf(id) }, null);
        try { if (!c.moveToFirst()) throw new AssertionError("Missing note"); return c.getLong(0); }
        finally { c.close(); }
    }
    private void shot(String name) throws Exception {
        // Let transient toasts disappear so screenshots reflect the current state.
        Thread.sleep(3500);
        File directory = new File(context.getExternalFilesDir(null), "pin-evidence");
        directory.mkdirs();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Cannot take screenshot");
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally { bitmap.recycle(); }
    }
    private void migration(int version) {
        final File target = new File(context.getCacheDir(), "pin-migration-" + System.nanoTime() + ".db");
        Context isolated = new ContextWrapper(context) {
            @Override public File getDatabasePath(String name) { return target; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(target, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(target.getPath(), factory, handler);
            }
        };
        // Generate a complete v4 database from the production v5 CREATE TABLE SQL.
        NotesDatabaseHelper helper = new NotesDatabaseHelper(isolated);
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(target, null);
        try {
            Field sql = NotesDatabaseHelper.class.getDeclaredField("CREATE_NOTE_TABLE_SQL");
            sql.setAccessible(true);
            String create = ((String) sql.get(null)).replace(
                    ",pinned INTEGER NOT NULL DEFAULT 0", "");
            if (version <= 3) create = create.replace(
                    ",version INTEGER NOT NULL DEFAULT 0", "");
            if (version <= 2) create = create.replace(
                    ",gtask_id TEXT NOT NULL DEFAULT ''", "");
            old.execSQL(create);
            Field dataSql = NotesDatabaseHelper.class.getDeclaredField("CREATE_DATA_TABLE_SQL");
            dataSql.setAccessible(true); old.execSQL((String) dataSql.get(null));
            old.execSQL("INSERT INTO note (_id,snippet,modified_date) VALUES (101,'keep old text',12345)");
            old.execSQL("INSERT INTO data (_id,mime_type,note_id,content) VALUES (201,'vnd.android.cursor.item/text_note',101,'keep old body')");
            old.setVersion(version);
            old.close();
            SQLiteDatabase upgraded = helper.getWritableDatabase();
            check("v" + version + "-to-v5-version", upgraded.getVersion() == 5);
            Cursor c = upgraded.rawQuery("SELECT snippet,modified_date,pinned FROM note WHERE _id=101", null);
            try {
                check("v" + version + "-row-preserved", c.moveToFirst() && "keep old text".equals(c.getString(0)));
                check("v" + version + "-edit-time-preserved", c.getLong(1) == 12345);
                check("v" + version + "-default-unpinned", c.getInt(2) == 0);
            } finally { c.close(); }
            c = upgraded.rawQuery("SELECT content FROM data WHERE _id=201", null);
            try { check("v" + version + "-body-preserved", c.moveToFirst() && "keep old body".equals(c.getString(0))); }
            finally { c.close(); }
        } catch (Exception e) { throw new RuntimeException(e); }
        finally { if (old.isOpen()) old.close(); helper.close(); SQLiteDatabase.deleteDatabase(target); }
    }
    private void listAndActions() throws Exception {
        Intent intent = new Intent(context, NotesListActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        NotesListActivity activity = (NotesListActivity) startActivitySync(intent);
        Thread.sleep(800); waitForIdleSync();
        shot("01-pinned-with-reminder");
        // Invoke the production row binding with the adapter's real query projection.
        Field projection = NoteItemData.class.getDeclaredField("PROJECTION");
        projection.setAccessible(true);
        Cursor c = resolver.query(Notes.CONTENT_NOTE_URI, (String[]) projection.get(null),
                "_id=?", new String[] { String.valueOf(first) }, null);
        try {
            check("list-projection-has-pinned", c.moveToFirst());
            final NoteItemData data = new NoteItemData(context, c);
            check("list-model-pinned", data.isPinned());
            main(() -> {
                NotesListItem row = new NotesListItem(activity);
                row.bind(activity, data, false, false);
                check("separate-pin-icon-visible", row.findViewById(R.id.iv_pin_icon).getVisibility() == View.VISIBLE);
                check("reminder-icon-still-visible", row.findViewById(R.id.iv_alert_icon).getVisibility() == View.VISIBLE);
            });
        } finally { c.close(); }
        // The real multi-choice selection and async Activity handler, not a test-only update.
        Field adapterField = NotesListActivity.class.getDeclaredField("mNotesListAdapter");
        adapterField.setAccessible(true);
        final net.micode.notes.ui.NotesListAdapter adapter =
                (net.micode.notes.ui.NotesListAdapter) adapterField.get(activity);
        final ListView list = activity.findViewById(R.id.notes_list);
        final int[] position = { -1 };
        main(() -> {
            for (int i = 0; i < adapter.getCount(); i++) if (adapter.getItemId(i) == first) position[0] = i;
            check("selected-note-in-real-list", position[0] >= 0);
            View row = adapter.getView(position[0], null, list);
            activity.onItemLongClick(list, row, position[0], first);
            try {
                Field callback = NotesListActivity.class.getDeclaredField("mModeCallBack");
                callback.setAccessible(true);
                android.view.MenuItem.OnMenuItemClickListener listener =
                        (android.view.MenuItem.OnMenuItemClickListener) callback.get(activity);
                check("unpin-menu-dispatch", listener.onMenuItemClick(new android.widget.PopupMenu(
                        activity, list).getMenu().add(0, R.id.unpin, 0, "unpin")));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(800); waitForIdleSync();
        check("activity-unpin-async-handler", value(first, Notes.NoteColumns.PINNED) == 0);
        main(activity::finish);
    }
    @Override public void onStart() {
        context = getTargetContext(); resolver = context.getContentResolver();
        Bundle result = new Bundle();
        try {
            if (args != null && "restart".equals(args.getString("mode"))) {
                long id = context.getSharedPreferences("pin_acceptance", 0).getLong("id", 0);
                check("pin-survives-process-restart", id > 0 && value(id, Notes.NoteColumns.PINNED) == 1);
                check("restart-edit-time-preserved", value(id, Notes.NoteColumns.MODIFIED_DATE)
                        == context.getSharedPreferences("pin_acceptance", 0).getLong("edited", -1));
                result.putString("stream", "RESTART ACCEPTANCE: " + passed + " passed\n");
                finish(Activity.RESULT_OK, result); return;
            }
            migration(2); migration(3); migration(4);
            first = create("Leader pin acceptance"); second = create("Second pin acceptance");
            check("new-note-default-unpinned", value(first, Notes.NoteColumns.PINNED) == 0);
            long edited = value(first, Notes.NoteColumns.MODIFIED_DATE);
            long syncFlag = value(first, Notes.NoteColumns.LOCAL_MODIFIED);
            check("single-pin-count", NotePinning.setPinned(resolver, ids(first), true) == 1);
            check("single-pin-persisted", value(first, Notes.NoteColumns.PINNED) == 1);
            check("pin-preserves-edit-time", value(first, Notes.NoteColumns.MODIFIED_DATE) == edited);
            check("pin-preserves-sync-flag", value(first, Notes.NoteColumns.LOCAL_MODIFIED) == syncFlag);
            check("batch-pin-count", NotePinning.setPinned(resolver, ids(first, second), true) == 2);
            check("batch-pin-persisted", value(second, Notes.NoteColumns.PINNED) == 1);
            check("batch-unpin-count", NotePinning.setPinned(resolver, ids(first, second), false) == 2);
            check("batch-unpin-persisted", value(first, Notes.NoteColumns.PINNED) == 0
                    && value(second, Notes.NoteColumns.PINNED) == 0);
            check("empty-selection", NotePinning.setPinned(resolver, ids(), true) == 0);
            check("system-folders-rejected", NotePinning.setPinned(resolver, ids(0L, -1L, -2L, -3L), true) == 0);
            ContentValues folder = new ContentValues(); folder.put(Notes.NoteColumns.TYPE, Notes.TYPE_FOLDER);
            long folderId = android.content.ContentUris.parseId(resolver.insert(Notes.CONTENT_NOTE_URI, folder));
            check("ordinary-folder-rejected", NotePinning.setPinned(resolver, ids(folderId), true) == 0);
            ContentValues trash = new ContentValues(); trash.put(Notes.NoteColumns.PARENT_ID, Notes.ID_TRASH_FOLER);
            resolver.update(android.content.ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, second), trash, null, null);
            check("trashed-note-rejected", NotePinning.setPinned(resolver, ids(second), true) == 0);
            check("trashed-note-stays-unpinned", value(second, Notes.NoteColumns.PINNED) == 0);
            trash.put(Notes.NoteColumns.PARENT_ID, Notes.ID_ROOT_FOLDER);
            resolver.update(android.content.ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, second), trash, null, null);
            NotePinning.setPinned(resolver, ids(first), true);
            ContentValues alert = new ContentValues();
            alert.put(Notes.NoteColumns.ALERTED_DATE, System.currentTimeMillis() + 7200000L);
            resolver.update(android.content.ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, first), alert, null, null);
            Cursor order = resolver.query(Notes.CONTENT_NOTE_URI, new String[] { "_id" },
                    "_id IN (?,?)", new String[] { String.valueOf(first), String.valueOf(second) }, NotePinning.SORT_ORDER);
            try { check("pinned-sorts-before-newer", order.moveToFirst() && order.getLong(0) == first); }
            finally { order.close(); }
            order = resolver.query(Notes.CONTENT_NOTE_URI, new String[] { "_id" },
                    "_id IN (?,?)", new String[] { String.valueOf(first), String.valueOf(folderId) }, NotePinning.SORT_ORDER);
            try { check("folders-remain-before-pinned-notes", order.moveToFirst() && order.getLong(0) == folderId); }
            finally { order.close(); }
            resolver.delete(android.content.ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, folderId), null, null);
            listAndActions();
            NotePinning.setPinned(resolver, ids(first), true);
            Intent editIntent = new Intent(context, NoteEditActivity.class).setAction(Intent.ACTION_VIEW)
                    .putExtra(Intent.EXTRA_UID, first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            NoteEditActivity editor = (NoteEditActivity) startActivitySync(editIntent);
            main(() -> {
                ((EditText) editor.findViewById(R.id.note_edit_view)).setText("abc123\nXYZ");
                TextView counter = editor.findViewById(R.id.tv_word_count);
                check("A-word-count-preserved", counter.getText().toString().equals(editor.getString(R.string.note_word_count, 9)));
            });
            final long reminderTime = QuickReminder.calculate(QuickReminder.Preset.IN_TWO_HOURS,
                    System.currentTimeMillis(), java.util.TimeZone.getDefault());
            main(() -> {
                try {
                    Method reminder = NoteEditActivity.class.getDeclaredMethod("applyReminder", long.class);
                    reminder.setAccessible(true); reminder.invoke(editor, reminderTime);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            check("B-reminder-saved-on-pinned-note", value(first, Notes.NoteColumns.ALERTED_DATE) == reminderTime);
            check("reminder-preserves-pinned-state", value(first, Notes.NoteColumns.PINNED) == 1);
            shot("02-pinned-note-word-count-reminder");
            main(() -> editor.onOptionsItemSelected(new android.widget.PopupMenu(editor,
                    editor.getWindow().getDecorView()).getMenu().add(0, R.id.menu_delete_remind, 0, "cancel")));
            check("B-cancel-reminder-on-pinned-note", value(first, Notes.NoteColumns.ALERTED_DATE) == 0);
            check("cancel-preserves-pinned-state", value(first, Notes.NoteColumns.PINNED) == 1);
            main(editor::finish);
            NotePinning.setPinned(resolver, ids(first), true);
            context.getSharedPreferences("pin_acceptance", 0).edit().putLong("id", first)
                    .putLong("edited", value(first, Notes.NoteColumns.MODIFIED_DATE)).commit();
            result.putString("stream", "PIN ACCEPTANCE: " + passed + " passed; retained fixture id=" + first + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL after " + passed + ": " + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
