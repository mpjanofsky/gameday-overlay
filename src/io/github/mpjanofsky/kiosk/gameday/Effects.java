// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.Random;

/**
 * The goal celebration, drawn over the bar: the game ball spins in and hits
 * the scorer's badge, then a slow confetti effect plays over the bar itself.
 * It stays inside the bar's own bounds because the overlay window is no bigger.
 */
final class Effects extends View {
    /** How the confetti plays once the ball lands. */
    static final String SHOWER = "Shower", DRIFT = "Drift", SPARKLE = "Sparkle";

    static final long HIT_MS = 520;
    private static final long BALL_END_MS = HIT_MS + 450, EFFECT_MS = 6500;
    private static final int MAX = 80;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path star = new Path();
    private final Random random = new Random();
    private final float[] ox = new float[MAX], oy = new float[MAX], vx = new float[MAX], vy = new float[MAX], phase = new float[MAX];
    private final float[] spawn = new float[MAX], life = new float[MAX], size = new float[MAX], spin = new float[MAX];
    private final int[] tint = new int[MAX], kind = new int[MAX];
    private int count;
    private ValueAnimator animator;
    private long elapsedMs;
    private Ball ball;
    private String sport;
    private View target;
    private float unit;

    Effects(Context context) { super(context); }

    /** target is the scorer's badge or abbreviation; colors are what the confetti is made of; unit is the bar's text size in px. */
    void play(String sport, View target, int[] colors, float unit, String style) {
        stop();
        this.sport = sport;
        this.target = target;
        this.unit = unit;
        this.ball = new Ball(sport, 1);
        float w = Math.max(getWidth(), unit * 10f), h = Math.max(getHeight(), unit * 2.6f);
        float tx = (target.getLeft() + target.getRight()) / 2f, ty = (target.getTop() + target.getBottom()) / 2f;
        count = 0;
        if (SPARKLE.equals(style)) {
            // A short burst from the badge to mark the hit, then stars twinkling at random spots over the whole bar.
            for (int i = 0; i < 16; i++) drift(i, tx, ty, colors, 0f, 1.4f + random.nextFloat(), 1.4f);
            for (int i = 16; i < 56; i++) {
                kind[i] = 2;
                ox[i] = random.nextFloat() * w;
                oy[i] = h * (0.12f + random.nextFloat() * 0.76f);
                spawn[i] = random.nextFloat() * 4.6f;
                life[i] = 0.9f + random.nextFloat() * 0.8f;
                size[i] = unit * (0.22f + random.nextFloat() * 0.3f);
                tint[i] = colors[random.nextInt(colors.length)];
                spin[i] = random.nextFloat() * 90f;
            }
            count = 56;
        } else if (DRIFT.equals(style)) {
            for (int i = 0; i < 46; i++) drift(i, tx, ty, colors, random.nextFloat() * 1.2f, 3.4f + random.nextFloat() * 2.2f, 2.6f);
            count = 46;
        } else {
            // Shower: pieces fall from the top edge across the full width, staggered over about five seconds.
            for (int i = 0; i < 64; i++) {
                kind[i] = 1;
                ox[i] = random.nextFloat() * w;
                size[i] = unit * (0.16f + random.nextFloat() * 0.16f);
                oy[i] = -size[i];
                vy[i] = unit * (1.3f + random.nextFloat() * 1.1f);
                phase[i] = random.nextFloat() * 6.28f;
                spawn[i] = random.nextFloat() * 4.6f;
                life[i] = (h + 2 * size[i]) / vy[i];
                spin[i] = random.nextFloat() * 360f - 180f;
                tint[i] = colors[random.nextInt(colors.length)];
            }
            count = 64;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        long total = BALL_END_MS + EFFECT_MS;
        animator.setDuration(total);
        animator.setInterpolator(null);
        animator.addUpdateListener(a -> { elapsedMs = Math.round((float) a.getAnimatedValue() * total); invalidate(); });
        animator.start();
    }

    /** A piece thrown from the badge that slows with drag and falls lazily. */
    private void drift(int i, float tx, float ty, int[] colors, float delay, float lifeSeconds, float reach) {
        kind[i] = 0;
        double a = random.nextDouble() * Math.PI * 2;
        float speed = unit * (1.2f + random.nextFloat() * reach);
        ox[i] = tx;
        oy[i] = ty;
        vx[i] = (float) Math.cos(a) * speed * 1.6f;
        vy[i] = (float) Math.sin(a) * speed - unit * 0.6f;
        spawn[i] = delay;
        life[i] = lifeSeconds;
        size[i] = unit * (0.15f + random.nextFloat() * 0.16f);
        spin[i] = random.nextFloat() * 540f - 270f;
        tint[i] = colors[random.nextInt(colors.length)];
    }

    /**
     * Takes no room of its own. A match-parent child in a wrap-content FrameLayout otherwise claims the
     * whole screen width on the first pass, which pushed the bar off-center. The frame then measures
     * this view again with exact bounds, which is when it gets the bar's size.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean exact = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY && MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY;
        setMeasuredDimension(exact ? MeasureSpec.getSize(widthSpec) : 0, exact ? MeasureSpec.getSize(heightSpec) : 0);
    }

    void stop() {
        if (animator != null) animator.cancel();
        animator = null;
        elapsedMs = 0;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (animator == null || target == null) return;
        float tx = (target.getLeft() + target.getRight()) / 2f, ty = (target.getTop() + target.getBottom()) / 2f;
        float r = unit * 0.62f;
        if (elapsedMs < HIT_MS) {
            // Flight: in from beyond the left edge on a shallow arc, spinning.
            float t = new DecelerateInterpolator(0.8f).getInterpolation(elapsedMs / (float) HIT_MS);
            ball.draw(c, sport, -r + (tx + r) * t, ty - (1f - t) * unit * 0.9f - (float) Math.sin(Math.PI * t) * unit * 0.5f, r, t * 900f);
            return;
        }
        float since = (elapsedMs - HIT_MS) / 1000f;
        if (elapsedMs < BALL_END_MS) {
            // The ball swells as it lands, then is gone.
            float k = (elapsedMs - HIT_MS) / (float) (BALL_END_MS - HIT_MS);
            ball.draw(c, sport, tx, ty, r * (1f + 0.5f * k), 900f + k * 90f);
        }
        for (int i = 0; i < count; i++) {
            float ts = since - spawn[i];
            if (ts < 0 || ts > life[i]) continue;
            float f = ts / life[i];
            float alpha = Math.min(1f, ts / 0.15f) * (f < 0.7f ? 1f : (1f - f) / 0.3f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(tint[i]);
            paint.setAlpha(Math.round(255 * Math.max(0f, alpha)));
            if (kind[i] == 2) {
                // Twinkle: a four-point star that grows then shrinks, turning slightly.
                float s = size[i] * (float) Math.sin(Math.PI * f);
                c.save();
                c.translate(ox[i], oy[i]);
                c.rotate(spin[i] + f * 60f);
                starPath(s);
                c.drawPath(star, paint);
                c.restore();
                continue;
            }
            float x, y;
            if (kind[i] == 1) {
                x = ox[i] + (float) Math.sin(ts * 2.2f + phase[i]) * unit * 0.3f;
                y = oy[i] + vy[i] * ts;
            } else {
                // Integrated drag: the piece leaves fast and settles into a slow float.
                float k = (1f - (float) Math.exp(-1.3f * ts)) / 1.3f;
                x = ox[i] + vx[i] * k;
                y = oy[i] + vy[i] * k + unit * 0.5f * ts * ts;
            }
            c.save();
            c.translate(x, y);
            c.rotate(spin[i] * ts);
            c.drawRect(-size[i], -size[i] * 0.6f, size[i], size[i] * 0.6f, paint);
            c.restore();
        }
    }

    private void starPath(float s) {
        star.reset();
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(-90 + 45 * i);
            float rad = i % 2 == 0 ? s : s * 0.28f;
            float px = (float) Math.cos(a) * rad, py = (float) Math.sin(a) * rad;
            if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.close();
    }
}
