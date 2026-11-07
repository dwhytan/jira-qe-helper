package com.qehelper.xray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.config.Config;
import com.qehelper.http.HttpGateway;

import java.util.List;
import java.util.Map;

import static com.qehelper.http.HttpGateway.JSON;

/** Xray Cloud: autenticación client_id/secret -> bearer token y API GraphQL. */
public final class XrayClient {

    private static final int TESTS_PER_CALL = 100;
    private final HttpGateway http;
    private final Config cfg;
    private String token;

    public XrayClient(Config cfg, HttpGateway http) {
        this.cfg = cfg;
        this.http = http;
    }

    private Map<String, String> auth() {
        if (token == null) {
            ObjectNode body = JSON.createObjectNode()
                    .put("client_id", cfg.xrayClientId())
                    .put("client_secret", cfg.xrayClientSecret());
            JsonNode res = http.send("POST", cfg.xrayBaseUrl() + "/api/v2/authenticate", Map.of(), body, false, null);
            token = res.asText(); // Xray responde un string JSON: "eyJ..."
            if (token.isBlank()) throw new IllegalStateException("Xray no devolvió token");
        }
        return Map.of("Authorization", "Bearer " + token);
    }

    public JsonNode graphql(String query, ObjectNode variables, boolean mutating) {
        ObjectNode body = JSON.createObjectNode().put("query", query);
        body.set("variables", variables);
        JsonNode res = http.send("POST", cfg.xrayBaseUrl() + "/api/v2/graphql", auth(), body, mutating,
                () -> JSON.createObjectNode().putObject("data"));
        if (res.has("errors") && !res.path("errors").isEmpty()) {
            throw new IllegalStateException("Xray GraphQL error: " + res.path("errors"));
        }
        return res.path("data");
    }

    /** Asocia tests (por issueId numérico) a un Test Plan; en lotes para planes grandes. */
    public int addTestsToTestPlan(String testPlanIssueId, List<String> testIssueIds) {
        String q = "mutation($id: String!, $tests: [String]!) {"
                + " addTestsToTestPlan(issueId: $id, testIssueIds: $tests) { addedTests warning } }";
        int added = 0;
        for (int i = 0; i < testIssueIds.size(); i += TESTS_PER_CALL) {
            List<String> chunk = testIssueIds.subList(i, Math.min(i + TESTS_PER_CALL, testIssueIds.size()));
            ObjectNode vars = JSON.createObjectNode().put("id", testPlanIssueId);
            chunk.forEach(vars.putArray("tests")::add);
            JsonNode data = graphql(q, vars, true);
            JsonNode r = data.path("addTestsToTestPlan");
            added += http.dryRun() ? chunk.size() : r.path("addedTests").size();
            if (r.hasNonNull("warning") && !r.path("warning").asText().isBlank()) {
                System.out.println("  ⚠ Xray: " + r.path("warning").asText());
            }
        }
        return added;
    }
}
