/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest;

import static org.junit.Assert.*;

import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeDialogCaptureTest {
    private static final String LONG_LABEL = "A landscape study with warm evening light and a detailed foreground";

    @Test
    public void aSingleLineClippedWithoutAnEllipsisFailsTheHorizontalCheck() {
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        label.setSingleLine(true);
        label.setEllipsize(null);
        layOut(label);

        assertEquals(1, label.getLineCount());
        assertEquals(0, label.getLayout().getEllipsisCount(0));
        assertTrue(label.getLayout().getLineRight(0) > availableWidth(label));
        AssertionError failure = assertThrows(AssertionError.class, () -> NativeDialogCapture.assertReadable(label));
        assertTrue(failure.getMessage(), failure.getMessage().contains("horizontal bounds"));
    }

    @Test
    public void theSameLabelFitsWhenItsTextCanWrap() {
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        label.setSingleLine(false);
        layOut(label);

        assertTrue(label.getLineCount() > 1);
        NativeDialogCapture.assertReadable(label);
    }

    @Test
    public void anEditableFieldMayScrollItsLongSingleLine() {
        EditText input = new EditText(RuntimeEnvironment.getApplication());
        input.setSingleLine(true);
        input.setEllipsize(null);
        layOut(input);

        assertEquals(1, input.getLineCount());
        assertTrue(input.getLayout().getLineRight(0) > availableWidth(input));
        NativeDialogCapture.assertReadable(input);
    }

    private static void layOut(TextView text) {
        text.setText(LONG_LABEL);
        text.setTextSize(20);
        text.setPadding(16, 12, 16, 12);
        text.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.AT_MOST));
        text.layout(0, 0, text.getMeasuredWidth(), text.getMeasuredHeight());
    }

    private static int availableWidth(TextView text) {
        return text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
    }
}
