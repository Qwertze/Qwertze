package com.porter.qwerte.manager.net;

/**
 * A logged-in session: the cookie value, the origin it was issued for and the user name. The token is a secret: it is
 * only kept in memory and in the module's saved data, and never printed.
 */
public record Session(String token, String origin, String user) {
    @Override
    public String toString() {
        return "Session[user=" + user + "]";
    }
}
