package com.qehelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * v1: crea en bulk las subtareas QE de una historia de usuario.
 * Todo llega por variables de entorno: credenciales desde el gestor de secretos del pipeline
 * y el issue padre desde el formulario del workflow.
 */
public final class App {

    static final List<String> SUBTASKS = List.of(
            "(QE) Análisis y elaboración de plan de pruebas",
            "(QE) Creación de casos de prueba",
            "(QE) Preparación de datos y ambiente de pruebas",
            "(QE) Ejecución de pruebas y reporte de defectos",
            "(QE) Registro de evidencias y cierre de pruebas");

    private static final Pattern ISSUE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]+-\\d+$");

    public static void main(String[] args) {
        List<String> rows = new ArrayList<>();
        String error = null;
        try {
            rows = run();
        } catch (RuntimeException e) {
            error = e.getMessage();
            System.err.println("ERROR: " + error);
        }
        writeSummary(rows, error);
        System.exit(error == null && rows.stream().noneMatch(r -> r.startsWith("| ❌")) ? 0 : 1);
    }

    private static List<String> run() {
        String parentKey = env("QE_ISSUE_KEY").toUpperCase();
        if (!ISSUE_KEY.matcher(parentKey).matches()) {
            throw new IllegalArgumentException("issue_key inválido: '" + parentKey + "' (formato esperado: PROJ-123)");
        }
        List<String> selected = selectedSubtasks();
        List<String> rows = new ArrayList<>();
        for (String summary : SUBTASKS) {
            if (!selected.contains(summary)) rows.add(row("➖ No seleccionada", "", summary));
        }
        if (selected.isEmpty()) {
            System.out.println("Ninguna subtarea seleccionada: no se llama a Jira.");
            return rows;
        }

        List<String> missing = new ArrayList<>();
        for (String name : List.of("JIRA_BASE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN")) {
            if (env(name).isEmpty()) missing.add(name);
        }
        if (!missing.isEmpty()) throw new IllegalStateException("Faltan secretos/variables: " + String.join(", ", missing));

        JiraClient jira = new JiraClient(env("JIRA_BASE_URL"), env("JIRA_EMAIL"), env("JIRA_API_TOKEN"));
        JsonNode parent = jira.getIssue(parentKey).path("fields");
        String projectKey = parent.path("project").path("key").asText();
        System.out.println("Padre: " + parentKey + " — " + parent.path("summary").asText());

        // Idempotencia: no duplicar subtareas si el workflow se vuelve a ejecutar sobre el mismo padre.
        Set<String> existing = new HashSet<>();
        parent.path("subtasks").forEach(st -> existing.add(st.path("fields").path("summary").asText()));

        List<String> toCreate = new ArrayList<>();
        for (String summary : selected) {
            if (existing.contains(summary)) rows.add(row("⏭️ Ya existe", "", summary));
            else toCreate.add(summary);
        }
        if (toCreate.isEmpty()) return rows;

        String typeId = jira.subtaskTypeId(projectKey);
        ObjectNode body = JiraClient.JSON.createObjectNode();
        ArrayNode updates = body.putArray("issueUpdates");
        for (String summary : toCreate) {
            ObjectNode f = updates.addObject().putObject("fields");
            f.putObject("project").put("key", projectKey);
            f.putObject("parent").put("key", parentKey);
            f.putObject("issuetype").put("id", typeId);
            f.put("summary", summary);
            f.putArray("labels").add("qe-helper");
        }

        JsonNode res = jira.bulkCreate(body);
        // Jira devuelve los creados en orden, omitiendo los fallidos (indicados en failedElementNumber).
        Set<Integer> failed = new HashSet<>();
        for (JsonNode err : res.path("errors")) {
            int i = err.path("failedElementNumber").asInt();
            failed.add(i);
            rows.add(row("❌ Error", "", toCreate.get(i) + " — " + err.path("elementErrors").path("errors")));
        }
        int n = 0;
        for (int i = 0; i < toCreate.size(); i++) {
            if (failed.contains(i)) continue;
            String key = res.path("issues").path(n++).path("key").asText();
            rows.add(row("✅ Creada", "[" + key + "](" + jira.browseUrl(key) + ")", toCreate.get(i)));
        }
        return rows;
    }

    /**
     * QE_SUBTASK_1..5 vienen de los checkboxes del formulario ("true"/"false").
     * Si una no está definida (ejecución local) se considera seleccionada.
     */
    private static List<String> selectedSubtasks() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < SUBTASKS.size(); i++) {
            String name = "QE_SUBTASK_" + (i + 1);
            String v = env(name).toLowerCase();
            if (!v.isEmpty() && !v.equals("true") && !v.equals("false")) {
                throw new IllegalArgumentException(name + " inválido: '" + v + "' (esperado true o false)");
            }
            if (!v.equals("false")) selected.add(SUBTASKS.get(i));
        }
        return selected;
    }

    private static String row(String status, String key, String summary) {
        String r = "| " + status + " | " + key + " | " + summary.replace("|", "\\|") + " |";
        System.out.println(r);
        return r;
    }

    /** Resultado visible en la pestaña Summary del run de GitHub Actions. */
    private static void writeSummary(List<String> rows, String error) {
        String file = env("GITHUB_STEP_SUMMARY");
        if (file.isEmpty()) return;
        StringBuilder md = new StringBuilder("## Subtareas QE · " + env("QE_ISSUE_KEY").toUpperCase() + "\n\n");
        if (error != null) md.append("> ❌ ").append(error).append("\n\n");
        if (!rows.isEmpty()) {
            md.append("| Estado | Issue | Resumen |\n|---|---|---|\n");
            rows.forEach(r -> md.append(r).append('\n'));
        }
        try {
            Files.writeString(Path.of(file), md.append('\n'), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("No se pudo escribir el summary: " + e.getMessage());
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }
}
