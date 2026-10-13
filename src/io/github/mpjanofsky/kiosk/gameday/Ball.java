// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The game ball for each TeamTracker sport, drawn with a few shapes so the
 * plugin ships no image files. Unknown sports get the soccer ball.
 */
final class Ball extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private final String sport;
    private final int size;

    /** A drawable of size x size pixels, for the clock capsule. */
    Ball(String sport, int size) {
        this.sport = sport;
        this.size = size;
        setBounds(0, 0, size, size);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void draw(Canvas c) {
        draw(c, sport, getBounds().exactCenterX(), getBounds().exactCenterY(), size / 2f, 0f);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /** Draws the ball centered on cx, cy with radius r, turned by deg degrees. */
    void draw(Canvas c, String sport, float cx, float cy, float r, float deg) {
        c.save();
        c.translate(cx, cy);
        c.rotate(deg);
        if ("baseball".equals(sport)) baseball(c, r);
        else if ("hockey".equals(sport)) puck(c, r);
        else if ("football".equals(sport)) football(c, r);
        else soccer(c, r);
        c.restore();
    }

    /**
     * Every ball is outlined in this light gray. The clock capsule is dark, so a pale ball (soccer, baseball) needs
     * the ring to stay round and a dark one (puck, football) needs it to be seen at all. Judged from Echo Show 5
     * screenshots, not by the numbers: the near-black puck with a mid-gray edge was only a faint smudge.
     */
    private static final int RIM = 0xFFC8C8C8;

    private float rimWidth(float r) { return Math.max(1f, r * 0.12f); }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
    }

    private void soccer(Canvas c, float r) {
        fill(0xFFF5F5F5);
        c.drawCircle(0, 0, r, paint);
        fill(0xFF222222);
        pentagon(c, 0, 0, r * 0.38f, -90);
        // Five dark patches on the rim, clipped by the ball's own outline.
        c.save();
        path.reset();
        path.addCircle(0, 0, r, Path.Direction.CW);
        c.clipPath(path);
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(-90 + 36 + 72 * i);
            pentagon(c, (float) Math.cos(a) * r * 1.0f, (float) Math.sin(a) * r * 1.0f, r * 0.34f, (float) Math.toDegrees(a) + 180);
        }
        c.restore();
        // Light outline, not dark: on the dark clock capsule the rim patches blend into the background, and a dark
        // edge left only five white petals that read as a flower. The light ring keeps the silhouette round.
        stroke(RIM, rimWidth(r));
        c.drawCircle(0, 0, r - rimWidth(r) / 2f, paint);
    }

    private void pentagon(Canvas c, float x, float y, float radius, float startDeg) {
        path.reset();
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(startDeg + 72 * i);
            float px = x + (float) Math.cos(a) * radius, py = y + (float) Math.sin(a) * radius;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        fill(0xFF222222);
        c.drawPath(path, paint);
    }

    private void baseball(Canvas c, float r) {
        fill(0xFFF8F8F8);
        c.drawCircle(0, 0, r, paint);
        // Two large circles centered outside the ball cross inside it as the ( ) of the stitching.
        c.save();
        path.reset();
        path.addCircle(0, 0, r, Path.Direction.CW);
        c.clipPath(path);
        stroke(0xFFD1342F, Math.max(1.5f, r * 0.2f));
        c.drawCircle(-r * 1.55f, 0, r * 0.95f, paint);
        c.drawCircle(r * 1.55f, 0, r * 0.95f, paint);
        c.restore();
        // Same light ring as the soccer ball: on the dark clock capsule a pale ball with a thin gray edge read as a
        // blob. Drawn inside the radius so the ring does not grow the ball.
        float rim = rimWidth(r);
        stroke(RIM, rim);
        c.drawCircle(0, 0, r - rim / 2f, paint);
    }

    private void puck(Canvas c, float r) {
        float h = r * 0.62f;
        box.set(-r, -h, r, h);
        fill(0xFF111111);
        c.drawOval(box, paint);
        box.set(-r * 0.78f, -h * 0.72f, r * 0.78f, h * 0.5f);
        fill(0xFF666666);
        c.drawOval(box, paint);
        // Thicker than the balls' rim: the puck is flatter and darker, so it is the hardest to see.
        float rim = rimWidth(r) * 1.4f;
        stroke(RIM, rim);
        box.set(-r + rim / 2f, -h + rim / 2f, r - rim / 2f, h - rim / 2f);
        c.drawOval(box, paint);
    }

    private void football(Canvas c, float r) {
        float h = r * 0.66f;
        box.set(-r, -h, r, h);
        fill(0xFF8A4B1F);
        c.drawOval(box, paint);
        float w = Math.max(1f, r * 0.12f);
        stroke(0xFFF2F2F2, w);
        c.drawLine(-r * 0.32f, 0, r * 0.32f, 0, paint);
        for (int i = -1; i <= 1; i++) c.drawLine(i * r * 0.2f, -h * 0.28f, i * r * 0.2f, h * 0.28f, paint);
        c.drawLine(-r * 0.62f, -h * 0.55f, -r * 0.62f, h * 0.55f, paint);
        c.drawLine(r * 0.62f, -h * 0.55f, r * 0.62f, h * 0.55f, paint);
        float rim = rimWidth(r);
        stroke(RIM, rim);
        box.set(-r + rim / 2f, -h + rim / 2f, r - rim / 2f, h - rim / 2f);
        c.drawOval(box, paint);
    }
}
