// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of a TeamTracker sensor the bar shows. Built from a KS entity
 * snapshot, so it never sees the HA connection. Attributes are untrusted
 * integration data: anything missing or malformed becomes null, never zero.
 */
final class Match {
    static final String PRE = "PRE";
    static final String IN = "IN";
    static final String POST = "POST";

    final String state;
    /** TeamTracker's lowercase sport (soccer, baseball, hockey, football), or null. */
    final String sport;
    /** TeamTracker's league_path, e.g. usa.usl.1 or mlb. Part of ESPN's team-list URL, so BrandStore checks it. */
    final String league;
    final String team;
    final String opponent;
    final boolean teamIsHome;
    final String teamScore;
    final String opponentScore;
    final String clock;
    final String eventName;
    final String kickoffIn;
    /** Epoch ms, or -1 when TeamTracker gave no usable date. */
    final long kickoff;
    /** When HA last saw the state change, epoch ms, or -1. Resets when HA restarts. */
    final long stateChanged;

    private Match(String state, String sport, String league, String team, String opponent, boolean teamIsHome, String teamScore, String opponentScore,
                  String clock, String eventName, String kickoffIn, long kickoff, long stateChanged) {
        this.state = state;
        this.sport = sport;
        this.league = league;
        this.team = team;
        this.opponent = opponent;
        this.teamIsHome = teamIsHome;
        this.teamScore = teamScore;
        this.opponentScore = opponentScore;
        this.clock = clock;
        this.eventName = eventName;
        this.kickoffIn = kickoffIn;
        this.kickoff = kickoff;
        this.stateChanged = stateChanged;
    }

    /** Null unless the snapshot is an available entity with a state. */
    static Match from(Map<String, Object> payload) {
        if (payload == null || !"available".equals(payload.get("status"))) return null;
        String state = text(payload.get("state"), 8);
        if (state == null) return null;
        Map<?, ?> a = payload.get("attributes") instanceof Map ? (Map<?, ?>) payload.get("attributes") : java.util.Collections.emptyMap();
        String sport = text(a.get("sport"), 16);
        return new Match(state.toUpperCase(java.util.Locale.ROOT),
            sport == null ? null : sport.toLowerCase(java.util.Locale.ROOT),
            text(a.get("league_path"), 24),
            orDash(text(a.get("team_abbr"), 6)),
            orDash(text(a.get("opponent_abbr"), 6)),
            !"away".equals(a.get("team_homeaway")),
            text(a.get("team_score"), 3),
            text(a.get("opponent_score"), 3),
            text(a.get("clock"), 24),
            text(a.get("event_name"), 40),
            text(a.get("kickoff_in"), 24),
            parseTime(text(a.get("date"), 40)),
            parseTime(text(payload.get("lastChanged"), 40)));
    }

    /** Scores arrive as strings or numbers. Whole numbers print without ".0". */
    private static String text(Object value, int max) {
        if (value == null) return null;
        String s;
        if (value instanceof Number && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue())) {
            s = String.valueOf(((Number) value).longValue());
        } else {
            s = String.valueOf(value).trim();
        }
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String orDash(String s) { return s == null ? "—" : s; }

    private static final Pattern ISO = Pattern.compile(
        "(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?(Z|[+-]\\d{2}:?\\d{2})?");

    /**
     * ESPN dates come as "2026-10-17T23:30Z" and HA timestamps as
     * "2026-10-11T15:22:07.640995+00:00". java.time is missing below API 26,
     * which minAndroidSdk 24 still allows, so this is parsed by hand. A
     * missing zone is taken as UTC, which is what both sources send.
     */
    static long parseTime(String s) {
        if (s == null) return -1;
        Matcher m = ISO.matcher(s);
        if (!m.matches()) return -1;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
        long ms = c.getTimeInMillis();
        if (m.group(7) != null) ms += Integer.parseInt((m.group(7) + "00").substring(0, 3));
        String zone = m.group(8);
        if (zone != null && !zone.equals("Z")) {
            String digits = zone.replace(":", "");
            int minutes = Integer.parseInt(digits.substring(1, 3)) * 60 + Integer.parseInt(digits.substring(3, 5));
            ms -= (zone.charAt(0) == '-' ? -1 : 1) * minutes * 60_000L;
        }
        return ms;
    }
}
