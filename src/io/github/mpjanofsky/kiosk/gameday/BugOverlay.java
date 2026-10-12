// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.view.animation.OvershootInterpolator;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Date;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * The bar itself (style A): a pill reading "TBR 1–0 DET (67')", left side first (home for soccer, away for US sports),
 * the score large, the clock in its own capsule. It is visual only, so there are no listeners.
 * The plugin updates it in place, because replacing an overlay rebuilds
 * the view and restacks it, which would flicker every minute of a match.
 */
final class BugOverlay implements OverlayFactory {
    private static final long FADE_MS = 500, FLASH_MS = 8000, HIT_MS = Effects.HIT_MS;
    private final int textSp;
    private final boolean crests;
    private volatile Display display;
    private volatile KsTheme theme;
    /** The live view, or null before create and after onDestroy. Only the main thread builds or binds it. */
    private volatile LinearLayout view;
    /** A goal in progress: which side scored, the word to show, and when it ends (uptime ms). 0 means none. */
    private volatile boolean flashLeft;
    private volatile String flashLabel = "";
    private volatile long flashFrom, flashUntil;
    /** The bar, its goal-effects layer on top, as KS holds them. Main thread only. */
    private Effects effects;
    private ObjectAnimator dotPulse;

    BugOverlay(Display display, int textSp, boolean crests) {
        this.display = display;
        this.textSp = textSp;
        this.crests = crests;
    }

    /** Called from the plugin worker. The view reads the newest display when the post runs. */
    void update(Display next) {
        // A flash belongs to one pairing: new teams on the bar (the preview tour does this) end it.
        if (!next.left.equals(display.left) || !next.right.equals(display.right)) flashUntil = 0;
        display = next;
        LinearLayout v = view;
        if (v != null) v.post(() -> { if (view == v) bind(v); });
    }

    /**
     * A score went up. The ball flies in and hits the scorer's badge, then the bar tints in the scorer's
     * color, the clock capsule says the word, and the score and badge pop while confetti bursts.
     */
    void celebrate(boolean leftScored, String label, String sport, String confetti) {
        flashLeft = leftScored;
        flashLabel = label;
        flashFrom = SystemClock.uptimeMillis() + HIT_MS;
        flashUntil = flashFrom + FLASH_MS;
        LinearLayout v = view;
        KsTheme t = theme;
        if (v == null || t == null) return;
        v.post(() -> {
            if (view != v || effects == null) return;
            View scorer = v.getChildAt(leftScored ? 0 : 2);
            Display d = display;
            Brand brand = leftScored ? d.leftBrand : d.rightBrand;
            int surface = t.color("surfaceContainerHigh");
            int team = Contrast.pick(brand, surface);
            int[] colors = {team != 0 ? team : t.color("primary"), brand != null && brand.alternate != 0 ? brand.alternate : 0xFFFFD54F, 0xFFFFFFFF, 0xFFFFD54F};
            effects.play(sport, scorer, colors, t.px(textSp), confetti);
            v.postDelayed(() -> {
                if (view != v) return;
                bind(v);
                pop(v.getChildAt(1), 1.22f);
                pop(scorer, 1.2f);
            }, HIT_MS);
            v.postDelayed(() -> { if (view == v) bind(v); }, HIT_MS + FLASH_MS + 50);
        });
    }

    private static void pop(View target, float scale) {
        target.animate().cancel();
        target.animate().scaleX(scale).scaleY(scale).setDuration(220)
            .withEndAction(() -> target.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(new OvershootInterpolator(3f)));
    }

    private boolean flashing() {
        long now = SystemClock.uptimeMillis();
        return now >= flashFrom && now < flashUntil;
    }

    /** Fade the bar out, then run done on the main thread. With no view, done runs now. */
    void fadeOut(Runnable done) {
        LinearLayout v = view;
        if (v == null) {
            done.run();
            return;
        }
        View top = (View) v.getParent();
        top.post(() -> top.animate().alpha(0f).setDuration(FADE_MS).withEndAction(done));
    }

