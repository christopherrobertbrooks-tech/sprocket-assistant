package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small stand-in for Android's org.json, for desktop tests only (android.jar's copy is a stub that throws). Reads
 * what the tests parse -- objects, arrays, strings, numbers, true/false/null -- with the same get/opt rules as Android.
 * Never built into the app (android/build.sh compiles src/ only).
 */
public class JSONObject {
    public static final Object NULL = new Object() { @Override public String toString() { return "null"; } };
    final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() { }

    public JSONObject(String s) throws JSONException {
        Reader r = new Reader(s);
        Object v = r.value();
        if (!(v instanceof JSONObject)) throw new JSONException("not an object");
        map.putAll(((JSONObject) v).map);
    }

    public Object get(String k) throws JSONException {
        Object v = map.get(k);
        if (v == null) throw new JSONException("no value for " + k);
        return v;
    }

    public Object opt(String k) { return map.get(k); }
    public java.util.Iterator<String> keys() { return map.keySet().iterator(); }
    public boolean has(String k) { return map.containsKey(k); }
    public JSONObject put(String k, Object v) { map.put(k, v); return this; }
    public Object remove(String k) { return map.remove(k); }
    public JSONObject getJSONObject(String k) throws JSONException { return (JSONObject) get(k); }
    public boolean getBoolean(String k) throws JSONException { Object v = get(k); if (v instanceof Boolean) return (Boolean) v; throw new JSONException(k + " is not a boolean"); }
    public JSONArray getJSONArray(String k) throws JSONException { return (JSONArray) get(k); }
    public String getString(String k) throws JSONException { return String.valueOf(get(k)); }
    public double getDouble(String k) throws JSONException { return num(get(k), k).doubleValue(); }
    public int getInt(String k) throws JSONException { return num(get(k), k).intValue(); }
    public long getLong(String k) throws JSONException { return num(get(k), k).longValue(); }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append(quote(e.getKey())).append(':').append(write(e.getValue()));
        }
        return b.append('}').toString();
    }

    static String write(Object v) {
        if (v instanceof String) return quote((String) v);
        return String.valueOf(v);   // numbers, booleans, NULL, nested objects and arrays
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    public String optString(String k, String fb) { Object v = opt(k); return v == null || v == NULL ? fb : String.valueOf(v); }
    public long optLong(String k, long fb) { Object v = opt(k); return v instanceof Number ? ((Number) v).longValue() : fb; }
    public int optInt(String k, int fb) { Object v = opt(k); return v instanceof Number ? ((Number) v).intValue() : fb; }
    // 0.43 (Live, Lookup): as Android's org.json -- optString's "" fallback, numbers that arrive as strings
    public String optString(String k) { return optString(k, ""); }
    public double optDouble(String k) { return optDouble(k, Double.NaN); }
    public double optDouble(String k, double fb) { try { return num(opt(k), k).doubleValue(); } catch (Exception e) { return fb; } }
    public JSONArray optJSONArray(String k) { Object v = opt(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public JSONObject optJSONObject(String k) { Object v = opt(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public int length() { return map.size(); }
    public boolean optBoolean(String k, boolean fb) { Object v = opt(k); return v instanceof Boolean ? (Boolean) v : fb; }

    static Number num(Object v, Object where) throws JSONException {
        if (v instanceof Number) return (Number) v;
        if (v instanceof String) try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) { }
        throw new JSONException("not a number at " + where);
    }

    /** A plain recursive-descent JSON reader. */
    static final class Reader {
        final String s;
        int i;
        Reader(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() throws JSONException {
            ws();
            if (i >= s.length()) throw new JSONException("end of text");
            char c = s.charAt(i);
            if (c == '{') {
                JSONObject o = new JSONObject();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws();
                    String k = (String) value();
                    ws(); i++;   // ':'
                    o.map.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return o;
                }
            }
            if (c == '[') {
                JSONArray a = new JSONArray();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.list.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return a;
                }
            }
            if (c == '"') {
                StringBuilder b = new StringBuilder();
                i++;
                while (s.charAt(i) != '"') {
                    char ch = s.charAt(i++);
                    if (ch == '\\') {
                        char e = s.charAt(i++);
                        if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == 'b' ? '\b' : e == 'f' ? '\f' : e);
                    } else b.append(ch);
                }
                i++;
                return b.toString();
            }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            int j = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(j, i);
            if (n.isEmpty()) throw new JSONException("bad character at " + j);
            if (n.matches("-?\\d+")) {
                long l = Long.parseLong(n);
                return l == (int) l ? (Object) (int) l : (Object) l;
            }
            return Double.parseDouble(n);
        }
    }
    // Sprocket's tests: Android's one-argument forms
    public int optInt(String k) { return optInt(k, 0); }
    public long optLong(String k) { return optLong(k, 0); }
    public boolean optBoolean(String k) { return optBoolean(k, false); }
}
