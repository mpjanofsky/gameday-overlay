// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

/**
 * Keeps a team color readable on the bar. ESPN's colors are brand colors, not
 * display colors: navy vanishes on a dark bar and yellow on a light one. WCAG
 * 3:1 is the bar for large text and graphics, which is all the bar draws.
 */
final class Contrast {
    static final double MIN_RATIO = 3.0;

    private Contrast() {}

    /**
     * The team's primary color if it reads on the surface, else its alternate,
     * else the primary pushed toward white (dark surface) or black (light
     * surface) until it does. 0 when the team has no color at all.
     */
    static int pick(Brand brand, int surface) {
        if (brand == null) return 0;
        if (brand.color != 0 && ratio(brand.color, surface) >= MIN_RATIO) return brand.color;
        if (brand.alternate != 0 && ratio(brand.alternate, surface) >= MIN_RATIO) return brand.alternate;
        int base = brand.color != 0 ? brand.color : brand.alternate;
        if (base == 0) return 0;
        int toward = luminance(surface) < 0.5 ? 0xFFFFFFFF : 0xFF000000;
        for (int step = 1; step <= 10; step++) {
            int c = mix(base, toward, step / 10f);
            if (ratio(c, surface) >= MIN_RATIO) return c;
        }
        return toward;
    }

    /** Black or white, whichever reads on the color. */
    static int on(int color) { return luminance(color) > 0.4 ? 0xFF000000 : 0xFFFFFFFF; }

    static double ratio(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double luminance(int argb) {
        return 0.2126 * channel(argb >> 16) + 0.7152 * channel(argb >> 8) + 0.0722 * channel(argb);
    }

    private static double channel(int v) {
        double c = (v & 0xFF) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static int mix(int from, int to, float t) {
        int r = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int a = (from >> shift) & 0xFF, b = (to >> shift) & 0xFF;
            r |= Math.round(a + (b - a) * t) << shift;
        }
        return r;
    }
}
