/*
 * SPDX-FileCopyrightText: The haretic Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.haretic.wallpaper;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.Random;

/**
 * The field of hares of haretic's wallpaper, alive: the still image's faceted hare, the same
 * vertices, each tagged with the part of the body it belongs to, so a pose is the vertices
 * moved and never another drawing. At rest it is the still image, line for line.
 *
 * <p>Each hare settles on a mode (sitting, asleep, drooping) through springs a little
 * under-damped, so ears overshoot and come back, and short events play over that: the ripple of
 * a wheel step, ears perking as the screen wakes, an ear's twitch. A spun wheel sets the whole
 * field running along its rows, coasting to a stop by friction. All of it is arithmetic on a
 * few dozen points a hare; {@link #advance} says whether anything still moves, and nothing
 * needs drawing while nothing does.
 *
 * <p>Lengths are in pixels of the 480 pixel wide screen the image was drawn for and scaled to
 * the surface; times in seconds.
 */
final class HareField {
    private static final int BODY = 0, HEAD = 1, NECK = 2, EAR1 = 3, EAR2 = 4, FORE = 5, HIND = 6;

    /** x, y in the hare's 100 x 100 box (the ground at y = 78) and the part, three by three. */
    private static final float[] OUTLINE = {
        17, 71, BODY, 21, 58, BODY, 31, 46, BODY, 45, 40, BODY, 57, 40, BODY, 61, 33, HEAD,
        45, 9, EAR1, 50, 4, EAR1, 66, 28, HEAD, 62, 3, EAR2, 68, 2, EAR2, 72, 29, HEAD,
        77, 32, HEAD, 83, 38, HEAD, 81, 42, HEAD, 74, 45, HEAD, 68, 49, NECK, 66, 57, BODY,
        69, 68, FORE, 73, 74, FORE, 63, 75, FORE, 56, 72, BODY, 49, 74, HIND, 53, 78, HIND,
        29, 78, HIND, 23, 76, BODY,
    };
    /** The lines across the body, each a run of points. */
    private static final float[][] FACETS = {
        {31, 46, BODY, 41, 62, BODY, 49, 74, HIND}, {41, 62, BODY, 57, 40, BODY},
        {41, 62, BODY, 56, 72, BODY}, {57, 40, BODY, 66, 57, BODY}, {57, 40, BODY, 68, 49, NECK},
        {61, 33, HEAD, 68, 49, NECK}, {61, 33, HEAD, 72, 29, HEAD}, {72, 29, HEAD, 74, 45, HEAD},
        {77, 32, HEAD, 74, 45, HEAD}, {21, 58, BODY, 41, 62, BODY}, {41, 62, BODY, 29, 78, HIND},
        {56, 72, BODY, 66, 57, BODY}, {47.5f, 6.5f, EAR1, 63, 30, HEAD},
        {65, 2.5f, EAR2, 69, 28, HEAD},
    };
    private static final float[] EYE = {73, 34, HEAD, 76, 35, HEAD, 74, 37, HEAD};
    private static final float[] EYE_SHUT = {72.5f, 35.5f, HEAD, 76.5f, 36, HEAD};
    /** Where each ear and the head turn, and the ground the feet stand on. */
    private static final float E1X = 63.5f, E1Y = 30.5f, E2X = 70.5f, E2Y = 28.5f;
    private static final float NECK_X = 64, NECK_Y = 44, GROUND = 78;

    /** What the springs move: ear angles (degrees, negative lays an ear back), the head's nod
     * and drop, the body's width and height about the feet, how bright, the eye open. */
    private static final int EAR_1 = 0, EAR_2 = 1, NOD = 2, HEAD_DY = 3, SX = 4, SY = 5, GLOW = 6,
            EYE_OPEN = 7, KEYS = 8;
    private static final int SIT = 0, PERK = 1, DROOP = 2, SLEEP = 3;
    private static final float[][] MODES = {
        {0, 0, 0, 0, 1, 1, 1, 1},
        {9, 8, -5, -1, 0.99f, 1.03f, 1.35f, 1},
        {-36, -44, 9, 3, 1.02f, 0.95f, 0.5f, 1},
        {-60, -70, 15, 6, 1.05f, 0.86f, 0.62f, 0},
    };

