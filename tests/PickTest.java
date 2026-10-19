// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

/** Which of several teams gets the bar: rank, picker order, and holding. */
public final class PickTest {
    static Display d(Display.Kind kind) {
        return new Display(kind, "AAA", "BBB", "0", "0", true, -1, 0, "");
    }

    static final Display LIVE = d(Display.Kind.LIVE), FINAL = d(Display.Kind.FINAL), NEXT = d(Display.Kind.NEXT), NONE = Display.HIDDEN;

    public static void main(String[] args) {
        // Nothing visible: no bar, whatever was on it before.
        assert Pick.choose(new Display[] {NONE, NONE, NONE}, -1) == -1;
        assert Pick.choose(new Display[] {NONE, NONE, NONE}, 1) == -1;
        assert Pick.choose(new Display[0], -1) == -1;

        // A single visible team is shown wherever it sits in the list.
        assert Pick.choose(new Display[] {NONE, NEXT, NONE}, -1) == 1;

        // Rank: live, then final, then pre-game, regardless of order.
        assert Pick.choose(new Display[] {NEXT, FINAL, LIVE}, -1) == 2;
        assert Pick.choose(new Display[] {NEXT, FINAL, NONE}, -1) == 1;

        // Ties go to picker order.
        assert Pick.choose(new Display[] {NONE, LIVE, LIVE}, -1) == 1;

        // Hold: the team on the bar keeps it against an equal or lower rank, even one earlier in the list.
        assert Pick.choose(new Display[] {LIVE, LIVE, NONE}, 1) == 1;
        assert Pick.choose(new Display[] {FINAL, NEXT, NONE}, 0) == 0;
        assert Pick.choose(new Display[] {NEXT, NEXT, NONE}, 1) == 1;
        assert Pick.choose(new Display[] {FINAL, LIVE, NONE}, 0) == 1 : "a live game takes over from a held final";
        assert Pick.choose(new Display[] {NEXT, FINAL, NONE}, 0) == 1 : "a fresh final takes over from a held pre-game";
        assert Pick.choose(new Display[] {LIVE, NEXT, NONE}, 1) == 0 : "a live game takes over from a held pre-game";

        // The held team drops out: the best of the rest takes the bar.
        assert Pick.choose(new Display[] {NONE, NEXT, FINAL}, 0) == 2;
        assert Pick.choose(new Display[] {NONE, NONE, NEXT}, 0) == 2;

        // A stale index (the picker list shrank) is ignored.
        assert Pick.choose(new Display[] {NONE, NEXT}, 5) == 1;

        System.out.println("Pick tests passed.");
    }
}
