/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/** Draws production dialog windows, including their parent windows, at the settings preview size. */
public final class NativeDialogCapture {
    private static final int WIDTH = 780;
    private static final int HEIGHT = 1688;

    private NativeDialogCapture() { }

    /**
     * Call from a NATIVE graphics test after its state has settled. This deliberately doesn't idle
     * the main looper: a batch-progress capture must not finish the batch while laying out its UI.
     */
    public static void capture(String name, View parent, Dialog... dialogs) throws Exception {
        File folder = new File("build/reports/settings-design");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(image);
            canvas.drawColor(0xff000000);
            if (parent != null) {
                parent.measure(exact(WIDTH), exact(HEIGHT));
                parent.layout(0, 0, WIDTH, HEIGHT);
                parent.jumpDrawablesToCurrentState();
                parent.draw(canvas);
            }
            for (Dialog dialog : dialogs) {
                assertNotNull("no dialog for " + name, dialog);
                assertTrue("dialog isn't showing for " + name, dialog.isShowing());
                View content = dialog.getWindow().getDecorView();
                int width = content.getWidth() > 0 ? Math.min(WIDTH, content.getWidth()) : WIDTH;
                content.measure(exact(width), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.AT_MOST));
                content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
                content.jumpDrawablesToCurrentState();
                assertTrue("dialog has no height for " + name, content.getHeight() > 0);
                assertTrue("dialog exceeds the viewport for " + name, content.getHeight() <= HEIGHT);
                assertReadable(content);
                canvas.drawColor(0x99000000);
                canvas.save();
                canvas.translate((WIDTH - content.getWidth()) / 2f, (HEIGHT - content.getHeight()) / 2f);
                content.draw(canvas);
                canvas.restore();
            }
            try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            image.recycle();
        }
    }

    /** Visible text must have real native line measurements and keep every displayed character. */
    public static void assertReadable(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.length() > 0) {
                Layout layout = text.getLayout();
                assertNotNull("text wasn't laid out: " + text.getText(), layout);
                int availableWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                // Editable fields deliberately use a wider layout when their contents can scroll.
                boolean scrollingInput = text instanceof EditText && layout.getWidth() > availableWidth;
                for (int line = 0; line < layout.getLineCount(); line++) {
                    assertEquals("text was ellipsized: " + text.getText(), 0, layout.getEllipsisCount(line));
                    if (!scrollingInput) {
                        assertTrue("text exceeds its horizontal bounds on line " + line + ": " + text.getText(),
                                layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= availableWidth + 0.5f);
                    }
                }
                assertTrue("text is taller than its view: " + text.getText(),
                        layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertReadable(group.getChildAt(i));
        }
    }

    private static int exact(int pixels) {
        return View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY);
    }
}
