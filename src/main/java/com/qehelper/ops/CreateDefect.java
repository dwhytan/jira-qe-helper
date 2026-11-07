package com.qehelper.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.jira.Adf;
import com.qehelper.jira.JiraClient;

import static com.qehelper.http.HttpGateway.JSON;

/** Crea un defecto en el proyecto del issue relacionado y lo vincula a él. */
public final class CreateDefect implements Operation {

    @Override
    public void run(Context ctx) {
        String relatedKey = ctx.in.requireIssueKey();
        String summary = ctx.in.requireSummary();

        JsonNode related = ctx.jira.getIssue(relatedKey, "summary,project");
        String projectKey = related.path("fields").path("project").path("key").asText();
        ctx.report.note("Relacionado con: " + relatedKey + " — " + related.path("fields").path("summary").asText());

        ObjectNode f = JSON.createObjectNode();
        f.putObject("project").put("key", projectKey);
        f.putObject("issuetype").put("name", ctx.cfg.bugType());
        f.put("summary", summary);
        f.set("description", Adf.doc(ctx.in.description(), ctx.footer()));
        f.putArray("labels").add("qe-helper").add("defecto-qa");

        // El link se crea en la misma llamada para que no quede un defecto huérfano.
        ObjectNode update = JSON.createObjectNode();
        ObjectNode add = update.putArray("issuelinks").addObject().putObject("add");
        add.putObject("type").put("name", ctx.cfg.linkType());
        add.putObject("outwardIssue").put("key", relatedKey);

        JiraClient.Created c = ctx.jira.create(f, update);
        ctx.report.add(ctx.createdStatus(), c.key(), c.summary(), ctx.jira.browseUrl(c.key()),
                ctx.cfg.linkType() + " → " + relatedKey);
    }
}
