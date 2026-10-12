// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Just enough JSON for the fixtures and ESPN's team list. android.jar's
 * org.json is a stub off-device, so the pure-Java rules and their tests
 * parse with this instead. Malformed input throws; callers treat that as
 * "no data".
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /** The parsed value: Map, List, String, Double, Boolean or null. */
    static Object parse(String text) { return new Json(text).value(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> file(String path) throws Exception {
        return (Map<String, Object>) new Json(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).value();
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            do {
                ws();
                String k = string();
                ws();
                i++; // :
                m.put(k, value());
                ws();
            } while (s.charAt(i++) == ',');
            return m;
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            do { l.add(value()); ws(); } while (s.charAt(i++) == ',');
            return l;
        }
        if (c == '"') return string();
        if (s.startsWith("null", i)) { i += 4; return null; }
        if (s.startsWith("true", i)) { i += 4; return true; }
        if (s.startsWith("false", i)) { i += 5; return false; }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(start, i));
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        for (char c; (c = s.charAt(i++)) != '"'; ) {
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
        return b.toString();
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
}
