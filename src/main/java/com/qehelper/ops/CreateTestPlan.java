package com.qehelper.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.config.Inputs;
import com.qehelper.jira.Adf;
import com.qehelper.jira.JiraClient;
import com.qehelper.report.Report.Status;

import java.util.List;

import static com.qehelper.http.HttpGateway.JSON;

/**
 * Crea un Test Plan (issue type de Xray) vía Jira REST y le asocia tests vía Xray GraphQL.
 * tests: "QA-1,QA-2" o "jql:project = QA AND issuetype = Test AND labels = regresion".
 */
public final class CreateTestPlan implements Operation {

    private static final int MAX_TESTS = 1000;

    @Override
    public void run(Context ctx) {
        String storyKey = ctx.in.requireIssueKey();
        String summary = ctx.in.requireSummary();
        ctx.xray(); // valida secretos de Xray antes de crear nada

        JsonNode story = ctx.jira.getIssue(storyKey, "summary,project");
        String projectKey = story.path("fields").path("project").path("key").asText();

        // 1) Resolver tests -> issueIds numéricos (Xray GraphQL trabaja con ids, no keys)
        List<JiraClient.Created> tests = List.of();
        String spec = ctx.in.tests();
        if (!spec.isBlank()) {
            String jql;
            if (spec.toLowerCase().startsWith("jql:")) {
                jql = spec.substring(4).trim();
            } else {
                List<String> keys = Inputs.parseKeys(spec);
                jql = "key in (" + String.join(",", keys) + ")";
            }
            tests = ctx.jira.search(jql, MAX_TESTS);
            ctx.report.note("Tests resueltos: " + tests.size() + " (JQL: " + jql + ")");
            if (tests.isEmpty()) throw new IllegalArgumentException("La selección de tests no devolvió issues");
        } else {
            ctx.report.note("Sin tests: se crea el Test Plan vacío.");
        }

        // 2) Crear el Test Plan
        ObjectNode f = JSON.createObjectNode();
        f.putObject("project").put("key", projectKey);
        f.putObject("issuetype").put("name", ctx.cfg.testPlanType());
        f.put("summary", summary);
        f.set("description", Adf.doc(ctx.in.description(), ctx.footer()));
        f.putArray("labels").add("qe-helper");
        ObjectNode update = JSON.createObjectNode();
        ObjectNode add = update.putArray("issuelinks").addObject().putObject("add");
        add.putObject("type").put("name", ctx.cfg.linkType());
        add.putObject("outwardIssue").put("key", storyKey);

        JiraClient.Created plan = ctx.jira.create(f, update);
        ctx.report.add(ctx.createdStatus(), plan.key(), plan.summary(), ctx.jira.browseUrl(plan.key()),
                ctx.cfg.linkType() + " → " + storyKey);

        // 3) Asociar tests en Xray
        if (!tests.isEmpty()) {
            try {
                int added = ctx.xray().addTestsToTestPlan(plan.id(), tests.stream().map(JiraClient.Created::id).toList());
                ctx.report.add(Status.INFO, plan.key(), added + " test(s) asociados al plan", null,
                        tests.stream().map(JiraClient.Created::key).limit(20).reduce((a, b) -> a + ", " + b).orElse("")
                                + (tests.size() > 20 ? "…" : ""));
            } catch (RuntimeException e) {
                // El plan ya existe: reportamos el fallo parcial sin perder la referencia al plan.
                ctx.report.add(Status.FAILED, plan.key(), "No se pudieron asociar los tests", null, e.getMessage());
            }
        }
    }
}
