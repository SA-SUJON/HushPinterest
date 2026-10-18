/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** Leaves a file's last-modified time out of its row, as an app that doesn't keep one does. */
    static volatile boolean hidesModified;
    /** Last-modified times to report by file name, on the clock the code under test reads. Others report the file's own. */
    static final Map<String, Long> modified = new ConcurrentHashMap<>();
    /** Empties the file, then refuses the write, as a disk that fills up mid-open does. */
    static volatile boolean truncatesThenRefuses;
    /** Holds each question named in [stalls] until it's counted down, ignoring the cancel signal as a stuck app does. */
    static volatile CountDownLatch stall;
    /** The questions the stall holds: "query", "write", "read", "close" (of a file opened for writing) or "delete". */
    static volatile Set<String> stalls = Set.of();
    /** A permit for each question the stall holds, given once its cancel listener is in place. */
    static final Semaphore holding = new Semaphore(0);
    /** Cancel signals received by held questions. */
    static final AtomicInteger cancels = new AtomicInteger();
    /** Set when any question reached the app on the main thread. */
    static volatile boolean askedOnMain;

    private static final AtomicInteger installs = new AtomicInteger();
    private static File folder;

    /** Registers the app and forgets every file and fault. */
    static void install() {
        refusesTruncate = false;
        refusesWrite = false;
        refusesRead = false;
        refusesDelete = false;
        hidesSize = false;
        hidesModified = false;
        modified.clear();
        truncatesThenRefuses = false;
        stall = null;
        stalls = Set.of();
        holding.drainPermits();
        cancels.set(0);
        askedOnMain = false;
        folder = new File(RuntimeEnvironment.getApplication().getCacheDir(),
                "pin-lists-" + System.nanoTime() + "-" + installs.incrementAndGet());
        if (!folder.mkdirs() && !folder.isDirectory()) throw new IllegalStateException("No folder for the test files");
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

    /** Notes a question asked on the main thread, and holds it when the test stalls [step]. */
    private static void ask(String step, CancellationSignal signal) {
        if (Looper.myLooper() == Looper.getMainLooper()) askedOnMain = true;
        CountDownLatch held = stall;
        if (held == null || !stalls.contains(step)) return;
        if (signal != null) signal.setOnCancelListener(cancels::incrementAndGet);
        holding.release();
        try {
            if (!held.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("The test never released the " + step);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the " + step);
        }
    }

    /** ContentResolver.openAssetFileDescriptor arrives here for writing, carrying the caller's cancel signal. */
    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        ask("write", signal);
        AssetFileDescriptor file = super.openAssetFile(uri, mode, signal);
        if (file == null || !stalls.contains("close")) return file;
        // The caller's file wraps this one, so its close lands here, as one an app holds onto does.
        ParcelFileDescriptor held = new ParcelFileDescriptor(file.getParcelFileDescriptor()) {
            @Override public void close() throws IOException {
                ask("close", null);
                super.close();
            }
        };
        return new AssetFileDescriptor(held, file.getStartOffset(), file.getDeclaredLength());
    }

    /** ContentResolver sends an "r" open here as a typed open of any type. */
    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String filter, Bundle opts, CancellationSignal signal)
            throws FileNotFoundException {
        ask("read", signal);
        return super.openTypedAssetFile(uri, filter, opts, signal);
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
        ask("delete", null);
        if (refusesDelete) throw new UnsupportedOperationException("Delete not supported");
        Uri uri = extras.getParcelable("uri");
        File file = new File(folder, uri.getLastPathSegment());
        if (file.exists() && !file.delete()) throw new IllegalStateException("The file stayed");
        return null;
    }

    /** A file's size and last-modified time, the way a document app answers them. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order,
                        CancellationSignal signal) {
        ask("query", signal);
        return query(uri, projection, selection, args, order);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED});
        String name = uri.getLastPathSegment();
        File file = new File(folder, name);
        if (!hidesSize) cursor.addRow(new Object[]{file.length(), hidesModified ? null : modified.getOrDefault(name, file.lastModified())});
        return cursor;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return PinManifest.MIME_TYPE; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
