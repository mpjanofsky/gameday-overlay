// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.util.*;

/** The display rules against the recorded and derived TeamTracker fixtures. */
public final class BugStateTest {
    static final long MIN = 60_000L, HOUR = 60 * MIN;

    static Map<String, Object> missingSport(Match unused) throws Exception {
        Map<String, Object> p = Json.file("fixtures/teamtracker/mlb-pre.json");
        ((Map<String, Object>) p.get("attributes")).remove("sport");
        return p;
    }

    public static void main(String[] args) throws Exception {
        // Timestamps in both shapes the sources send.
        long kickoff = Match.parseTime("2026-10-17T23:30Z");
        assert kickoff == 1_792_279_800_000L : kickoff;
        assert Match.parseTime("2026-10-17T19:30:00-04:00") == kickoff;
        assert Match.parseTime("2026-10-11T15:22:07.640995+00:00") == Match.parseTime("2026-10-11T15:22:07Z") + 640;
        assert Match.parseTime("next Saturday") == -1 && Match.parseTime(null) == -1;

        Match pre = Match.from(Json.file("fixtures/teamtracker/pre.json"));
        assert pre.state.equals(Match.PRE) && pre.team.equals("TBR") && pre.opponent.equals("DET") && pre.teamIsHome;
        assert pre.kickoff == kickoff && pre.teamScore == null;

        // PRE: hidden until the window opens, then a countdown, then hidden again if TeamTracker goes stale.
        assert !BugState.decide(pre, kickoff - 6 * 24 * HOUR, 3, 30).visible();
        assert !BugState.decide(pre, kickoff - 3 * HOUR - 1, 3, 30).visible();
        Display next = BugState.decide(pre, kickoff - 2 * HOUR - 15 * MIN, 3, 30);
        assert next.kind == Display.Kind.NEXT && next.left.equals("TBR") && next.right.equals("DET");
        assert next.minutesToKickoff == 135 && Display.countdown(135).equals("in 2h 15m") && next.middle().equals("v");
        assert BugState.decide(pre, kickoff - 30 * MIN - 10_000, 3, 30).minutesToKickoff == 31 : "partial minutes round up";
        assert Display.countdown(60).equals("in 1h") && Display.countdown(5).equals("in 5m") && Display.countdown(0).equals("soon");
        assert BugState.decide(pre, kickoff + 10 * MIN, 3, 30).minutesToKickoff == 0;
        assert !BugState.decide(pre, kickoff + 3 * HOUR + 1, 3, 30).visible();
        assert !BugState.decide(pre, kickoff - MIN, 0, 30).visible() : "0 hours means no pre-game bar";

        // IN: always shown, home side first, clock as TeamTracker gives it.
        Match live = Match.from(Json.file("fixtures/teamtracker/in.json"));
        Display l = BugState.decide(live, kickoff + 70 * MIN, 3, 30);
        assert l.kind == Display.Kind.LIVE && l.middle().equals("1–0") && l.detail.equals("67'") && l.teamIsLeft;
        assert l.summary().equals("Live: TBR 1–0 DET 67'");

        // Away games flip the order but keep the team marked.
        Map<String, Object> awayPayload = Json.file("fixtures/teamtracker/in.json");
        ((Map<String, Object>) awayPayload.get("attributes")).put("team_homeaway", "away");
        Display a = BugState.decide(Match.from(awayPayload), kickoff + 70 * MIN, 3, 30);
        assert a.left.equals("DET") && a.right.equals("TBR") && a.middle().equals("0–1") && !a.teamIsLeft;

        // Numeric scores print as whole numbers.
        ((Map<String, Object>) awayPayload.get("attributes")).put("team_score", 2.0);
        assert Match.from(awayPayload).teamScore.equals("2");

        // POST: final score for the hold time after full time.
        Map<String, Object> postPayload = Json.file("fixtures/teamtracker/post.json");
        Match post = Match.from(postPayload);
        long fullTime = Match.parseTime("2026-10-18T01:24:40+00:00");
        Display f = BugState.decide(post, fullTime + 5 * MIN, 3, 30);
        assert f.kind == Display.Kind.FINAL && f.middle().equals("2–1") && f.detail.equals("FT");
        assert BugState.decide(post, fullTime + 30 * MIN - 1, 3, 30).visible();
        assert !BugState.decide(post, fullTime + 30 * MIN, 3, 30).visible();
        assert !BugState.decide(post, fullTime, 3, 0).visible() : "0 minutes hides at full time";

        // An HA restart the next morning resets lastChanged. The final must not come back.
        postPayload.put("lastChanged", "2026-10-18T13:00:00+00:00");
        Match restarted = Match.from(postPayload);
        assert BugState.endedAt(restarted) == kickoff + BugState.spanMs(restarted);
        assert !BugState.decide(restarted, Match.parseTime("2026-10-18T13:01:00Z"), 3, 30).visible();

        // Other sports: real snapshots from 2026-10-11. The Bucs final is three days old, so it is long gone.
        Match nfl = Match.from(Json.file("fixtures/teamtracker/nfl-post.json"));
        assert nfl.sport.equals("football") && nfl.team.equals("TB") && !nfl.teamIsHome && nfl.teamScore.equals("24");
        long nflKickoff = Match.parseTime("2026-10-09T00:15Z");
        assert BugState.spanMs(nfl) == 4 * HOUR;
        assert !BugState.decide(nfl, Match.parseTime("2026-10-12T01:05:00Z"), 3, 30).visible() : "stale final stays hidden";
        Display nflFinal = BugState.decide(nfl, nflKickoff + 3 * HOUR + 10 * MIN, 3, 30);
        assert nflFinal.kind == Display.Kind.FINAL && nflFinal.left.equals("TB") && nflFinal.right.equals("DAL");
        assert nflFinal.middle().equals("24–16") && nflFinal.detail.equals("Final") : nflFinal.middle() + " " + nflFinal.detail;
        Map<String, Object> ot = Json.file("fixtures/teamtracker/nfl-post.json");
        ((Map<String, Object>) ot.get("attributes")).put("clock", "Final/Overtime");
        assert BugState.decide(Match.from(ot), nflKickoff + 3 * HOUR + 10 * MIN, 3, 30).detail.equals("Final") : "long text falls back";

        Match mlb = Match.from(Json.file("fixtures/teamtracker/mlb-pre.json"));
        Match nhl = Match.from(Json.file("fixtures/teamtracker/nhl-pre.json"));
        assert mlb.sport.equals("baseball") && mlb.teamIsHome && BugState.spanMs(mlb) == 5 * HOUR;
        assert nhl.sport.equals("hockey") && !nhl.teamIsHome && BugState.spanMs(nhl) == 7 * HOUR / 2;
        long mlbStart = Match.parseTime("2026-10-13T00:00Z");
        Display mlbNext = BugState.decide(mlb, mlbStart - HOUR, 3, 30);
        assert mlbNext.kind == Display.Kind.NEXT && mlbNext.left.equals("CLE") && mlbNext.right.equals("TB") && mlbNext.minutesToKickoff == 60;
        Display nhlNext = BugState.decide(nhl, Match.parseTime("2026-10-13T23:15Z") - 2 * HOUR, 3, 30);
        assert nhlNext.left.equals("TB") && nhlNext.right.equals("NYR") && nhlNext.minutesToKickoff == 120;
        assert BugState.spanMs(Match.from(missingSport(mlb))) == 5 * HOUR : "unknown sport uses the longest span";

        // Soccer, recorded from HA history: the 2026-10-10 away match at INDY (TBR won 3-1).
        long indyKickoff = Match.parseTime("2026-10-10T23:00Z");
        Display firstHalf = BugState.decide(Match.from(Json.file("fixtures/teamtracker/rowdies-in-29.json")), indyKickoff + 36 * MIN, 3, 30);
        assert firstHalf.kind == Display.Kind.LIVE && firstHalf.left.equals("INDY") && firstHalf.right.equals("TBR") && !firstHalf.teamIsLeft;
        assert firstHalf.middle().equals("1–0") && firstHalf.detail.equals("29'") : firstHalf.middle() + " " + firstHalf.detail;
        Display half = BugState.decide(Match.from(Json.file("fixtures/teamtracker/rowdies-ht.json")), indyKickoff + 56 * MIN, 3, 30);
        assert half.kind == Display.Kind.LIVE && half.detail.equals("HT") : half.detail;
        Display stoppage = BugState.decide(Match.from(Json.file("fixtures/teamtracker/rowdies-in-90p3.json")), indyKickoff + 117 * MIN, 3, 30);
        assert stoppage.middle().equals("1–3") && stoppage.detail.equals("90'+3'") : stoppage.middle() + " " + stoppage.detail;
        Match indyFinal = Match.from(Json.file("fixtures/teamtracker/rowdies-post.json"));
        long indyFullTime = Match.parseTime("2026-10-11T01:01:27Z");
        Display indyDone = BugState.decide(indyFinal, indyFullTime + 5 * MIN, 3, 30);
        assert indyDone.kind == Display.Kind.FINAL && indyDone.middle().equals("1–3") && indyDone.detail.equals("FT");
        assert BugState.decide(indyFinal, indyFullTime + 30 * MIN - 1000, 3, 30).visible();
        assert !BugState.decide(indyFinal, indyFullTime + 30 * MIN + 1000, 3, 30).visible();

        // Unavailable or odd entities hide the bar.
        Map<String, Object> missing = new HashMap<>();
        missing.put("status", "missing");
        assert Match.from(missing) == null && !BugState.decide(null, kickoff, 3, 30).visible();
        Map<String, Object> bye = Json.file("fixtures/teamtracker/pre.json");
        bye.put("state", "BYE");
        assert !BugState.decide(Match.from(bye), kickoff, 3, 30).visible();

        // Reading order: US sports are "away @ home" (away on the left), soccer and unknown sports home first.
        assert BugState.awayFirst("baseball") && BugState.awayFirst("hockey") && BugState.awayFirst("football") && BugState.awayFirst("basketball");
        assert !BugState.awayFirst("soccer") && !BugState.awayFirst(null) && !BugState.awayFirst("cricket");
        Display nflDone = BugState.decide(Match.from(Json.file("fixtures/teamtracker/nfl-post.json")), Match.parseTime("2026-10-12T00:00Z"), 3, 100000);
        assert nflDone.visible() ? nflDone.teamIsLeft && nflDone.leftScore.equals("24") : true; // TB is the away team and so on the left

        System.out.println("BugState tests passed.");
    }
}
