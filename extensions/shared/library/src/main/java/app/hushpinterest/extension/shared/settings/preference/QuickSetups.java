/*
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on https://github.com/SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushpinterest.extension.shared.settings.preference;

import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushpinterest.extension.shared.settings.BooleanSetting;

/**
 * Quick setups: a set of switches flipped in one tap. Each choice is a map from switch to the
 * value it puts there; which switches go in which choice is the app's call, in its own class.
 *
 * <p>Every change goes through the switch's row, the same as a tap on it, so the page's own
 * listener saves it, keeps the rows that depend on it in step and notes a restart when the switch
 * needs one. A switch whose patch isn't in this build has no row and is left alone.
 */
@SuppressWarnings("deprecation")
public final class QuickSetups {
    private QuickSetups() {
    }

    /** Each of [settings] turned on. */
    public static Map<BooleanSetting, Boolean> allOn(List<BooleanSetting> settings) {
        Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
        for (BooleanSetting setting : settings) values.put(setting, Boolean.TRUE);
        return Collections.unmodifiableMap(values);
    }

    /** Each of [settings] back to the value it started with. */
    public static Map<BooleanSetting, Boolean> defaults(List<BooleanSetting> settings) {
        Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
        for (BooleanSetting setting : settings) values.put(setting, setting.defaultValue);
        return Collections.unmodifiableMap(values);
    }

    /** What one choice did to the switches it set out to change. */
    public static final class Outcome {
        /** Switches now on the value asked for: the row, the setting and the store all answer it. */
        public final int accepted;
        /** Switches that were asked to change and aren't on the value asked for. */
        public final int failed;
        /**
         * False when a switch that failed is left with its row, its setting and the store answering
         * different things, which the page can only show right once it's opened again.
         */
        public final boolean settled;

        Outcome(int accepted, int failed, boolean settled) {
            this.accepted = accepted;
            this.failed = failed;
            this.settled = settled;
        }
    }

    /**
     * Sets each switch row on [screen] named in [values] that shows something else, and answers
     * what became of them. A row that already shows its value counts as neither.
     *
     * <p>Call on the main thread. There the store tells the page's listener before setChecked
     * returns, and the listener can leave a change out or put the saved value back, so a switch
     * is accepted only when its row, its setting and the store all answer the value asked for.
     * The store is what the preferences answer now: the write to disk is the framework's own and
     * may still be on its way.
     *
     * <p>No switch says anything of its own while this runs. The listener's toast for a change it
     * had to recover is held back, so the caller has to say what the answer holds.
     */
    public static Outcome apply(PreferenceGroup screen, Map<BooleanSetting, Boolean> values) {
        int accepted = 0;
        int failed = 0;
        boolean settled = true;
        AbstractPreferenceFragment.recoveryToastHeld = true;
        try {
            for (Map.Entry<BooleanSetting, Boolean> value : values.entrySet()) {
                BooleanSetting setting = value.getKey();
                Preference row = screen.findPreference(setting.key);
                if (!(row instanceof TwoStatePreference) || value.getValue() == null) continue;
                TwoStatePreference toggle = (TwoStatePreference) row;
                boolean wanted = value.getValue();
                if (toggle.isChecked() == wanted) continue;
                toggle.setChecked(wanted);
                boolean shown = toggle.isChecked();
                boolean typed = setting.savedValue();
                boolean stored = setting.persistedValue();
                if (shown == wanted && typed == wanted && stored == wanted) {
                    accepted++;
                } else {
                    failed++;
                    if (shown != typed || typed != stored) settled = false;
                }
            }
        } finally {
            AbstractPreferenceFragment.recoveryToastHeld = false;
        }
        return new Outcome(accepted, failed, settled);
    }
}