    private static final int HOPLET = 0, PERK_UP = 1, TWITCH = 2, LOOK = 3, HOP = 4;

    /** The image's pattern: a 400 x 352 tile of two hares, turned by -14 degrees and drawn at
     * half size, the hares 1.22 times their box. */
    private static final float TURN = (float) Math.toRadians(-14);
    private static final float COS = (float) Math.cos(TURN), SIN = (float) Math.sin(TURN);
    private static final float HARE_SCALE = 1.22f;
    /** A hare that runs off one end of its row comes back in far off the other. */
    private static final float ROW_START = -1300, ROW_SPAN = 9 * 400;
    private static final int COLOR = 0xFF4F00;

    private static final class Event {
        int kind;
        float start, length, direction;
    }

    private static final class Hare {
        float lx, ly;
        final float[] at = MODES[SIT].clone();
        final float[] speed = new float[KEYS];
        int mode = SIT, next = -1;
        float nextAt;
        final ArrayList<Event> events = new ArrayList<>();
        float phase, breath, run;
        float face = 1, faceSpeed, faceTo = 1, turnAt;
    }

    /** A pose: the springs' values and what running and the events add for this frame. */
    private static final class Pose {
        float ear1, ear2, nod, headDy, foreX, foreY, hindX, lean, sx, sy, pitch, dy, face, glow;
        boolean eyeOpen;
    }

    private final ArrayList<Hare> mHares = new ArrayList<>();
    private final Random mRandom = new Random(1337);
    private final Pose mPose = new Pose();
    private final Path mPath = new Path();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mPoint = new float[2];

    private float mWidth = 480, mHeight = 640, mScale = 1;
    private float mTime;
    private float mScroll, mSpeed;
    private float mTiltX, mTiltY, mTiltNowX, mTiltNowY;
    private boolean mNight, mLow, mCharging;
    private float mFacing = 1, mLastSpin = -1e9f, mNextIdle = 4;
    private float mWheelX = 480, mWheelY = 262, mPortX = 240, mPortY = 640;

