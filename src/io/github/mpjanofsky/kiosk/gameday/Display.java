// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.util.Objects;

/**
 * What the bar shows right now. Left side first, in the order the sport is normally read: home first for soccer, away first (\"away @ home\")
 * for the US sports. BugState decides which is which.
 * Equal values draw the same bar, so the plugin touches the view only when
 * this changes.
 */
final class Display {
    enum Kind { HIDDEN, NEXT, LIVE, FINAL }

    static final Display HIDDEN = new Display(Kind.HIDDEN, "", "", "", "", true, -1, 0, "");

    final Kind kind;
    final String left;
    final String right;
    final String leftScore;
    final String rightScore;
    /** Which side is the configured team, so it can be drawn heavier. */
    final boolean teamIsLeft;
    /** NEXT: kickoff epoch ms. The view formats it with the device's 12/24-hour choice. */
    final long kickoff;
    /** NEXT: whole minutes to kickoff, 0 once it has passed. */
    final int minutesToKickoff;
    /** LIVE: the match clock, e.g. 67' or HT. FINAL: FT, AET or similar. */
    final String detail;
    /** ESPN colors and logos, or null while unknown or not fetched. */
    final Brand leftBrand;
    final Brand rightBrand;
    /** TeamTracker's sport (soccer, baseball, hockey, football), or null. Picks the ball and the goal word. */
    final String sport;

    Display(Kind kind, String left, String right, String leftScore, String rightScore, boolean teamIsLeft,
            long kickoff, int minutesToKickoff, String detail) {
        this(kind, left, right, leftScore, rightScore, teamIsLeft, kickoff, minutesToKickoff, detail, null, null, null);
    }

    Display(Kind kind, String left, String right, String leftScore, String rightScore, boolean teamIsLeft,
            long kickoff, int minutesToKickoff, String detail, Brand leftBrand, Brand rightBrand, String sport) {
        this.kind = kind;
        this.left = left;
        this.right = right;
        this.leftScore = leftScore;
        this.rightScore = rightScore;
        this.teamIsLeft = teamIsLeft;
        this.kickoff = kickoff;
        this.minutesToKickoff = minutesToKickoff;
        this.detail = detail;
        this.leftBrand = leftBrand;
        this.rightBrand = rightBrand;
        this.sport = sport;
    }

    /** The same display with what is learned outside the match rules: team brands and the sport. */
    Display decorated(Brand h, Brand a, String sport) {
        return new Display(kind, left, right, leftScore, rightScore, teamIsLeft, kickoff, minutesToKickoff, detail, h, a, sport);
    }

    boolean visible() { return kind != Kind.HIDDEN; }

    /** "1–0" for scores, "v" before kickoff. */
    String middle() {
        return kind == Kind.NEXT ? "v" : leftScore + "–" + rightScore;
    }

    /** "in 2h 15m", "in 45m", "soon" once kickoff passes without the match going live. */
    static String countdown(int minutes) {
        if (minutes <= 0) return "soon";
        if (minutes < 60) return "in " + minutes + "m";
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? "in " + h + "h" : "in " + h + "h " + m + "m";
    }

    /**
     * "" for today, "Tomorrow", the short weekday within the next week, else "Oct 17". Pure so the day
     * rule is testable; the zone and locale are the device's.
     */
    static String dayPrefix(long kickoff, long now, java.util.TimeZone zone, java.util.Locale locale) {
        java.util.Calendar a = java.util.Calendar.getInstance(zone), b = java.util.Calendar.getInstance(zone);
        a.setTimeInMillis(now);
        b.setTimeInMillis(kickoff);
        a.set(java.util.Calendar.HOUR_OF_DAY, 12);
        b.set(java.util.Calendar.HOUR_OF_DAY, 12);
        // Noon to noon, so a daylight-saving change cannot make a day 23 or 25 hours long.
        long days = Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86_400_000.0);
        if (days <= 0) return "";
        if (days == 1) return "Tomorrow";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(days < 7 ? "EEE" : "MMM d", locale);
        f.setTimeZone(zone);
        return f.format(new java.util.Date(kickoff));
    }

    /** One line for the plugin subpage status and logs. */
    String summary() {
        switch (kind) {
            case NEXT: return "Next: " + left + " v " + right + ", kickoff " + countdown(minutesToKickoff);
            case LIVE: return "Live: " + left + " " + middle() + " " + right + " " + detail;
            case FINAL: return "Final: " + left + " " + middle() + " " + right + " " + detail;
            default: return "Hidden";
        }
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Display)) return false;
        Display d = (Display) o;
        return kind == d.kind && teamIsLeft == d.teamIsLeft && kickoff == d.kickoff && minutesToKickoff == d.minutesToKickoff
            && left.equals(d.left) && right.equals(d.right) && leftScore.equals(d.leftScore) && rightScore.equals(d.rightScore)
            && detail.equals(d.detail) && Objects.equals(leftBrand, d.leftBrand) && Objects.equals(rightBrand, d.rightBrand) && Objects.equals(sport, d.sport);
    }

    @Override public int hashCode() {
        return Objects.hash(kind, left, right, leftScore, rightScore, teamIsLeft, kickoff, minutesToKickoff, detail, leftBrand, rightBrand, sport);
    }

    @Override public String toString() { return summary(); }
}
