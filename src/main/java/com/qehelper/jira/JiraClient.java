package com.qehelper.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.config.Config;
import com.qehelper.http.HttpGateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.qehelper.http.HttpGateway.JSON;

/** Sucesor de JiraController: mismo rol (cliente REST), sin dependencia de la UI. */
public final class JiraClient {

    private static final int BULK_LIMIT = 50; // límite de /rest/api/3/issue/bulk
    private final HttpGateway http;
    private final String base;
    private final Map<String, String> auth;
    private final AtomicInteger fakeSeq = new AtomicInteger(1);

    public JiraClient(Config cfg, HttpGateway http) {
        this.http = http;
        this.base = cfg.jiraBaseUrl();
        String basic = Base64.getEncoder().encodeToString(
                (cfg.jiraEmail() + ":" + cfg.jiraApiToken()).getBytes(StandardCharsets.UTF_8));
        this.auth = Map.of("Authorization", "Basic " + basic);
    }

    public record Created(String id, String key, String summary) { }
    public record Failure(String summary, String error) { }
    public record BulkResult(List<Created> created, List<Failure> failed) { }

    /** Verifica credenciales antes de hacer nada (falla rápido si el secreto está mal). */
    public String whoAmI() {
        JsonNode me = http.send("GET", base + "/rest/api/3/myself", auth, null, false, null);
        return me.path("displayName").asText(me.path("emailAddress").asText("?"));
    }

    public JsonNode getIssue(String key, String fields) {
        return http.send("GET", base + "/rest/api/3/issue/" + enc(key) + "?fields=" + enc(fields),
                auth, null, false, null);
    }

    public Created create(ObjectNode fields, ObjectNode update) {
        ObjectNode body = JSON.createObjectNode();
        body.set("fields", fields);
        if (update != null) body.set("update", update);
        JsonNode res = http.send("POST", base + "/rest/api/3/issue", auth, body, true, this::fakeCreated);
        return new Created(res.path("id").asText(), res.path("key").asText(), fields.path("summary").asText());
    }

    /** Creación masiva en lotes de 50; errores parciales no abortan el resto. */
    public BulkResult bulkCreate(List<ObjectNode> fieldsList) {
        List<Created> ok = new ArrayList<>();
        List<Failure> ko = new ArrayList<>();
        for (int i = 0; i < fieldsList.size(); i += BULK_LIMIT) {
            List<ObjectNode> chunk = fieldsList.subList(i, Math.min(i + BULK_LIMIT, fieldsList.size()));
            ObjectNode body = JSON.createObjectNode();
            ArrayNode updates = body.putArray("issueUpdates");
            chunk.forEach(f -> updates.addObject().set("fields", f));

            JsonNode res = http.send("POST", base + "/rest/api/3/issue/bulk", auth, body, true, () -> {
                ObjectNode fake = JSON.createObjectNode();
                ArrayNode arr = fake.putArray("issues");
                chunk.forEach(f -> arr.add(fakeCreated()));
                fake.putArray("errors");
                return fake;
            });

            // Jira devuelve los creados en el orden de entrada, omitiendo los fallidos
            // (cada fallido trae su índice en failedElementNumber).
            Set<Integer> failedIdx = new HashSet<>();
            for (JsonNode err : res.path("errors")) {
                int idx = err.path("failedElementNumber").asInt(-1);
                failedIdx.add(idx);
                String summary = idx >= 0 && idx < chunk.size() ? chunk.get(idx).path("summary").asText() : "?";
                ko.add(new Failure(summary, err.path("elementErrors").toString()));
            }
            JsonNode issues = res.path("issues");
            int n = 0;
            for (int j = 0; j < chunk.size() && n < issues.size(); j++) {
                if (failedIdx.contains(j)) continue;
                JsonNode is = issues.get(n++);
                ok.add(new Created(is.path("id").asText(), is.path("key").asText(), chunk.get(j).path("summary").asText()));
            }
        }
        return new BulkResult(ok, ko);
    }

    /** Usa el endpoint nuevo /search/jql (el /search clásico fue retirado en Jira Cloud). */
    public List<Created> search(String jql, int max) {
        List<Created> out = new ArrayList<>();
        String nextPageToken = null;
        do {
            ObjectNode body = JSON.createObjectNode().put("jql", jql).put("maxResults", Math.min(100, max - out.size()));
            body.putArray("fields").add("summary");
            if (nextPageToken != null) body.put("nextPageToken", nextPageToken);
            JsonNode res = http.send("POST", base + "/rest/api/3/search/jql", auth, body, false, null);
            for (JsonNode is : res.path("issues")) {
                out.add(new Created(is.path("id").asText(), is.path("key").asText(), is.path("fields").path("summary").asText()));
            }
            nextPageToken = res.path("nextPageToken").isTextual() ? res.path("nextPageToken").asText() : null;
        } while (nextPageToken != null && out.size() < max);
        return out;
    }

    public void link(String type, String inwardKey, String outwardKey) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("type").put("name", type);
        body.putObject("inwardIssue").put("key", inwardKey);
        body.putObject("outwardIssue").put("key", outwardKey);
        http.send("POST", base + "/rest/api/3/issueLink", auth, body, true, null);
    }

    public String browseUrl(String key) {
        return base + "/browse/" + key;
    }

    private JsonNode fakeCreated() {
        int n = fakeSeq.getAndIncrement();
        return JSON.createObjectNode().put("id", String.valueOf(900000 + n)).put("key", "DRYRUN-" + n);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