    HareField() {
        for (int j = -3; j <= 6; j++) {
            for (int i = -3; i <= 5; i++) {
                for (int k = 0; k < 2; k++) {
                    final Hare hare = new Hare();
                    hare.lx = (k == 0 ? 24 : 224) + 400 * i;
                    hare.ly = (k == 0 ? 0 : 176) + 352 * j + 50;
                    hare.phase = mRandom.nextFloat() * 6.28f;
                    hare.breath = mRandom.nextFloat() * 6.28f;
                    mHares.add(hare);
                }
            }
        }
        mFill.setStyle(Paint.Style.FILL);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeJoin(Paint.Join.ROUND);
        mLine.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * The surface, and where on it the wheel sits and the USB port is: what the wheel does
     * spreads from the one, plugging in from the other.
     */
    void setSize(int width, int height, float wheelX, float wheelY, float portX, float portY) {
        mScale = Math.min(width, height) / 480f;
        mWidth = width / mScale;
        mHeight = height / mScale;
        mWheelX = wheelX / mScale;
        mWheelY = wheelY / mScale;
        mPortX = portX / mScale;
        mPortY = portY / mScale;
    }

    // ---- What happens to it ----

    /** One step of the wheel: a ripple out from it, and a push along the rows. */
    void step(int direction) {
        mSpeed = Math.max(-1100, Math.min(1100, mSpeed + direction * 150));
        mLastSpin = mTime;
        for (Hare hare : mHares) {
            if (place(hare, 80)) {
                add(hare, HOPLET, mTime + distance(mWheelX, mWheelY) / 1100, 0.26f).direction =
                        direction;
            }
        }
    }

    /** The screen came on: ears perk in a wave from the side button. */
    void wake() {
        ripple(mWheelX, mWheelY, 0.9f, 700);
    }

    /** Power: plugged in, a wave from the port, and no drooping while it charges. */
    boolean setCharging(boolean charging, boolean show) {
        if (charging == mCharging) {
            return false;
        }
        mCharging = charging;
        if (charging && show) {
            ripple(mPortX, mPortY, 1.1f, 600);
        }
        retarget(1);
        return true;
    }

    /** Night: they lie down one after another and sleep. */
    void setNight(boolean night) {
        if (night != mNight) {
            mNight = night;
            retarget(1.8f);
        }
    }

    /** A low battery: ears droop and the lines dim. */
    boolean setLow(boolean low) {
        if (low == mLow) {
            return false;
        }
        mLow = low;
        retarget(1.2f);
        return true;
    }

    /** Which way the device leans, -1 to 1 across and along the screen. */
    void setTilt(float x, float y) {
        mTiltX = x;
        mTiltY = y;
    }

    /** Everything where it settles, at once: the picture a still frame shows. */
    void settle() {
        mSpeed = 0;
        mTiltNowX = mTiltX;
        mTiltNowY = mTiltY;
        final int mode = baseMode();
        for (Hare hare : mHares) {
            hare.mode = mode;
            hare.next = -1;
            hare.events.clear();
            System.arraycopy(MODES[mode], 0, hare.at, 0, KEYS);
            java.util.Arrays.fill(hare.speed, 0);
            hare.face = hare.faceTo = mFacing = 1;
            hare.faceSpeed = hare.turnAt = hare.run = 0;
        }
    }

    /** Seconds until one of them does something of its own, when nothing else moves. */
    float untilIdle() {
        return Math.max(0, mNextIdle - mTime);
    }

    /** Asleep, they breathe: slow enough that a few frames a second show it. */
    boolean breathing() {
        return mNight;
    }

    /** Time that passed with nothing moving and nothing drawn. */
    void rest(float seconds) {
        mTime += seconds;
    }

    private int baseMode() {
        return mNight ? SLEEP : mLow && !mCharging ? DROOP : SIT;
    }

    private void retarget(float spread) {
        final int mode = baseMode();
        for (Hare hare : mHares) {
            hare.nextAt = mTime + mRandom.nextFloat() * spread;
            hare.next = mode;
        }
    }

    private void ripple(float fromX, float fromY, float length, float speed) {
        for (Hare hare : mHares) {
            if (place(hare, 80)) {
                add(hare, PERK_UP, mTime + distance(fromX, fromY) / speed, length);
            }
        }
    }

    private Event add(Hare hare, int kind, float start, float length) {
        final Event event = new Event();
        event.kind = kind;
        event.start = start;
        event.length = length;
        hare.events.add(event);
        return event;
    }

    private void idleMoment() {
        int seen = 0;
        for (Hare hare : mHares) {
            if (place(hare, -20)) {
                seen++;
            }
        }
        if (seen == 0) {
            return;
        }
        int pick = mRandom.nextInt(seen);
        for (Hare hare : mHares) {
            if (place(hare, -20) && pick-- == 0) {
                final float r = mRandom.nextFloat();
                if (mNight) {
                    add(hare, TWITCH, mTime, 0.5f);
                } else if (r < 0.45f) {
                    add(hare, TWITCH, mTime, 0.55f);
                } else if (r < 0.7f) {
                    add(hare, LOOK, mTime, 1.7f);
                } else {
                    add(hare, HOP, mTime, 0.42f);
                }
                return;
            }
        }
    }

    // ---- Time ----

    /** {@code dt} seconds on. True while anything still moves, breathing aside. */
    boolean advance(float dt) {
        mTime += dt;
        final float now = mTime;
        boolean busy = false;
        // The field coasts on the wheel's speed and slows by friction
        mScroll += mSpeed * dt;
        mSpeed *= (float) Math.exp(-2.4f * dt);
        if (Math.abs(mSpeed) < 2) {
            mSpeed = 0;
        } else {
            busy = true;
        }
        final float run = Math.min(1, Math.max(0, (Math.abs(mSpeed) - 120) / 380));
        if (run > 0.1f) {
            mFacing = mSpeed > 0 ? 1 : -1;
        } else if (now - mLastSpin > 3.5f) {
            mFacing = 1;
        }
        final float ease = Math.min(1, dt * 6);
        final float dx = mTiltX - mTiltNowX, dy = mTiltY - mTiltNowY;
        mTiltNowX += dx * ease;
        mTiltNowY += dy * ease;
        if (Math.abs(dx) > 0.002f || Math.abs(dy) > 0.002f) {
            busy = true;
        }
        if (now >= mNextIdle) {
            idleMoment();
            mNextIdle = now + (mNight ? 14 : 6) + mRandom.nextFloat() * (mNight ? 16 : 9);
        }
        for (int h = mHares.size() - 1; h >= 0; h--) {
            final Hare hare = mHares.get(h);
            if (hare.next >= 0 && now >= hare.nextAt) {
                hare.mode = hare.next;
                hare.next = -1;
            }
            float perk = 0;
            for (int i = hare.events.size() - 1; i >= 0; i--) {
                final Event event = hare.events.get(i);
                if (now >= event.start + event.length) {
                    hare.events.remove(i);
                } else if (now >= event.start && event.kind == PERK_UP) {
                    perk = Math.max(perk, (float) Math.sin(
                            Math.PI * (now - event.start) / event.length));
                }
            }
            if (hare.mode == SLEEP) {
                perk *= 0.35f;
            }
            final float[] target = MODES[hare.mode];
            for (int k = 0; k < KEYS; k++) {
                final float want = target[k] + (MODES[PERK][k] - MODES[SIT][k]) * perk;
                final float pull = (want - hare.at[k]) * 70 - hare.speed[k] * 10.5f;
                hare.speed[k] += pull * dt;
                hare.at[k] += hare.speed[k] * dt;
                if (Math.abs(want - hare.at[k]) > 0.004f * (Math.abs(want) + 1)
                        || Math.abs(hare.speed[k]) > 0.02f) {
                    busy = true;
                }
            }
            // Turning round is a spring of its own through no width; one hare after another
            if (hare.faceTo != mFacing && hare.turnAt == 0) {
                hare.turnAt = now + mRandom.nextFloat() * (run > 0.1f ? 0.12f : 1.6f);
            }
            if (hare.turnAt != 0 && now >= hare.turnAt) {
                hare.faceTo = mFacing;
                hare.turnAt = 0;
            }
            final float turn = (hare.faceTo - hare.face) * 160 - hare.faceSpeed * 22;
            hare.faceSpeed += turn * dt;
            hare.face += hare.faceSpeed * dt;
            if (Math.abs(hare.faceTo - hare.face) > 0.004f || Math.abs(hare.faceSpeed) > 0.02f
                    || hare.turnAt != 0 || !hare.events.isEmpty()) {
                busy = true;
            }
            hare.phase += Math.abs(mSpeed) * dt * 0.085f;
            hare.run = run;
        }
        return busy;
    }

    // ---- The picture ----

    /** Where the hare's box is on the screen, into mPoint; whether within {@code margin}. */
    private boolean place(Hare hare, float margin) {
        final float lx = floorMod(hare.lx + mScroll * 2 - ROW_START, ROW_SPAN) + ROW_START;
        final float x = (lx * COS - hare.ly * SIN) * 0.5f - 80 + mTiltNowX * 7;
        final float y = (lx * SIN + hare.ly * COS) * 0.5f + mTiltNowY * 7;
        mPoint[0] = x;
        mPoint[1] = y;
        return x > -margin && x < mWidth + margin && y > -margin && y < mHeight + margin;
    }

    /** From the middle of the hare last placed to a point. */
    private float distance(float x, float y) {
        return (float) Math.hypot(mPoint[0] + 61 - x, mPoint[1] + 61 - y);
    }

    private static float floorMod(float value, float span) {
        final float m = value % span;
        return m < 0 ? m + span : m;
    }

    private Pose pose(Hare hare) {
        final Pose p = mPose;
        final float[] at = hare.at;
        final float run = hare.run;
        p.ear1 = at[EAR_1] + mTiltNowX * 7;
        p.ear2 = at[EAR_2] + mTiltNowX * 9;
        p.nod = at[NOD];
        p.headDy = at[HEAD_DY];
        p.foreX = p.foreY = p.hindX = p.pitch = p.dy = 0;
        p.lean = mTiltNowX * 0.09f;
        p.sx = at[SX];
        p.sy = at[SY];
        p.face = hare.face;
        p.glow = at[GLOW];
        p.eyeOpen = at[EYE_OPEN] > 0.5f;
        if (run > 0) {
            // Running: stretched out, ears streaming, a bounding gait
            final float gait = (float) Math.sin(hare.phase), up = Math.abs(gait);
            p.ear1 -= 40 * run + 6 * run * gait;
            p.ear2 -= 46 * run + 8 * run * gait;
            p.nod -= 5 * run;
            p.sx += 0.15f * run;
            p.sy -= 0.09f * run;
            p.pitch = 6 * run * (float) Math.cos(hare.phase);
            p.dy -= 11 * run * up;
            p.foreX = 8 * run * gait;
            p.foreY = -3 * run * up;
            p.hindX = -8 * run * gait;
            p.glow += 0.7f * run;
        }
        if (mNight && hare.mode == SLEEP) {
            p.sy *= 1 + 0.014f * (float) Math.sin(mTime * 1.35f + hare.breath);
        }
        for (int i = hare.events.size() - 1; i >= 0; i--) {
            final Event event = hare.events.get(i);
            final float u = (mTime - event.start) / event.length;
            if (u < 0 || u > 1) {
                continue;
            }
            final float half = (float) Math.sin(Math.PI * u), whole = (float) Math.sin(2 * Math.PI * u);
            switch (event.kind) {
                case HOPLET:
                    p.dy -= 4.5f * half;
                    p.sy *= 1 + 0.05f * whole;
                    p.ear1 += 9 * whole;
                    p.ear2 += 12 * whole;
                    p.glow += 0.5f * half;
                    break;
                case HOP: {
                    // Down, up off the ground, down again on landing
                    final float squash = u < 0.2f ? -0.13f * (float) Math.sin(Math.PI * u / 0.2f)
                            : u > 0.8f ? -0.11f * (float) Math.sin(Math.PI * (u - 0.8f) / 0.2f)
                            : 0.07f * (float) Math.sin(Math.PI * (u - 0.2f) / 0.6f);
                    p.sy *= 1 + squash;
                    p.sx *= 1 - squash * 0.6f;
                    if (u > 0.2f && u < 0.8f) {
                        p.dy -= 15 * (float) Math.sin(Math.PI * (u - 0.2f) / 0.6f);
                    }
                    p.ear1 -= 16 * whole;
                    p.ear2 -= 20 * whole;
                    break;
                }
                case TWITCH:
                    p.ear2 += 17 * half * (float) Math.cos(5 * Math.PI * u);
                    break;
                case LOOK: {
                    final float up = (float) Math.pow(
                            Math.sin(Math.PI * Math.min(1, u * 1.15f)), 0.7);
                    p.nod -= 11 * up;
                    p.ear1 += 7 * up;
                    p.ear2 += 6 * up;
                    break;
                }
                default:
                    break;
            }
        }
        return p;
    }

    /** One vertex through the pose, into mPoint. */
    private void posed(float x, float y, int part, Pose p) {
        if (part == EAR1) {
            turn(x, y, E1X, E1Y, p.ear1);
            x = mPoint[0];
            y = mPoint[1];
        } else if (part == EAR2) {
            turn(x, y, E2X, E2Y, p.ear2);
            x = mPoint[0];
            y = mPoint[1];
        }
        if (part == HEAD || part == EAR1 || part == EAR2) {
            turn(x, y, NECK_X, NECK_Y, p.nod);
            x = mPoint[0];
            y = mPoint[1] + p.headDy;
        } else if (part == NECK) {
            turn(x, y, NECK_X, NECK_Y, p.nod * 0.4f);
            x = mPoint[0];
            y = mPoint[1] + p.headDy * 0.4f;
        } else if (part == FORE) {
            x += p.foreX;
            y += p.foreY;
        } else if (part == HIND) {
            x += p.hindX;
        }
        x += (GROUND - y) * p.lean;
        x = 50 + (x - 50) * p.sx;
        y = GROUND + (y - GROUND) * p.sy;
        turn(x, y, 50, 70, p.pitch);
        mPoint[0] = 50 + (mPoint[0] - 50) * p.face;
        mPoint[1] += p.dy;
    }

    private void turn(float x, float y, float cx, float cy, float degrees) {
        if (degrees == 0) {
            mPoint[0] = x;
            mPoint[1] = y;
            return;
        }
        final double a = Math.toRadians(degrees);
        final float c = (float) Math.cos(a), s = (float) Math.sin(a), dx = x - cx, dy = y - cy;
        mPoint[0] = cx + dx * c - dy * s;
        mPoint[1] = cy + dx * s + dy * c;
    }

    private void run(float[] points, Pose p, boolean close) {
        for (int i = 0; i < points.length; i += 3) {
            posed(points[i], points[i + 1], (int) points[i + 2], p);
            if (i == 0) {
                mPath.moveTo(mPoint[0], mPoint[1]);
            } else {
                mPath.lineTo(mPoint[0], mPoint[1]);
            }
        }
        if (close) {
            mPath.close();
        }
    }

    private static int color(float alpha) {
        return Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24 | COLOR;
    }

    void draw(Canvas canvas) {
        canvas.drawColor(0xFF000000);
        canvas.save();
        canvas.scale(mScale, mScale);
        for (int h = mHares.size() - 1; h >= 0; h--) {
            final Hare hare = mHares.get(h);
            place(hare, 0);
            final float x = mPoint[0], y = mPoint[1];
            if (x < -140 || x > mWidth + 20 || y < -140 || y > mHeight + 50) {
                continue;
            }
            final Pose p = pose(hare);
            final float glow = Math.max(0, p.glow);
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(-14);
            canvas.scale(HARE_SCALE, HARE_SCALE);
            mPath.rewind();
            run(OUTLINE, p, true);
            mFill.setColor(color(0.035f * glow));
            canvas.drawPath(mPath, mFill);
            mLine.setColor(color(0.26f * glow));
            mLine.setStrokeWidth(0.9f);
            canvas.drawPath(mPath, mLine);
            mPath.rewind();
            for (float[] facet : FACETS) {
                run(facet, p, false);
            }
            mLine.setColor(color(0.7f * glow));
            mLine.setStrokeWidth(0.55f);
            canvas.drawPath(mPath, mLine);
            mPath.rewind();
            if (p.eyeOpen) {
                run(EYE, p, true);
                mFill.setColor(color(0.5f * glow));
                canvas.drawPath(mPath, mFill);
            } else {
                run(EYE_SHUT, p, false);
                mLine.setColor(color(0.4f * glow));
                mLine.setStrokeWidth(0.7f);
                canvas.drawPath(mPath, mLine);
            }
            canvas.restore();
        }
        canvas.restore();
    }
}
