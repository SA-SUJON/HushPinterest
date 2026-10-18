/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The cache folder Copy image keeps its picture in. Every file here is one this feature made, named
 * by a random id and the picture's type, so the provider that serves them and the sweep that
 * deletes them both recognize their own files by name and touch nothing else in the cache.
 */
final class CopiedImages {
    private CopiedImages() {}

    static final String FOLDER = "hushpinterest-copied-images";

    /** A finished picture: a random id, then the type the bytes showed. */
    private static final Pattern PICTURE = Pattern.compile("[0-9a-f]{32}\\.(jpg|png|webp|gif)");

    /** A picture still arriving. Never served. */
    private static final Pattern PART = Pattern.compile("[0-9a-f]{32}\\.part");

    /** The folder, made if it isn't there, or null when the cache can't hold one. */
    @Nullable
    static File dir(Context context) {
        File cache = context.getCacheDir();
        if (cache == null) return null;
        File dir = new File(cache, FOLDER);
        if (!dir.mkdirs() && !dir.isDirectory()) return null;
        return dir;
    }

    /** The type a finished picture's name stands for, or null for any other name. */
    @Nullable
    static String mime(String name) {
        if (name == null || !PICTURE.matcher(name).matches()) return null;
        switch (name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
            case "jpg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            default: return null;
        }
    }

    /** The file extension for one of the four types, or null. */
    @Nullable
    static String extension(String mime) {
        if ("image/jpeg".equals(mime)) return ".jpg";
        if ("image/png".equals(mime)) return ".png";
        if ("image/webp".equals(mime)) return ".webp";
        if ("image/gif".equals(mime)) return ".gif";
        return null;
    }

    /** The finished picture called [name], or null when the name isn't one of ours or nothing is there. */
    @Nullable
    static File file(Context context, String name) {
        if (mime(name) == null) return null;
        File dir = dir(context);
        if (dir == null) return null;
        File file = new File(dir, name);
        return file.isFile() ? file : null;
    }

    /** A name for a picture that is still arriving. */
    static String partName(String id) {
        return id + ".part";
    }

    static boolean isPart(String name) {
        return name != null && PART.matcher(name).matches();
    }

    /** Deletes pictures still arriving, left by a copy that died. Nothing else. */
    static void clearParts(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (isPart(file.getName()) && !file.delete() && file.exists()) file.deleteOnExit();
        }
    }

    /**
     * Deletes every picture of ours except [keep], so the newest copy stays readable until a newer one
     * replaces it and the folder never holds more than that one.
     */
    static void keepOnly(File dir, String keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.equals(keep) || (mime(name) == null && !isPart(name))) continue;
            if (!file.delete() && file.exists()) file.deleteOnExit();
        }
    }

    /** How many of our files are in the folder. */
    static int count(File dir) {
        File[] files = dir.listFiles();
        int found = 0;
        if (files != null) {
            for (File file : files) if (mime(file.getName()) != null || isPart(file.getName())) found++;
        }
        return found;
    }
}
