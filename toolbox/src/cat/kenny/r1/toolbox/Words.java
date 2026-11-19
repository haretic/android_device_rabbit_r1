// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.content.Context;
import android.content.SharedPreferences;

import cat.kenny.r1.toolbox.engine.LevelMath;
import cat.kenny.r1.toolbox.engine.Tally;

import java.util.Locale;

/** Readings as the words and numbers the list and the screens share. */
final class Words {

    private Words() {
    }

    static CharSequence degrees(Context context, float degrees) {
        // No "-0.0": a level reads what it is off by
        final float shown = Math.round(Math.abs(degrees) * 10) / 10f;
        final String text = context.getString(R.string.level_degrees,
                String.format(Locale.getDefault(), "%.1f", shown));
        // The display face has no degree sign and its stand-in sits on the baseline like a
        // letter o: the sign comes from the text face, where it is one
        final android.text.SpannableString out = new android.text.SpannableString(text);
        final int at = text.indexOf('\u00b0');
        if (at >= 0) {
            out.setSpan(new android.text.style.TypefaceSpan("haretic-text"), at, at + 1, 0);
        }
        return out;
    }

    static String tenths(float degrees) {
        return String.format(Locale.getDefault(), "%.1f", Math.round(Math.abs(degrees) * 10) / 10f);
    }

    static int levelRow(LevelSensor sensor) {
        if (!sensor.lying) {
            return R.string.level_upright;
        }
        final float tilt = (float) Math.hypot(sensor.math.tiltX(), sensor.math.tiltY());
        return tilt < 0.15f ? R.string.level_is_flat
                : tilt < 2f ? R.string.level_almost_flat : R.string.level_tilted;
    }

    /** 0 very quiet .. 5 harmful. */
    static int loudness(float db) {
        return db < 30 ? 0 : db < 45 ? 1 : db < 62 ? 2 : db < 78 ? 3 : db < 90 ? 4 : 5;
    }

    private static final int[] SOUND = {R.string.sound_very_quiet, R.string.sound_quiet_room,
            R.string.sound_talking, R.string.sound_busy, R.string.sound_loud,
            R.string.sound_harmful};
    private static final int[] SOUND_ROW = {R.string.sound_row_very_quiet,
            R.string.sound_row_quiet_room, R.string.sound_row_talking, R.string.sound_row_busy,
            R.string.sound_row_loud, R.string.sound_row_harmful};

    static int sound(float db) {
        return SOUND[loudness(db)];
    }

    static int soundRow(float db) {
        return SOUND_ROW[loudness(db)];
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("toolbox", Context.MODE_PRIVATE);
    }

    static Tally tally(Context context) {
        return Tally.restore(prefs(context).getString("tally", ""));
    }

    static void save(Context context, Tally tally) {
        prefs(context).edit().putString("tally", tally.save()).apply();
    }

    // ------------------------------------------------------------------ the settings page's

    /** Keep the screen on: 0 while a tool plays or runs, 1 for as long as a tool is open. */
    static final String SCREEN_ON = "screen_on";
    /** The metronome's beat, one of ClickTrack's sounds */
    static final String CLICK_SOUND = "click_sound";
    /** How loud the tone is: 0 quiet, 1 medium, 2 loud */
    static final String TONE_LEVEL = "tone_level";
    /** The tone's wave, one of ToneRender's */
    static final String TONE_WAVE = "tone_wave";
    /** The level buzzes as it comes to dead level: 1 yes */
    static final String LEVEL_BUZZ = "level_buzz";

    /** The level's zero: 0 forgotten once the level closes, 1 kept until pressed away */
    static final String LEVEL_KEEP_ZERO = "level_keep_zero";
    /** The tuner's needle: 0 the thin marker, 1 large, for reading from arm's length */
    static final String TUNER_NEEDLE = "tuner_needle";

    /** The zeros kept for the level, into its math, where the settings say to keep them. */
    static void restoreZeros(Context context, LevelMath math) {
        if (setting(context, LEVEL_KEEP_ZERO, 2) != 1) {
            return;
        }
        for (int mode = LevelMath.FLAT; mode <= LevelMath.PLUMB; mode++) {
            final String kept = prefs(context).getString("level_zero_" + mode, "");
            final String[] xy = kept.split(",", -1);
            if (xy.length != 2) {
                continue;
            }
            try {
                math.setZero(mode, Float.parseFloat(xy[0]), Float.parseFloat(xy[1]));
            } catch (NumberFormatException e) {
                // Not one this page wrote: left off
            }
        }
    }

    /** Keeps the zero of the level's mode now, or forgets it once it is cleared. */
    static void saveZero(Context context, LevelMath math) {
        final String key = "level_zero_" + math.mode();
        final float[] zero = math.zeroOf(math.mode());
        if (zero == null || setting(context, LEVEL_KEEP_ZERO, 2) != 1) {
            prefs(context).edit().remove(key).apply();
        } else {
            prefs(context).edit().putString(key, zero[0] + "," + zero[1]).apply();
        }
    }

    /** A setting's choice, held to the range it has. */
    static int setting(Context context, String key, int count) {
        final int value = prefs(context).getInt(key, 0);
        return value < 0 || value >= count ? 0 : value;
    }

    /** What a choice of TONE_LEVEL plays at, of full scale. Medium was the one level before. */
    static float toneLevel(Context context) {
        final int level = prefs(context).getInt(TONE_LEVEL, 1);
        return level == 0 ? 0.12f : level == 2 ? 0.5f : 0.25f;
    }

    static int levelMode(Context context) {
        final int mode = prefs(context).getInt("level_mode", LevelMath.FLAT);
        return mode < LevelMath.FLAT || mode > LevelMath.PLUMB ? LevelMath.FLAT : mode;
    }
}
