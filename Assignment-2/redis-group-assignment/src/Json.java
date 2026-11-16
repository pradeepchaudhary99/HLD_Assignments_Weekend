import java.util.*;

/** A tiny JSON reader/writer so the exercise needs nothing but the JDK. */
final class Json {
    static Object parse(String s) {
        Parser p = new Parser(s);
        try {
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) throw new IllegalArgumentException("Unexpected text at position " + p.i);
            return v;
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
    }

    static String write(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return quote(s);
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Enum<?> e) return quote(e.name());
        if (o instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            m.forEach((k, v) -> j.add(quote(String.valueOf(k)) + ":" + write(v)));
            return j.toString();
        }
        if (o instanceof Collection<?> c) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Object v : c) j.add(write(v));
            return j.toString();
        }
        if (o instanceof Record r) {
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                for (var rc : r.getClass().getRecordComponents()) {
                    var accessor = rc.getAccessor();
                    accessor.setAccessible(true);
                    m.put(rc.getName(), accessor.invoke(r));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return write(m);
        }
        return quote(o.toString());
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + i);
            String n = s.substring(st, i);
            return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : Double.parseDouble(n);
        }

        String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        void expect(char c) {
            ws();
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("Expected '" + c + "' at position " + i);
            i++;
        }
    }
}
