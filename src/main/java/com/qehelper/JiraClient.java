package com.qehelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Cliente mínimo de Jira Cloud REST v3. */
final class JiraClient {

    static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String baseUrl;
    private final String auth;

    JiraClient(String baseUrl, String email, String apiToken) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.auth = "Basic " + Base64.getEncoder()
                .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
    }

    JsonNode getIssue(String key) {
        return send("GET", "/rest/api/3/issue/" + key + "?fields=summary,project,subtasks", null);
    }

    /** El nombre del tipo subtarea cambia según idioma/config ("Subtask", "Subtarea"...): se busca por el flag. */
    String subtaskTypeId(String projectKey) {
        for (JsonNode type : send("GET", "/rest/api/3/project/" + projectKey, null).path("issueTypes")) {
            if (type.path("subtask").asBoolean()) return type.path("id").asText();
        }
        throw new IllegalStateException("El proyecto " + projectKey + " no tiene un tipo de issue subtarea");
    }

    /** POST /issue/bulk: hasta 50 issues por llamada; los errores parciales vienen en "errors". */
    JsonNode bulkCreate(JsonNode body) {
        return send("POST", "/rest/api/3/issue/bulk", body);
    }

    /** Comentario en formato ADF (Atlassian Document Format), obligatorio en la API v3. */
    void addComment(String issueKey, JsonNode adfDoc) {
        var body = JSON.createObjectNode();
        body.set("body", adfDoc);
        send("POST", "/rest/api/3/issue/" + issueKey + "/comment", body);
    }

    String browseUrl(String key) {
        return baseUrl + "/browse/" + key;
    }

    private JsonNode send(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", auth)
                    .header("Accept", "application/json");
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                String hint = res.statusCode() == 401 || res.statusCode() == 403
                        ? " — revisa los secretos JIRA_EMAIL / JIRA_API_TOKEN" : "";
                throw new IllegalStateException(method + " " + path + " -> " + res.statusCode() + ": " + res.body() + hint);
            }
            return res.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(res.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " falló: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido", e);
        }
    }
}
