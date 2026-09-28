package io.bloxorz.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

public final class LocalProxyServer extends NanoHTTPD {
    public static final int PORT = 8765;
    public static final String TARGET_ORIGIN = "https://bloxorz.io";
    public static final String LOCAL_ORIGIN = "http://127.0.0.1:" + PORT;

    private static final Set<String> HOP = new HashSet<>();
    static {
        String[] names = {
                "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length"
        };
        for (String n : names) HOP.add(n);
    }

    public LocalProxyServer() {
        super("127.0.0.1", PORT);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String q = session.getQueryParameterString();
            String upstream = TARGET_ORIGIN + session.getUri()
                    + ((q == null || q.isEmpty()) ? "" : "?" + q);
            return proxy(session, upstream);
        } catch (Throwable t) {
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8",
                    "Proxy error\n\n" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private Response proxy(IHTTPSession session, String upstreamUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(upstreamUrl).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(session.getMethod().name());
        conn.setRequestProperty("Accept-Encoding", "identity");

        for (Map.Entry<String, String> h : session.getHeaders().entrySet()) {
            String name = h.getKey();
            if (name == null || HOP.contains(name.toLowerCase(Locale.ROOT))) continue;
            conn.setRequestProperty(name, h.getValue());
        }

        if (hasBody(session.getMethod())) {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String postData = files.get("postData");
            if (postData != null) {
                byte[] body = postData.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                conn.getOutputStream().write(body);
            }
        }

        int code = conn.getResponseCode();
        String contentType = conn.getContentType();
        if (contentType == null) contentType = "application/octet-stream";

        InputStream input;
        try {
            input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        } catch (IOException e) {
            input = conn.getErrorStream();
        }

        byte[] bytes = input == null ? new byte[0] : readAll(input);
        if (isText(contentType)) {
            String text = new String(bytes, detectCharset(contentType));
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (lower.contains("text/html")) text = rewriteHtml(text);
            else if (lower.contains("text/css")) text = rewriteCss(text);
            bytes = text.getBytes(StandardCharsets.UTF_8);
            contentType = contentType.split(";")[0].trim() + "; charset=utf-8";
        }

        Response response = newFixedLengthResponse(
                statusFor(code), contentType, new ByteArrayInputStream(bytes), bytes.length);

        Map<String, List<String>> headers = conn.getHeaderFields();
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                String name = e.getKey();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);

                if (HOP.contains(lower) || lower.equals("content-encoding") || lower.equals("content-length")) continue;
                if (lower.equals("content-security-policy")
                        || lower.equals("content-security-policy-report-only")
                        || lower.equals("x-frame-options")) continue;

                if (lower.equals("location")) {
                    String value = first(e.getValue());
                    if (value != null) response.addHeader("Location", rewriteLocation(upstreamUrl, value));
                    continue;
                }

                if (lower.equals("set-cookie")) {
                    if (e.getValue() != null) {
                        for (String cookie : e.getValue()) {
                            String rewritten = cookie
                                    .replaceAll("(?i);\\s*Domain=[^;]+", "")
                                    .replaceAll("(?i);\\s*Secure", "")
                                    .replaceAll("(?i);\\s*SameSite=None", "");
                            response.addHeader("Set-Cookie", rewritten);
                        }
                    }
                    continue;
                }

                String value = first(e.getValue());
                if (value != null) response.addHeader(name, value);
            }
        }

        response.addHeader("Cache-Control", "no-transform");
        return response;
    }

    private static boolean hasBody(Method m) {
        String n = m.name();
        return "POST".equals(n) || "PUT".equals(n) || "PATCH".equals(n);
    }

    private static String rewriteHtml(String html) {
        String out = html
                .replace("https://bloxorz.io/", LOCAL_ORIGIN + "/")
                .replace("https:\\/\\/bloxorz.io\\/", "http:\\/\\/127.0.0.1:" + PORT + "\\/");

        String lower = out.toLowerCase(Locale.ROOT);
        if (!lower.contains("name=\"viewport\"") && !lower.contains("name='viewport'")) {
            String meta = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">";
            int head = lower.indexOf("<head>");
            if (head >= 0) out = out.substring(0, head + 6) + meta + out.substring(head + 6);
        }
        return out;
    }

    private static String rewriteCss(String css) {
        return css.replace("https://bloxorz.io/", LOCAL_ORIGIN + "/");
    }

    private static String rewriteLocation(String baseUrl, String value) {
        try {
            URI u = URI.create(baseUrl).resolve(value);
            if ("bloxorz.io".equalsIgnoreCase(u.getHost())) {
                String path = u.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                String q = u.getRawQuery();
                return LOCAL_ORIGIN + path + (q == null ? "" : "?" + q);
            }
        } catch (Throwable ignored) {
        }
        return value;
    }

    private static boolean isText(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return t.startsWith("text/") || t.contains("javascript")
                || t.contains("json") || t.contains("xml") || t.contains("svg");
    }

    private static Charset detectCharset(String type) {
        try {
            for (String part : type.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    return Charset.forName(p.substring(8).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = src.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String first(List<String> values) {
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static Response.IStatus statusFor(final int code) {
        for (Response.Status status : Response.Status.values()) {
            if (status.getRequestStatus() == code) return status;
        }
        return new Response.IStatus() {
            @Override public String getDescription() { return Integer.toString(code); }
            @Override public int getRequestStatus() { return code; }
        };
    }
}
