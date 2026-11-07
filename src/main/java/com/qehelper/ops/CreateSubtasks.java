package com.qehelper.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.jira.Adf;
import com.qehelper.jira.JiraClient;
import com.qehelper.report.Report.Status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.qehelper.http.HttpGateway.JSON;

/** Crea en bulk las subtareas QA del catálogo bajo un issue padre. Idempotente por summary. */
public final class CreateSubtasks implements Operation {

    @Override
    public void run(Context ctx) {
        String parentKey = ctx.in.requireIssueKey();
        List<SubtaskCatalog.Template> selected = SubtaskCatalog.load().select(ctx.in.subtasks());

        JsonNode parent = ctx.jira.getIssue(parentKey, "summary,project,issuetype,subtasks");
        JsonNode pf = parent.path("fields");
        if (pf.path("issuetype").path("subtask").asBoolean(false)) {
            throw new IllegalArgumentException(parentKey + " ya es una subtarea; elige la historia/tarea padre");
        }
        String projectKey = pf.path("project").path("key").asText();
        ctx.report.note("Padre: " + parentKey + " — " + pf.path("summary").asText()
                + " (" + pf.path("issuetype").path("name").asText() + ")");

        Set<String> existing = new HashSet<>();
        for (JsonNode st : pf.path("subtasks")) existing.add(st.path("fields").path("summary").asText().trim());

        List<ObjectNode> toCreate = new ArrayList<>();
        for (SubtaskCatalog.Template t : selected) {
            String summary = "QA | " + t.name() + " | " + parentKey;
            if (existing.contains(summary)) {
                ctx.report.add(Status.SKIPPED, null, summary, null, "ya existe en " + parentKey);
                continue;
            }
            ObjectNode f = JSON.createObjectNode();
            f.putObject("project").put("key", projectKey);
            f.putObject("parent").put("key", parentKey);
            f.putObject("issuetype").put("name", ctx.cfg.subtaskType());
            f.put("summary", summary);
            f.set("description", Adf.doc(t.description(), ctx.footer()));
            f.putArray("labels").add("qe-helper").add("qa-" + t.code().toLowerCase());
            toCreate.add(f);
        }

        if (toCreate.isEmpty()) {
            ctx.report.note("Nada que crear: todas las subtareas seleccionadas ya existen.");
            return;
        }

        JiraClient.BulkResult res = ctx.jira.bulkCreate(toCreate);
        res.created().forEach(c -> ctx.report.add(ctx.createdStatus(), c.key(), c.summary(), ctx.jira.browseUrl(c.key()), null));
        res.failed().forEach(f -> ctx.report.add(Status.FAILED, null, f.summary(), null, f.error()));
    }
}
