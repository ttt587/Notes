/* Licensed under the Apache License, Version 2.0. */
package net.micode.notes.data;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.OperationApplicationException;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Set;

/** Local list ordering. Pinning does not change note text, edit time or sync flags. */
public final class NotePinning {
    public static final String SORT_ORDER = Notes.NoteColumns.TYPE + " DESC,"
            + Notes.NoteColumns.PINNED + " DESC," + Notes.NoteColumns.MODIFIED_DATE
            + " DESC," + Notes.NoteColumns.ID + " DESC";

    private NotePinning() { }

    public static int setPinned(ContentResolver resolver, Set<Long> ids, boolean pinned)
            throws RemoteException, OperationApplicationException {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        ArrayList<ContentProviderOperation> operations =
                new ArrayList<ContentProviderOperation>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                continue;
            }
            // Recheck type and trash status in the provider, not only in the UI.
            operations.add(ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id))
                    .withSelection(Notes.NoteColumns.TYPE + "=? AND "
                            + Notes.NoteColumns.PARENT_ID + "<>?", new String[] {
                                    String.valueOf(Notes.TYPE_NOTE),
                                    String.valueOf(Notes.ID_TRASH_FOLER) })
                    .withValue(Notes.NoteColumns.PINNED, pinned ? 1 : 0).build());
        }
        if (operations.isEmpty()) {
            return 0;
        }
        ContentProviderResult[] results = resolver.applyBatch(Notes.AUTHORITY, operations);
        int updated = 0;
        for (ContentProviderResult result : results) {
            if (result.count != null) {
                updated += result.count;
            }
        }
        return updated;
    }
}
