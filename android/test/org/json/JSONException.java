package org.json;

/** The desktop tests' stand-in for Android's JSONException. */
public class JSONException extends Exception {
    public JSONException(String m) { super(m); }
}
