// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

/**
 * Chooses which team's display gets the bar when several are configured. Pure, so the rules are tested off-device.
 * Live beats final beats pre-game; picker order breaks ties; and the team already on the bar keeps it until it
 * drops out or something of higher rank appears, so a goal or a clock tick never swaps teams.
 */
final class Pick {
    private Pick() {}

    private static int rank(Display d) {
        switch (d.kind) {
            case LIVE: return 3;
            case FINAL: return 2;
            case NEXT: return 1;
            default: return 0;
        }
    }

    /** The index of the display to show, or -1 when none is visible. current is the index on the bar now, or -1. */
    static int choose(Display[] candidates, int current) {
        int best = -1;
        for (int i = 0; i < candidates.length; i++) {
            if (rank(candidates[i]) > (best < 0 ? 0 : rank(candidates[best]))) best = i;
        }
        if (best < 0) return -1;
        boolean holds = current >= 0 && current < candidates.length && rank(candidates[current]) >= rank(candidates[best]);
        return holds ? current : best;
    }
}
