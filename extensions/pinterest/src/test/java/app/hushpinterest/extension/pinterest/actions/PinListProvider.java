/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/** The document app behind a file picked for Save list, and the ways a real one turns a request down. */
public final class PinListProvider extends ContentProvider {
    static final String AUTHORITY = "app.hushpinterest.test.pinlists";

    /** Answers "wt" with a refusal and "w" without truncating, which the ContentResolver docs allow. */
    static volatile boolean refusesTruncate;
    /** Refuses every open for writing. */
    static volatile boolean refusesWrite;
    /** Refuses every open for reading. */
    static volatile boolean refusesRead;
    /** Turns down DocumentsContract.deleteDocument, as a provider without delete support does. */
    static volatile boolean refusesDelete;
    /** Answers a query with no rows, so a file's size can't be read. */
    static volatile boolean hidesSize;
    /** Empties the file, then refuses the write, as a disk that fills up mid-open does. */
    static volatile boolean truncatesThenRefuses;

    private static File folder;

    /** Registers the app and forgets every file and fault. */
    static void install() {
        refusesTruncate = false;
        refusesWrite = false;
        refusesRead = false;
        refusesDelete = false;
        hidesSize = false;
        truncatesThenRefuses = false;
        folder = new File(RuntimeEnvironment.getApplication().getCacheDir(), "pin-lists-" + System.nanoTime());
        if (!folder.mkdirs()) throw new IllegalStateException("No folder for the test files");
        Robolectric.setupContentProvider(PinListProvider.class, AUTHORITY);
    }

    /** The address of a file named [name], holding [bytes] when they're given. */
    static Uri put(String name, byte[] bytes) throws IOException {
        if (bytes != null) {
            try (FileOutputStream out = new FileOutputStream(new File(folder, name))) {
                out.write(bytes);
            }
        }
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    /** What the file at [uri] holds now, or null when nothing was ever written there. */
    static byte[] get(Uri uri) throws IOException {
        File file = new File(folder, uri.getLastPathSegment());
        return file.exists() ? Files.readAllBytes(file.toPath()) : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = new File(folder, uri.getLastPathSegment());
        if (mode.startsWith("r")) {
            if (refusesRead) throw new SecurityException("This app doesn't hand its files back");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (refusesWrite) throw new FileNotFoundException("The folder is full");
        if (truncatesThenRefuses) {
            try {
                new FileOutputStream(file).close();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            throw new FileNotFoundException("The disk filled up");
        }
        if ("wt".equals(mode) && refusesTruncate) throw new IllegalArgumentException("Unsupported mode: wt");
        int flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE;
        if ("wt".equals(mode)) flags |= ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(file, flags);
    }

    /** DocumentsContract.deleteDocument arrives here as its "android:deleteDocument" call. */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"android:deleteDocument".equals(method)) return null;
        if (refusesDelete) throw new UnsupportedOperationException("Delete not supported");
        Uri uri = extras.getParcelable("uri");
        File file = new File(folder, uri.getLastPathSegment());
        if (file.exists() && !file.delete()) throw new IllegalStateException("The file stayed");
        return null;
    }

    /** A file's size, as OpenableColumns.SIZE, the way a document app answers it. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
        if (!hidesSize) cursor.addRow(new Object[]{new File(folder, uri.getLastPathSegment()).length()});
        return cursor;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return PinManifest.MIME_TYPE; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
