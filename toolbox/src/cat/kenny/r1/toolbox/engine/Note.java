// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

/** Names the nearest equal temperament note for a chosen concert pitch. */
public final class Note {
    private static final String[] NAMES = {
        "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"
    };
    public final String name;
    public final int octave;
    public final float cents;
    public final float targetHz;

    private Note(int midi, float cents, float targetHz) {
        name = NAMES[Math.floorMod(midi, 12)];
        octave = Math.floorDiv(midi, 12) - 1;
        this.cents = cents;
        this.targetHz = targetHz;
    }

    public static Note of(float hz, float a4Hz) {
        if (!Float.isFinite(hz) || !Float.isFinite(a4Hz) || hz <= 0 || a4Hz <= 0) {
            throw new IllegalArgumentException("positive finite frequencies required");
        }
        double midi = 69 + 12 * Math.log((double) hz / a4Hz) / Math.log(2);
        int nearest = (int) Math.round(midi);
        float target = (float) (a4Hz * Math.pow(2, (nearest - 69) / 12.0));
        return new Note(nearest, (float) ((midi - nearest) * 100), target);
    }
}
