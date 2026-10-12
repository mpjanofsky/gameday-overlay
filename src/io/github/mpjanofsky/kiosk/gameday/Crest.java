// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;

/**
 * A team badge: a ring in the team's color around its logo.
 * With no logo yet (not fetched, or it failed) a disc takes the team color
 * and carries the abbreviation, so the bar never shows an empty hole.
 */
final class Crest extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private int ring, labelColor, discColor;
    private String label = "";
    private Bitmap logo;
    private float textPx;

    Crest(Context context) { super(context); }

    /** ring is already contrast-checked against the bar; 0 means no team color. logo may be null. */
    void set(String label, int ring, Bitmap logo, int plainRing, int plainText, float textPx) {
        this.label = label;
        this.ring = ring != 0 ? ring : plainRing;
        this.logo = logo;
        this.textPx = textPx;
        this.discColor = this.ring;
        this.labelColor = logo != null ? plainText : Contrast.on(this.ring);
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int size = Math.round(textPx * 1.9f);
        setMeasuredDimension(size, size);
    }

    @Override protected void onDraw(Canvas c) {
        float s = getWidth(), stroke = Math.max(2f, s * 0.07f);
        // A logo sits straight on the bar inside its ring; only the label fallback fills the disc.
        if (logo == null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(discColor);
            c.drawCircle(s / 2, s / 2, s / 2 - stroke / 2, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(ring);
        c.drawCircle(s / 2, s / 2, s / 2 - stroke / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        if (logo != null) {
            float pad = s * 0.17f;
            box.set(pad, pad, s - pad, s - pad);
            c.drawBitmap(logo, null, box, paint);
        } else {
            paint.setColor(labelColor);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(s * (label.length() > 3 ? 0.26f : 0.32f));
            c.drawText(label, s / 2, s / 2 - (paint.descent() + paint.ascent()) / 2, paint);
            paint.setFakeBoldText(false);
        }
    }
}