    @Override public View create(Context context, KsTheme theme) {
        this.theme = theme;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 4; i++) {
            View child;
            if (crests && (i == 0 || i == 2)) {
                child = new Crest(context);
            } else {
                TextView t = new TextView(context);
                t.setSingleLine(true);
                // Font padding is asymmetric and made the right-hand text and the capsule sit high.
                t.setIncludeFontPadding(false);
                t.setGravity(Gravity.CENTER);
                child = t;
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) p.setMarginStart(theme.px(i == 3 ? textSp : textSp / 2));
            root.addView(child, p);
        }
        // KS may call create again for the same overlay after its Activity restarts. Only the newest view is bound.
        view = root;
        effects = new Effects(context);
        FrameLayout frame = new FrameLayout(context);
        frame.setClipChildren(false);
        frame.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        frame.addView(effects, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        bind(root);
        frame.setAlpha(0f);
        frame.animate().alpha(1f).setDuration(FADE_MS);
        return frame;
    }

    @Override public void onThemeChanged(View v, KsTheme theme) {
        this.theme = theme;
        bind((LinearLayout) ((FrameLayout) v).getChildAt(0));
    }

    @Override public void onDestroy(View v) {
        v.animate().cancel();
        if (dotPulse != null) dotPulse.cancel();
        dotPulse = null;
        if (effects != null) effects.stop();
        effects = null;
        if (view == ((FrameLayout) v).getChildAt(0)) view = null;
    }

    private void bind(LinearLayout root) {
        Display d = display;
        KsTheme t = theme;
        if (t == null) return;
        GradientDrawable pill = t.card();
        pill.setCornerRadius(t.px(100));
        boolean flash = flashing() && d.kind == Display.Kind.LIVE;
        if (flash) {
            int team = Contrast.pick(flashLeft ? d.leftBrand : d.rightBrand, t.color("surfaceContainerHigh"));
            if (team == 0) team = t.color("primary");
            pill.setColor(Contrast.mix(t.color("surfaceContainerHigh"), team, 0.22f));
            pill.setStroke(Math.max(1, t.px(2)), team);
        }
        root.setBackground(pill);
        int padH = t.px(textSp), padV = t.px(Math.max(4, textSp / 3));
        root.setPadding(padH, padV, padH, padV);
        TextView middle = (TextView) root.getChildAt(1);
        TextView detail = (TextView) root.getChildAt(3);
        int surface = t.color("surfaceContainerHigh");
        int leftColor = Contrast.pick(d.leftBrand, surface), rightColor = Contrast.pick(d.rightBrand, surface);
        middle.setText(flash ? scorerHighlighted(d, flashLeft ? leftColor : rightColor, t) : d.middle());
        // Name the team as well as the word, so a goal reads even when two teams wear similar colors.
        detail.setText(flash ? (flashLeft ? d.left : d.right) + " " + flashLabel : detailText(root.getContext(), d));
        if (crests) {
            bindCrest((Crest) root.getChildAt(0), d.left, d.leftBrand, leftColor, t);
            bindCrest((Crest) root.getChildAt(2), d.right, d.rightBrand, rightColor, t);
        } else {
            // Style A: the score is the loudest thing, abbreviations sit back, the configured team a notch heavier.
            TextView leftText = (TextView) root.getChildAt(0), rightText = (TextView) root.getChildAt(2);
            leftText.setText(d.left);
            rightText.setText(d.right);
            t.styleText(leftText, textSp, d.teamIsLeft ? 700 : 500, "onSurface");
            t.styleText(rightText, textSp, d.teamIsLeft ? 500 : 700, "onSurface");
            if (leftColor != 0) leftText.setTextColor(leftColor);
            if (rightColor != 0) rightText.setTextColor(rightColor);
            if (!d.teamIsLeft && rightColor == 0) leftText.setTextColor(t.color("onSurfaceVariant"));
            if (d.teamIsLeft && leftColor == 0) rightText.setTextColor(t.color("onSurfaceVariant"));
        }
        // While a goal is on, the side that did not score steps back.
        root.getChildAt(0).setAlpha(flash && !flashLeft ? 0.35f : 1f);
        root.getChildAt(2).setAlpha(flash && flashLeft ? 0.35f : 1f);
        String scoreRole = d.kind == Display.Kind.NEXT ? "onSurfaceVariant" : resultRole(d);
        t.styleText(middle, d.kind == Display.Kind.NEXT ? textSp : textSp * 1.35f, d.kind == Display.Kind.NEXT ? 400 : 800, scoreRole);
        middle.setIncludeFontPadding(false);
        styleCapsule(detail, d, t);
    }

