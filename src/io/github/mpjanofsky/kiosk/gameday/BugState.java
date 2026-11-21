// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

/**
 * Decides what the bar shows from a match and the clock. Pure, so every
 * rule is tested against fixtures without a device or a live game.
 *
 * Hidden with no match, before the pre-game window, and after the final
 * score has been up for its hold time. TeamTracker leaves a sensor in PRE
 * or POST for days, so both ends are bounded by time, not by state alone.
 */
final class BugState {
    private static final long HOUR_MS = 60 * 60_000L;

    /**
     * How long after the start time a game is certainly over, by sport. Generous on
     * purpose: it only bounds stale PRE/POST states, never a live game. An unknown
     * sport gets the longest common span so a long game is never cut off.
     */
    static long spanMs(Match m) {
        String sport = m.sport == null ? "" : m.sport;
        switch (sport) {
            case "soccer": case "basketball": return 3 * HOUR_MS;
            case "hockey": return 7 * HOUR_MS / 2;
            case "football": return 4 * HOUR_MS;
            case "baseball": case "tennis": return 5 * HOUR_MS; // a tennis match has no ceiling: long ones run past four hours
            default: return 5 * HOUR_MS;
        }
    }

    private BugState() {}

    /** US sports read "away @ home", so the away team is on the left. Soccer, and any sport we do not know, reads home first. */
    static boolean awayFirst(String sport) {
        if (sport == null) return false;
        switch (sport) {
            case "baseball": case "basketball": case "football": case "hockey": return true;
            default: return false;
        }
    }

    static Display decide(Match m, long now, int preGameHours, int finalMinutes) {
        if (m == null) return Display.HIDDEN;
        // The left side is home for soccer and away for the US sports; teamLeft says whether the user's team is it.
        boolean teamLeft = m.teamIsHome != awayFirst(m.sport);
        String left = teamLeft ? m.team : m.opponent;
        String right = teamLeft ? m.opponent : m.team;
        String leftScore = score(teamLeft ? m.teamScore : m.opponentScore);
        String rightScore = score(teamLeft ? m.opponentScore : m.teamScore);
        switch (m.state) {
            case Match.PRE: {
                if (m.kickoff < 0 || preGameHours <= 0) return Display.HIDDEN;
                if (now < m.kickoff - preGameHours * 3_600_000L) return Display.HIDDEN;
                // A PRE that outlives the match means TeamTracker stopped updating. Show nothing rather than a stale kickoff.
                if (now > m.kickoff + spanMs(m)) return Display.HIDDEN;
                int minutes = (int) Math.max(0, (m.kickoff - now + 59_999) / 60_000);
                return new Display(Display.Kind.NEXT, left, right, "", "", teamLeft, m.kickoff, minutes, "");
            }
            case Match.IN:
                return new Display(Display.Kind.LIVE, left, right, leftScore, rightScore, teamLeft, -1, 0, liveDetail(m, teamLeft));
            case Match.POST: {
                long ended = endedAt(m);
                if (ended < 0 || finalMinutes <= 0 || now >= ended + finalMinutes * 60_000L) return Display.HIDDEN;
                return new Display(Display.Kind.FINAL, left, right, leftScore, rightScore, teamLeft, -1, 0, finalLabel(m));
            }
            default:
                // BYE, NOT_FOUND and anything newer TeamTracker adds.
                return Display.HIDDEN;
        }
    }

    /**
     * HA's state-change time is the real full-time moment, but it resets when
     * HA restarts. Capping it at kickoff plus a match span keeps yesterday's
     * final from reappearing after a restart the next morning.
     */
    static long endedAt(Match m) {
        if (m.stateChanged < 0) return m.kickoff < 0 ? -1 : m.kickoff + spanMs(m);
        if (m.kickoff < 0) return m.stateChanged;
        return Math.min(m.stateChanged, m.kickoff + spanMs(m));
    }

    private static String score(String s) { return s == null ? "–" : s; }

    /**
     * The capsule text during a live match. For every other sport that is the clock as TeamTracker
     * prints it. Tennis is the exception: its live score is the games of the current set, so the sets
     * won would be invisible, and "6–3 in the 2nd Set" reads like a match lead even when the opponent
     * leads one set to none. Once a set is over, its count joins the clock: "S 0–1 · 2. Set".
     * No count, or none won yet, leaves the clock on its own.
     */
    static String liveDetail(Match m, boolean teamLeft) {
        String clock = m.clock == null ? "LIVE" : m.clock;
        if (!"tennis".equals(m.sport)) return clock;
        int mine = setsWon(teamLeft ? m.teamSets : m.opponentSets);
        int theirs = setsWon(teamLeft ? m.opponentSets : m.teamSets);
        if (mine < 0 || theirs < 0 || (mine == 0 && theirs == 0)) return clock;
        return "S " + mine + "–" + theirs + " · " + clock;
    }

    /** -1 when TeamTracker sent no usable count, so an odd value never reaches the bar. */
    private static int setsWon(String s) {
        if (s == null) return -1;
        try {
            int n = Integer.parseInt(s.trim());
            return n < 0 ? -1 : n;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /**
     * TeamTracker's POST clock is FT/AET for soccer and "Final" (or "Final/OT") elsewhere.
     * Long text falls back to the sport's plain final label.
     */
    private static String finalLabel(Match m) {
        String clock = m.clock;
        if (clock != null && clock.length() <= 8) return clock;
        return "soccer".equals(m.sport) ? "FT" : "Final";
    }
}
