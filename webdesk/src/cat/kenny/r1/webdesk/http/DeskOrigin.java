// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.net.URI;
import java.net.URISyntaxException;

/** Only addresses shown by this desk may name it, even after a DNS change. */
public final class DeskOrigin {
    private DeskOrigin() { }

    public static boolean allowed(Request request, int port, String address) {
        String host = request.header("host");
        if (host == null) {
            return false;
        }
        try {
            URI target = new URI("http://" + host);
            String name = target.getHost();
            int actualPort = target.getPort() < 0 ? 80 : target.getPort();
            if (name == null || target.getRawUserInfo() != null
                    || !target.getRawPath().isEmpty() || target.getRawQuery() != null
                    || target.getRawFragment() != null || actualPort != port
                    || !(name.equalsIgnoreCase("r1.local") || name.equalsIgnoreCase("r1")
                    || name.equals(address))) {
                return false;
            }
            String site = request.header("sec-fetch-site");
            if (site != null && !(site.equals("same-origin") || site.equals("none"))) {
                return false;
            }
            String origin = request.header("origin");
            String source = origin == null ? request.header("referer") : origin;
            if (source == null && !(request.method().equals("GET")
                    && (request.path().equals("/") || request.path().startsWith("/font/")
                    || "same-origin".equals(site)))) {
                return false;
            }
            if (source != null && !"http".equalsIgnoreCase(new URI(source).getScheme())) {
                return false;
            }
            return Origin.same(request);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
