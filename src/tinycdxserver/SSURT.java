package tinycdxserver;

import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Superior SURT. Transforms scheme://userinfo@domain.tld:port/path?query#fragment into
 * (tld,domain,):port:scheme:userinfo/path?query#fragment.
 *
 * Status: unstable
 *
 * Note: port, scheme and userinfo are mandatory in the canonical form, but userinfo is allowed to be blank.
 * There is no such thing as a relative SSURT.
 *
 * Resolves the following issues with Heritrix SURTs:
 *
 * 1. URLs from the same domain sort together.
 * 2. URLs with the same port but different schemes sort together. (Think websocket.)
 * 3. SSURT prefixing works properly for domain wildcards, port wildcards, userinfo wildcards and so on.
 *
 * Example SSURT prefixes:
 *
 * (au,gov,nla,            => *.nla.gov.au
 * (au,gov,nla,):          => everything on host 'nla.gov.au' regardless of scheme and port.
 * (au,gov,nla,):80:       => everything on port 80 on host 'nla.gov.au'
 * (au,gov,nla,):80:http:  => http on port 80 on host 'nla.gov.au' any userinfo
 * (au,gov,nla,):80:http:/ => http on port 80 on host 'nla.gov.au' blank userinfo
 * 10.                     => everything in ipv4 subnet 10.0.0.0/8
 * [2001:0db8:              => everything in ipv6 subnet 2001:0db8:
 *
 *
 * Here's a loose grammar.
 *
 * SSURT = sshost ":" port ":" scheme ":" [ userinfo ] "/" path [ "?" query ] [ "#" fragment ]
 * sshost = "(" revdomain ",)" / IPv4address / "[" IPv6address "]"
 *
 * Canonicalisation rules:
 *
 * scheme: lowercase
 * host:
 *   - remove trailing '.'
 *   - replace '..' with '.'
 *   - IDN to ASCII
 *   - lowercase
 *   - canonical percent encoding (fully decode then encode illegals)
 * port:
 *   - remove leading zeros
 * path:
 *   - canonical percent encoding (fully decode then encode illegals)
 * query:
 *   - canonical percent encoding (fully decode then encode illegals)
 *
 *
 *  Open question: IP addresses. Should we represent ipv4 addresses as ipv6, pad with leading zeros and expand
 *  zero compress (::)?
 *
 */
public class SSURT {

    public static String fromUrl(String url) {
        try {
            URL parsed = new URL(url);
            return fromUrl(canonicalizeHost(parsed.getHost()), parsed.getPort() == -1 ? parsed.getDefaultPort() : parsed.getPort(),
                    parsed.getProtocol(), parsed.getUserInfo(), canonicalizePath(parsed.getPath()),
                    UrlCanonicalizer.canonicalizeUrlEncoding(parsed.getQuery()), null);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String canonicalizePath(String path) {
        return path.isEmpty() ? "/" : UrlCanonicalizer.canonicalizeUrlEncoding(path);
    }

    public static String fromUrl(String host, int port, String scheme, String userInfo, String path, String query,
                                 String fragment) {
        StringBuilder out = new StringBuilder();
        if (host.startsWith("[") || UrlCanonicalizer.DOTTED_IP.matcher(host).matches()) {
            out.append(host);
        } else {
            out.append('(');
            reverseDomain(host.toString(), out);
            out.append(')');
        }
        out.append(':').append(port);
        out.append(':').append(scheme);
        out.append(':').append(userInfo == null ? "" : userInfo);
        out.append(path);
        if (query != null) {
            out.append('?').append(query);
        }
        if (fragment != null) {
            out.append('#').append(fragment);
        }
        return out.toString();
    }

    public static String prefixFromPattern(String pattern) {
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("blank pattern is nonsensical");
        }
        if (isAlreadySsurt(pattern)) {
            return pattern;
        } else if (pattern.startsWith("*.")) {
            if (pattern.contains("/")) {
                throw new IllegalArgumentException("can't use a domain wildcard with a path");
            }
            StringBuilder out = new StringBuilder("(");
            reverseDomain(pattern.substring(2), out);
            return out.toString();
        } else if (pattern.endsWith("*")) {
            return fromUrl(pattern.substring(0, pattern.length() - 1));
        } else {
            return fromUrl(pattern) + " ";
        }
    }

    private static boolean isAlreadySsurt(String s) {
        char c = s.charAt(0);
        return c == '(' || c == '[' || '0' <= c && c <= '9';
    }

    private static void reverseDomain(String host, StringBuilder out) {
        int i = host.lastIndexOf('.');
        int j = host.length();
        while (i != -1) {
            out.append(host, i + 1, j);
            out.append(',');
            j = i;
            i = host.lastIndexOf('.', i - 1);
        }
        out.append(host, 0, j);
        out.append(',');
    }

    private static String canonicalizeHost(String host) {
        // TODO: canoniaclize IPv6 addresses
        host = host.replace("..", ".");
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        host = IDN.toASCII(host);
        host = host.toLowerCase();
        host = UrlCanonicalizer.canonicalizeUrlEncoding(host);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }
}
