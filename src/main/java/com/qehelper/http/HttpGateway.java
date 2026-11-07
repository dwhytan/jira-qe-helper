package com.qehelper.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cliente HTTP mínimo (java.net.http) con reintentos ante 429/5xx y modo dry-run:
 * en dry-run las lecturas se ejecutan de verdad y las escrituras solo se imprimen.
 * Los headers (Authorization) nunca se loguean.
 */
public final class HttpGateway {

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final boolean dryRun;

    public HttpGateway(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean dryRun() { return dryRun; }

    /**
     * @param mutating  true si la llamada crea/modifica datos (se omite en dry-run)
     * @param dryRunFake respuesta simulada para dry-run (solo si mutating)
     */
    public JsonNode send(String method, String url, Map<String, String> headers, Object body,
                         boolean mutating, Supplier<JsonNode> dryRunFake) {
        String payload = body == null ? null : write(body);
        if (mutating && dryRun) {
            System.out.println("[DRY-RUN] " + method + " " + url + (payload == null ? "" : "\n          " + payload));
            return dryRunFake == null ? JSON.createObjectNode() : dryRunFake.get();
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        headers.forEach(rb::header);
        if (payload != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(payload));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest request = rb.build();

        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> res;
            try {
                res = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) throw new ApiException(method, url, -1, e.getMessage());
                sleep(backoff(attempt, null));
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(method, url, -1, "interrumpido");
            }

            int status = res.statusCode();
            if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                long wait = backoff(attempt, res.headers().firstValue("Retry-After").orElse(null));
                System.out.printf("  ↻ %s %s -> %d, reintento %d en %dms%n", method, path(url), status, attempt, wait);
                sleep(wait);
                continue;
            }
            if (status >= 400) throw new ApiException(method, url, status, res.body());
            return read(res.body());
        }
    }

    private static long backoff(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try { return Math.min(Long.parseLong(retryAfter.trim()) * 1000L, 30_000L); } catch (NumberFormatException ignored) { }
        }
        return (long) (500 * Math.pow(2, attempt - 1));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String path(String url) {
        return URI.create(url).getPath();
    }

    private static String write(Object body) {
        try {
            return body instanceof String s ? s : JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.getNodeFactory().textNode(body);
        }
    }

    public static final class ApiException extends RuntimeException {
        private final int status;

        public ApiException(String method, String url, int status, String body) {
            super(method + " " + path(url) + " -> " + status + ": " + truncate(body));
            this.status = status;
        }

        public int status() { return status; }

        private static String truncate(String s) {
            if (s == null) return "";
            return s.length() > 600 ? s.substring(0, 600) + "…" : s;
        }
    }
}
