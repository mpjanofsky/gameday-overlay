// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.util.Objects;

/** A team's ESPN colors and logo. A color of 0 means ESPN gave none; colors are opaque ARGB. */
final class Brand {
    final int color;
    final int alternate;
    /** An https ESPN-hosted image URL, or null. */
    final String logo;
    /** ESPN's variant drawn for dark backgrounds, or null. */
    final String logoDark;

    Brand(int color, int alternate, String logo) { this(color, alternate, logo, null); }

    Brand(int color, int alternate, String logo, String logoDark) {
        this.color = color;
        this.alternate = alternate;
        this.logo = logo;
        this.logoDark = logoDark;
    }

    /** The logo suited to the bar's background: ESPN's dark variant on a dark bar, the default otherwise. */
    String logoFor(boolean darkBar) { return darkBar && logoDark != null ? logoDark : logo; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Brand)) return false;
        Brand b = (Brand) o;
        return color == b.color && alternate == b.alternate && Objects.equals(logo, b.logo) && Objects.equals(logoDark, b.logoDark);
    }

    @Override public int hashCode() { return Objects.hash(color, alternate, logo, logoDark); }
}