    /** The score with the scorer's number in the team color, so the digit that changed is the one that glows. */
    private CharSequence scorerHighlighted(Display d, int color, KsTheme t) {
        android.text.SpannableString text = new android.text.SpannableString(d.middle());
        int start = flashLeft ? 0 : d.leftScore.length() + 1;
        int end = flashLeft ? d.leftScore.length() : text.length();
        int paint = color != 0 ? color : t.color("primary");
        text.setSpan(new android.text.style.ForegroundColorSpan(paint), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private void bindCrest(Crest crest, String abbr, Brand brand, int ring, KsTheme t) {
        LinearLayout parent = (LinearLayout) crest.getParent();
        Bitmap logo = brand == null ? null : Logos.get(brand.logoFor(t.isDark()), () -> {
            // A logo landed: rebind on the main thread, if this view is still the live one.
            if (parent != null) parent.post(() -> { if (view == parent) bind(parent); });
        });
        crest.set(abbr, ring, logo, t.color("outline"), t.color("onSurface"), t.px(textSp));
    }

    /** The final is tinted by the configured team's result. Anything else keeps the plain text color. */
    private static String resultRole(Display d) {
        if (d.kind != Display.Kind.FINAL) return "onSurface";
        int mine = scoreOf(d.teamIsLeft ? d.leftScore : d.rightScore);
        int theirs = scoreOf(d.teamIsLeft ? d.rightScore : d.leftScore);
        if (mine < 0 || theirs < 0 || mine == theirs) return "onSurface";
        return mine > theirs ? "success" : "error";
    }

    /** -1 when the score is not a plain number, so an odd value never tints the bar. */
    private static int scoreOf(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** The clock sits in its own capsule; a live match adds a small primary dot. */
    private void styleCapsule(TextView detail, Display d, KsTheme t) {
        // Primary on the capsule was too faint to read at a glance; only the dot carries the accent.
        t.styleText(detail, Math.max(11, textSp - 2), 700, d.kind == Display.Kind.LIVE ? "onSurface" : "onSurfaceVariant");
        GradientDrawable capsule = new GradientDrawable();
        capsule.setCornerRadius(t.px(100));
        capsule.setColor(t.color("surfaceContainerHighest"));
        detail.setBackground(capsule);
        int h = t.px(Math.max(6, textSp * 0.6f)), v = t.px(Math.max(2, textSp / 6f));
        detail.setPadding(h, v, h, v);
        if (dotPulse != null) dotPulse.cancel();
        dotPulse = null;
        GradientDrawable dot = null;
        if (d.kind == Display.Kind.LIVE) {
            // Red like a broadcast "live" light, pulsing so a stalled clock still reads as alive.
            dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(t.color("error"));
            int size = t.px(Math.max(6, textSp / 2.4f));
            dot.setBounds(0, 0, size, size);
            dotPulse = ObjectAnimator.ofInt(dot, "alpha", 255, 70);
            dotPulse.setDuration(900);
            dotPulse.setRepeatCount(ObjectAnimator.INFINITE);
            dotPulse.setRepeatMode(ObjectAnimator.REVERSE);
            dotPulse.start();
        }
        Ball glyph = new Ball(d.sport, t.px(Math.max(12, textSp * 0.95f)));
        detail.setCompoundDrawables(dot, null, glyph, null);
        int gap = t.px(Math.max(4, textSp / 3f));
        detail.setCompoundDrawablePadding(gap);
        // While live the capsule is as wide as it will be during a goal, so the bar does not jump when GOAL appears.
        if (d.kind == Display.Kind.LIVE) {
            int dotWidth = dot == null ? 0 : dot.getBounds().width();
            int width = (int) Math.ceil(detail.getPaint().measureText((d.left.length() > d.right.length() ? d.left : d.right) + " " + GamedayPlugin.goalWord(d.sport))) + dotWidth + glyph.getIntrinsicWidth() + 2 * gap + 2 * h;
            detail.setMinWidth(width);
            detail.setMinimumWidth(width);
        } else {
            detail.setMinWidth(0);
            detail.setMinimumWidth(0);
        }
    }

    /** Kickoff time follows the device's own 12/24-hour choice, which only a Context knows. */
    private static String detailText(Context context, Display d) {
        if (d.kind != Display.Kind.NEXT) return d.detail;
        // Either the time or the countdown, never both: with both the capsule was the widest thing on the bar.
        if (d.minutesToKickoff < 60) return Display.countdown(d.minutesToKickoff);
        String time = android.text.format.DateFormat.getTimeFormat(context).format(new Date(d.kickoff));
        String day = Display.dayPrefix(d.kickoff, System.currentTimeMillis(), java.util.TimeZone.getDefault(), java.util.Locale.getDefault());
        return day.isEmpty() ? time : day + " " + time;
    }
}
