package dev.sprocket.app;

/** Was it "no internet"? (Same rule as the earlier test app's Net.offline.) */
final class Net {
    private Net() { }

    static boolean offline(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException || t instanceof java.net.SocketTimeoutException)
                return true;
            String m = t.getMessage();
            if (m != null && m.contains("Unable to resolve host")) return true;
        }
        return false;
    }
}
