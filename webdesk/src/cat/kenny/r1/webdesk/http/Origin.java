// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.net.URI;
import java.net.URISyntaxException;

/** Compares authorities without DNS lookups or attacker controlled suffix matching. */
public final class Origin {
    private Origin() { }

    public static boolean same(Request request) {
        String source = request.header("origin");
        boolean referer = source == null;
        if (referer) {
            source = request.header("referer");
        }
        if (source == null) {
            return request.method().equals("GET");
        }
        String host = request.header("host");
        if (host == null || host.isEmpty()) {
            return false;
        }
        try {
            URI from = new URI(source);
            String scheme = from.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || from.getHost() == null || from.getRawUserInfo() != null
                    || from.getRawFragment() != null || !referer
                    && (from.getRawQuery() != null || !from.getRawPath().isEmpty())) {
                return false;
            }
            URI target = new URI("http://" + host);
            if (target.getHost() == null || target.getRawUserInfo() != null
                    || !target.getRawPath().isEmpty() || target.getRawQuery() != null
                    || target.getRawFragment() != null) {
                return false;
            }
            int defaultPort = scheme.equalsIgnoreCase("https") ? 443 : 80;
            int fromPort = from.getPort() < 0 ? defaultPort : from.getPort();
            int targetPort = target.getPort() < 0 ? 80 : target.getPort();
            return fromPort > 0 && fromPort <= 65_535 && targetPort == fromPort
                    && target.getHost().equalsIgnoreCase(from.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
