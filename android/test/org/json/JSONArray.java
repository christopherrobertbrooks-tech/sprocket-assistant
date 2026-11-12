package org.json;

import java.util.ArrayList;
import java.util.List;

/** The desktop tests' stand-in for Android's JSONArray (see JSONObject here). */
public class JSONArray {
    final List<Object> list = new ArrayList<>();

    public int length() { return list.size(); }
    public Object get(int i) throws JSONException { return list.get(i); }
    public Object opt(int i) { return i >= 0 && i < list.size() ? list.get(i) : null; }
    public JSONArray put(Object v) { list.add(v); return this; }
    public String getString(int i) throws JSONException { return String.valueOf(get(i)); }
    public double getDouble(int i) throws JSONException { return JSONObject.num(get(i), i).doubleValue(); }
    public int getInt(int i) throws JSONException { return JSONObject.num(get(i), i).intValue(); }
    public JSONObject getJSONObject(int i) throws JSONException { return (JSONObject) get(i); }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public String optString(int i) { return optString(i, ""); }
    public String optString(int i, String fb) { Object v = opt(i); return v == null || v == JSONObject.NULL ? fb : String.valueOf(v); }
    public double optDouble(int i, double fb) { Object v = opt(i); return v instanceof Number ? ((Number) v).doubleValue() : fb; }

    public JSONArray() { }

    public JSONArray(java.util.Collection<?> c) { list.addAll(c); }

    public JSONArray(String s) throws JSONException {
        Object v = new JSONObject.Reader(s).value();
        if (!(v instanceof JSONArray)) throw new JSONException("not an array");
        list.addAll(((JSONArray) v).list);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (Object v : list) { if (b.length() > 1) b.append(','); b.append(JSONObject.write(v)); }
        return b.append(']').toString();
    }

    /** Android's rule: a missing value or a null gives the fallback. */
    public int optInt(int i, int fallback) {
        Object v = opt(i);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }
}
