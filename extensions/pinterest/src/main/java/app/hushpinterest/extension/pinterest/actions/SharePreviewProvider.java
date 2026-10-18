/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hands a saved video to the app it's shared with, read from the MediaStore entry Android filed
 * for the download, together with a frame of it. Android's share sheet asks the shared address
 * itself for a thumbnail, and MediaStore answers none for a video there, so the sheet showed a
 * plain file icon. Nothing here is exported: an app reaches an address only through the read
 * grant a share hands it, and an address names one video entry this app saved, never a collection
 * or another app's file. Two more paths, {@code clip/} and {@code shared/}, serve the pictures Copy
 * image and Share image keep in their own cache folders, read-only and only by the names those
 * features give their files.
 */
public final class SharePreviewProvider extends ContentProvider {
    /** The first path segment, ahead of the MediaStore path it carries. */
    private static final String MEDIA = "media";
    private static final String FRAME_TYPE = "image/jpeg";
    /** The frame's longest side when the asker names no size. */
    private static final int FRAME = 512;
    /**
     * What a query with no projection gets. The share sheet on Android 11 to 13 asks that way and
     * shows a thumbnail only when the flags column offers one.
     */
    private static final String[] DEFAULT_COLUMNS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_FLAGS};

    @Override public boolean onCreate() {
        return true;
    }

    /**
     * The address a saved [file] of type [mime] is shared under: one here for a MediaStore video
     * this app saved, so the share sheet gets a frame, and [file] itself for anything else or when
     * this provider isn't in the app's manifest. It wraps only what the provider will serve.
     */
    static Uri wrap(Context context, Uri file, String mime) {
        if (context == null || !video(mime) || servable(context, file) == null) return file;
        String authority = authority(context);
        if (authority == null) return file;
        Uri.Builder shared = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath(MEDIA);
        for (String segment : file.getPathSegments()) shared.appendPath(segment);
        return shared.build();
    }

    /** This provider's authority as the manifest declares it, or null when it isn't declared. */
    @SuppressWarnings("deprecation")
    private static String authority(Context context) {
        try {
            return context.getPackageManager().getProviderInfo(new ComponentName(context, SharePreviewProvider.class), 0).authority;
        } catch (PackageManager.NameNotFoundException | RuntimeException missing) {
            return null;
        }
    }

    /** The first path segment of an address for a picture Copy image put on the clipboard. */
    private static final String CLIP = "clip";
    /** The same for a picture Share image handed to the share sheet. */
    private static final String SHARED = "shared";

    /** Whether this provider is declared in the app, so a Copy image or Share image address would answer. */
    static boolean serves(Context context) {
        return context != null && authority(context) != null;
    }

    /**
     * The address of the copied picture called [name], or null when the name isn't one Copy image
     * made or this provider isn't declared. The only thing under it is that folder.
     */
    static Uri clipUri(Context context, String name) {
        return cachedUri(context, CLIP, name);
    }

    /** The address of the shared picture called [name], the same way for Share image's folder. */
    static Uri sharedUri(Context context, String name) {
        return cachedUri(context, SHARED, name);
    }

    private static Uri cachedUri(Context context, String folder, String name) {
        if (context == null || CopiedImages.mime(name) == null) return null;
        String authority = authority(context);
        if (authority == null) return null;
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath(folder).appendPath(name).build();
    }

    private static boolean isCached(Uri uri) {
        List<String> path = uri.getPathSegments();
        return !path.isEmpty() && (CLIP.equals(path.get(0)) || SHARED.equals(path.get(0)));
    }

    /** The cached picture [uri] names, or null for any other path, name or a picture already replaced. */
    private File cachedFile(Uri uri) {
        List<String> path = uri.getPathSegments();
        if (path.size() != 2) return null;
        if (CLIP.equals(path.get(0))) return CopiedImages.file(getContext(), path.get(1));
        if (SHARED.equals(path.get(0))) return CopiedImages.sharedFile(getContext(), path.get(1));
        return null;
    }

    private File requireCached(Uri uri) throws FileNotFoundException {
        File picture = cachedFile(uri);
        if (picture == null) throw new FileNotFoundException("No cached image at " + uri);
        return picture;
    }

    /**
     * Name, size and type of a cached picture, for the apps that read them before pasting or
     * attaching. A shared one also offers the thumbnail flag the share sheet reads.
     */
    private Cursor cachedRow(Uri uri, String[] projection) {
        File picture = cachedFile(uri);
        if (picture == null) return null;
        boolean shared = SHARED.equals(uri.getPathSegments().get(0));
        String[] asked = projection != null ? projection
                : shared ? DEFAULT_COLUMNS : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String column : asked) {
            Object value;
            if (OpenableColumns.DISPLAY_NAME.equals(column)) value = picture.getName();
            else if (OpenableColumns.SIZE.equals(column)) value = picture.length();
            else if (MediaStore.MediaColumns.MIME_TYPE.equals(column)) value = CopiedImages.mime(picture.getName());
            else if (shared && DocumentsContract.Document.COLUMN_FLAGS.equals(column)) value = DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;
            else continue;
            if (columns.contains(column)) continue;
            columns.add(column);
            values.add(value);
        }
        MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
        cursor.addRow(values.toArray());
        return cursor;
    }

    private static boolean video(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/");
    }

    /** [file] when it's one MediaStore row, else null. A collection would list every file in it. */
    private static Uri entry(Uri file) {
        if (file == null || !ContentResolver.SCHEME_CONTENT.equals(file.getScheme()) || !MEDIA.equals(file.getAuthority())) return null;
        List<String> path = file.getPathSegments();
        if (path.size() < 2) return null;
        String id = path.get(path.size() - 1);
        if (id.isEmpty() || id.length() > 19) return null;
        for (int i = 0; i < id.length(); i++) if (id.charAt(i) < '0' || id.charAt(i) > '9') return null;
        return file;
    }

    /**
     * [file] when it's one MediaStore row that MediaStore itself types as a video and that this app
     * owns, as Android files the downloads it makes for it, else null. Sharing and serving both
     * ask this, so an address handed out is always one that answers, and a grant can't reach a
     * video some other app saved.
     */
    private static Uri servable(Context context, Uri file) {
        if (entry(file) == null || !video(mediaType(context, file))) return null;
        long caller = Binder.clearCallingIdentity();
        try (Cursor row = context.getContentResolver().query(file,
                new String[]{MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
            return row != null && row.moveToFirst() && context.getPackageName().equals(row.getString(0)) ? file : null;
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(caller);
        }
    }

    /** The MediaStore video [uri] stands for, or null when it names anything else. */
    private Uri target(Uri uri) {
        List<String> path = uri.getPathSegments();
        if (path.size() < 3 || !MEDIA.equals(path.get(0))) return null;
        Uri.Builder media = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(MEDIA);
        for (String segment : path.subList(1, path.size())) media.appendPath(segment);
        return servable(getContext(), media.build());
    }

    private Uri require(Uri uri) throws FileNotFoundException {
        Uri file = target(uri);
        if (file == null) throw new FileNotFoundException("No shared video at " + uri);
        return file;
    }

    /** MediaStore is asked as this app, whoever asked here: the share's grant is what let them in. */
    private static String mediaType(Context context, Uri file) {
        long caller = Binder.clearCallingIdentity();
        try {
            return context.getContentResolver().getType(file);
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(caller);
        }
    }

    private String mediaType(Uri file) {
        return mediaType(getContext(), file);
    }

    @Override public String getType(Uri uri) {
        if (isCached(uri)) {
            File picture = cachedFile(uri);
            return picture == null ? null : CopiedImages.mime(picture.getName());
        }
        Uri file = target(uri);
        return file == null ? null : mediaType(file);
    }

    /**
     * Android 14 and later ask this for a caller without a grant. Only a grant holder learns
     * whether an address names a video.
     */
    @Override public String getTypeAnonymous(Uri uri) {
        return null;
    }

    @Override public String[] getStreamTypes(Uri uri, String filter) {
        if (isCached(uri)) {
            File picture = cachedFile(uri);
            String type = picture == null ? null : CopiedImages.mime(picture.getName());
            return type != null && ClipDescription.compareMimeTypes(type, filter) ? new String[]{type} : null;
        }
        Uri file = target(uri);
        if (file == null) return null;
        List<String> types = new ArrayList<>();
        for (String type : new String[]{mediaType(file), FRAME_TYPE}) {
            if (type != null && ClipDescription.compareMimeTypes(type, filter)) types.add(type);
        }
        return types.isEmpty() ? null : types.toArray(new String[0]);
    }

    /**
     * The file's name and size, as other apps read them for a shared file, and the thumbnail flag
     * the share sheet reads. Columns it doesn't know are left out, as FileProvider does.
     */
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (isCached(uri)) return cachedRow(uri, projection);
        Uri file = target(uri);
        if (file == null) return null;
        String name;
        Long size;
        long caller = Binder.clearCallingIdentity();
        try (Cursor row = getContext().getContentResolver().query(file,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (row == null || !row.moveToFirst()) return null;
            name = row.getString(0);
            size = row.isNull(1) ? null : row.getLong(1);
        } finally {
            Binder.restoreCallingIdentity(caller);
        }
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String column : projection != null ? projection : DEFAULT_COLUMNS) {
            Object value;
            if (OpenableColumns.DISPLAY_NAME.equals(column)) value = name;
            else if (OpenableColumns.SIZE.equals(column)) value = size;
            else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) value = DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;
            else if (MediaStore.MediaColumns.MIME_TYPE.equals(column)) value = mediaType(file);
            else continue;
            if (columns.contains(column)) continue;
            columns.add(column);
            values.add(value);
        }
        MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
        cursor.addRow(values.toArray());
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("A shared video is read only");
        if (isCached(uri)) return ParcelFileDescriptor.open(requireCached(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        Uri file = require(uri);
        long caller = Binder.clearCallingIdentity();
        try {
            ParcelFileDescriptor opened = getContext().getContentResolver().openFileDescriptor(file, "r");
            if (opened == null) throw new FileNotFoundException("MediaStore gave no file for " + uri);
            return opened;
        } finally {
            Binder.restoreCallingIdentity(caller);
        }
    }

    /**
     * The video itself for its own type, and a JPEG frame for an image request, which is how
     * ContentResolver.loadThumbnail asks, at the size it names.
     */
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String filter, Bundle opts) throws FileNotFoundException {
        if (isCached(uri)) {
            File picture = requireCached(uri);
            if (!ClipDescription.compareMimeTypes(CopiedImages.mime(picture.getName()), filter)) {
                throw new FileNotFoundException("No " + filter + " for " + uri);
            }
            return new AssetFileDescriptor(ParcelFileDescriptor.open(picture, ParcelFileDescriptor.MODE_READ_ONLY), 0, picture.length());
        }
        Uri file = require(uri);
        String type = mediaType(file);
        if (type == null) throw new FileNotFoundException("No shared video at " + uri);
        if (ClipDescription.compareMimeTypes(type, filter)) return super.openTypedAssetFile(uri, filter, opts);
        if (!ClipDescription.compareMimeTypes(FRAME_TYPE, filter)) throw new FileNotFoundException("No " + filter + " for " + uri);
        byte[] frame = frame(file, opts);
        // Written whole, opened, then unlinked: the open descriptor keeps it readable, and nothing
        // is left in the cache once the reader closes it.
        File frames = new File(getContext().getCacheDir(), "hushpinterest-share-frames");
        File written = null;
        try {
            // Two first previews at once may both try to make it; either one making it is enough.
            if (!frames.mkdirs() && !frames.isDirectory()) throw new IOException("No folder for " + frames);
            written = File.createTempFile("frame", ".jpg", frames);
            try (OutputStream out = new FileOutputStream(written)) {
                out.write(frame);
            }
            return new AssetFileDescriptor(ParcelFileDescriptor.open(written, ParcelFileDescriptor.MODE_READ_ONLY), 0, frame.length);
        } catch (IOException failed) {
            throw new FileNotFoundException("Couldn't hand over a frame of " + uri + ": " + failed.getMessage());
        } finally {
            if (written != null && !written.delete()) written.deleteOnExit();
        }
    }

    @SuppressWarnings("deprecation")
    private byte[] frame(Uri file, Bundle opts) throws FileNotFoundException {
        Point wanted = opts == null ? null : opts.getParcelable(ContentResolver.EXTRA_SIZE);
        int width = wanted != null && wanted.x > 0 ? wanted.x : FRAME;
        int height = wanted != null && wanted.y > 0 ? wanted.y : FRAME;
        Bitmap picture;
        long caller = Binder.clearCallingIdentity();
        try {
            picture = getContext().getContentResolver().loadThumbnail(file, new Size(width, height), null);
        } catch (IOException | RuntimeException none) {
            throw new FileNotFoundException("No frame for " + file + ": " + none.getMessage());
        } finally {
            Binder.restoreCallingIdentity(caller);
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        picture.compress(Bitmap.CompressFormat.JPEG, 90, jpeg);
        picture.recycle();
        return jpeg.toByteArray();
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("A shared video is read only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("A shared video is read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("A shared video is read only");
    }
}
