/*
 * Copyright (c) 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import app.hushpinterest.extension.pinterest.actions.DownloadLedger;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/** The private request history. Files and active jobs remain under Android's control. */
@SuppressWarnings("deprecation")
public final class DownloadHistoryPreference extends DialogPreference {
    private Session session;

    public DownloadHistoryPreference(Context context) {
        super(HushPinterestPreferenceFragment.themed(context));
        setKey("action_download_history");
        setPersistent(false);
        setTitle(L10n.t(context, "Download history"));
        setSummary(L10n.t(context, "Check recent downloads started by HushPinterest."));
        setDialogTitle(getTitle());
        setNegativeButtonText(L10n.t(context, "Close"));
        setPositiveButtonText(L10n.t(context, "Open Downloads"));
    }

    @Override protected void onBindView(View view) {
        super.onBindView(view);
        HushPinterestPreferenceFragment.showAllText(view);
        ScreenColors.row(view, this);
        view.setAccessibilityDelegate(new HushPinterestPreferenceFragment.RowSemantics(this, Button.class));
    }

    @Override protected View onCreateDialogView() {
        session = new Session();
        return session.content;
    }

    @Override protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        session.message.setVisibility(View.VISIBLE);
        session.message.setText(L10n.t(getContext(), "Checking download history..."));
    }

    @Override protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        builder.setNeutralButton(L10n.t(getContext(), "Refresh"), null);
    }

    @Override protected void showDialog(Bundle state) {
        try {
            super.showDialog(state);
            Session opened = session;
            opened.dialog = (AlertDialog) getDialog();
            ScreenColors.dialog(opened.dialog);
            opened.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> opened.refresh(null));
            opened.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (opened.alive()) DownloadLedger.openDownloads(getContext());
            });
            opened.refresh(null);
        } catch (RuntimeException failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "open native download history", failure);
            if (getDialog() != null) getDialog().dismiss();
            session = null;
            Utils.showToastLong(L10n.t(getContext(), "Couldn't check Downloads. Try again."));
        }
    }

    @Override protected void onDialogClosed(boolean positiveResult) {
        Session closed = session;
        session = null;
        if (closed != null && closed.jobDialog != null) closed.jobDialog.dismiss();
        super.onDialogClosed(positiveResult);
    }

    @Override protected void onPrepareForRemoval() {
        onActivityDestroy();
        super.onPrepareForRemoval();
    }

    private boolean retryEnabled() {
        return Utils.settingsReady()
                && PatchFamily.Capability.PIN_DOWNLOADS.installed() && Settings.DOWNLOAD_PINS.get();
    }

    private String pinName(DownloadLedger.Job job) {
        return L10n.f(getContext(), "Pin %s", L10n.isolate(job.pinId));
    }

    private String detail(DownloadLedger.Job job) {
        Date created = new Date(job.createdAt);
        Context dated = L10n.localized(getContext());
        String time = DateFormat.getDateFormat(dated).format(created) + " "
                + DateFormat.getTimeFormat(dated).format(created);
        String reason = job.reasonText();
        return L10n.isolate(time) + "\n" + job.statusText() + (reason.isEmpty() ? "" : "\n" + reason);
    }

    /** One open screen. A callback from a dismissed or replaced screen can't change its successor. */
    private final class Session {
        final LinearLayout content = new LinearLayout(getContext());
        final TextView message = new TextView(getContext());
        final ListView list = new ListView(getContext());
        List<DownloadLedger.Job> jobs = Collections.emptyList();
        AlertDialog dialog;
        AlertDialog jobDialog;
        boolean busy;

        Session() {
            content.setOrientation(LinearLayout.VERTICAL);
            message.setId(android.R.id.message);
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            message.setTextColor(ScreenColors.DEFAULT.summary);
            message.setSingleLine(false);
            message.setMaxLines(Integer.MAX_VALUE);
            message.setEllipsize(null);
            message.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            message.setLineSpacing(dp(2), 1);
            message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            messageStyle(true);
            content.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            list.setId(android.R.id.list);
            list.setDivider(null);
            list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            list.setVisibility(View.GONE);
            // The native list scrolls through the bounded history without pushing actions offscreen.
            int height = Math.min(dp(320), getContext().getResources().getDisplayMetrics().heightPixels / 3);
            content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            list.setOnItemClickListener((parent, view, position, id) -> {
                if (!busy && position >= 0 && position < jobs.size()) refresh(jobs.get(position).id);
            });
        }

        boolean alive() { return session == this && dialog != null && dialog.isShowing(); }

        void messageStyle(boolean empty) {
            int inset = dp(ScreenColors.SIDE_DP);
            int vertical = dp(empty ? 24 : 12);
            message.setPaddingRelative(inset, vertical, inset, vertical);
            message.setMinHeight(empty ? dp(144) : 0);
            message.setGravity(empty ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
            message.setTextAlignment(empty ? View.TEXT_ALIGNMENT_CENTER : View.TEXT_ALIGNMENT_VIEW_START);
        }

        void working() {
            busy = true;
            list.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            message.setText(L10n.t(getContext(), "Checking download history..."));
        }

        void refresh(Long chooseId) {
            if (!alive() || busy) return;
            working();
            DownloadLedger.refresh(getContext(), found -> {
                if (!alive()) return;
                try {
                    jobs = found == null ? Collections.emptyList() : new ArrayList<>(found);
                    CharSequence[] names = new CharSequence[jobs.size()];
                    CharSequence[] details = new CharSequence[jobs.size()];
                    for (int i = 0; i < jobs.size(); i++) {
                        names[i] = pinName(jobs.get(i));
                        details[i] = detail(jobs.get(i));
                    }
                    list.setAdapter(new ChoiceCards(ScreenColors.DEFAULT, names, details));
                    list.setVisibility(jobs.isEmpty() ? View.GONE : View.VISIBLE);
                    messageStyle(jobs.isEmpty());
                    message.setText(L10n.t(getContext(), found == null ? "Couldn't check Downloads. Try again."
                            : jobs.isEmpty() ? "No HushPinterest downloads in this history."
                            : jobs.stream().anyMatch(job -> job.id < 0)
                                ? "Only the 32 most recent downloads and selection results are listed. Saved files are kept."
                                : "Only the 32 most recent HushPinterest requests are listed. Manage active downloads in Downloads."));
                } catch (RuntimeException failure) {
                    HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "show native download history", failure);
                    jobs = Collections.emptyList();
                    list.setVisibility(View.GONE);
                    messageStyle(true);
                    message.setText(L10n.t(getContext(), "Couldn't check Downloads. Try again."));
                } finally {
                    busy = false;
                    list.setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                }
                if (chooseId != null) {
                    for (DownloadLedger.Job job : jobs) if (job.id == chooseId) { showJob(job); break; }
                }
            });
        }

        void showJob(DownloadLedger.Job job) {
            if (!alive() || busy || jobDialog != null) return;
            boolean enabled = retryEnabled();
            boolean retry = job.canRetry() && enabled;
            List<CharSequence> names = new ArrayList<>();
            List<CharSequence> details = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            // Which choices open something else (the chevron): Open file, Share file, Set as and
            // Open pin do, the two that change the history act at once.
            List<Boolean> opens = new ArrayList<>();
            if (retry) {
                names.add(L10n.t(getContext(), "Retry download"));
                details.add(L10n.t(getContext(), "Starts a new download. Android manages it."));
                actions.add(() -> change(job.id, true));
                opens.add(false);
            }
            if (job.canOpen() && enabled) {
                names.add(L10n.t(getContext(), "Open file"));
                details.add(L10n.t(getContext(), "Opens the saved file in an app that can show it."));
                actions.add(() -> DownloadLedger.openFile(getContext(), job));
                opens.add(true);
                names.add(L10n.t(getContext(), "Share file"));
                details.add(L10n.t(getContext(), "Sends the saved file to another app."));
                actions.add(() -> DownloadLedger.shareFile(getContext(), job));
                opens.add(true);
            }
            if (job.canSetAs() && enabled) {
                names.add(L10n.t(getContext(), "Set as wallpaper"));
                details.add(L10n.t(getContext(), "Opens Android's Set as options for this saved image."));
                actions.add(() -> DownloadLedger.setAs(getContext(), job));
                opens.add(true);
            }
            names.add(L10n.t(getContext(), "Open pin"));
            details.add(job.state == DownloadLedger.State.FAILED
                    ? L10n.t(getContext(), "Open the pin again to get a fresh download link.") : null);
            actions.add(() -> DownloadLedger.reopenPin(getContext(), job));
            opens.add(true);
            names.add(L10n.t(getContext(), "Remove from history"));
            details.add(L10n.t(getContext(), "Removes this history entry. Files and active downloads are kept."));
            actions.add(() -> change(job.id, false));
            opens.add(false);
            boolean[] chevrons = new boolean[opens.size()];
            for (int i = 0; i < chevrons.length; i++) chevrons[i] = opens.get(i);

            LinearLayout header = new LinearLayout(getContext());
            header.setOrientation(LinearLayout.VERTICAL);
            header.setPaddingRelative(dp(ScreenColors.SIDE_DP), dp(20), dp(ScreenColors.SIDE_DP), dp(12));
            TextView title = new TextView(getContext());
            title.setText(pinName(job));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setTextColor(ScreenColors.DEFAULT.title);
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
            title.setEllipsize(null);
            title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            title.setAccessibilityHeading(true);
            header.addView(title);
            TextView status = new TextView(getContext());
            status.setId(android.R.id.message);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            status.setSingleLine(false);
            status.setMaxLines(Integer.MAX_VALUE);
            status.setEllipsize(null);
            status.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            status.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            status.setLineSpacing(dp(2), 1);
            status.setPaddingRelative(0, dp(6), 0, 0);
            String guidance = "";
            if (!enabled && (job.canRetry() || job.canOpen() || job.canSetAs())) {
                if (!PatchFamily.Capability.PIN_DOWNLOADS.installed()) {
                    guidance = L10n.f(getContext(), "This build doesn't include the parts that handle %1$s.",
                            L10n.t(getContext(), "Download pins"));
                } else {
                    guidance = L10n.t(getContext(), job.canRetry()
                            ? "Resume HushPinterest and turn on Download pins to retry."
                            : "Resume HushPinterest and turn on Download pins to use this.");
                }
            }
            status.setText(detail(job) + (guidance.isEmpty() ? "" : "\n" + guidance));
            status.setTextColor(ScreenColors.DEFAULT.summary);
            header.addView(status);
            try {
                AlertDialog opened = new AlertDialog.Builder(getContext()).setCustomTitle(header)
                        .setAdapter(new ChoiceCards(ScreenColors.DEFAULT, names.toArray(new CharSequence[0]),
                                details.toArray(new CharSequence[0]), chevrons), (choice, position) -> {
                            if (!alive() || busy || jobDialog != choice) return;
                            if (position >= 0 && position < actions.size()) actions.get(position).run();
                        })
                        .setPositiveButton(L10n.t(getContext(), "Open Downloads"), (choice, which) -> {
                            if (alive() && jobDialog == choice) DownloadLedger.openDownloads(getContext());
                        })
                        .setNegativeButton(L10n.t(getContext(), "Cancel"), null).create();
                jobDialog = opened;
                opened.setOnDismissListener(ignored -> { if (jobDialog == opened) jobDialog = null; });
                opened.create();
                opened.getListView().setDivider(null);
                opened.getListView().setSelector(new ColorDrawable(Color.TRANSPARENT));
                ScreenColors.dialog(opened);
                opened.show();
            } catch (RuntimeException failure) {
                if (jobDialog != null) jobDialog.dismiss();
                jobDialog = null;
                HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "open native download history entry", failure);
                Utils.showToastLong(L10n.t(getContext(), "Couldn't check Downloads. Try again."));
            }
        }

        void change(long id, boolean retry) {
            if (!alive() || busy) return;
            working();
            Runnable after = () -> {
                if (!alive()) return;
                busy = false;
                refresh(null);
            };
            if (retry) DownloadLedger.retry(getContext(), id, after);
            else DownloadLedger.removeHistory(getContext(), id, after);
        }

        int dp(int value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    getContext().getResources().getDisplayMetrics()));
        }
    }
}
