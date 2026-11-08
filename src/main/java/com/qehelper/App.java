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
 * Crea en bulk las subtareas QE de una historia de usuario, agrupadas por fase.
 * Todo llega por variables de entorno: credenciales desde el gestor de secretos del pipeline,
 * y el issue padre y las fases elegidas desde el formulario del workflow.
 */
public final class App {

    record Phase(String name, List<String> subtasks) {}

    /** El orden importa: la fase N se activa con QE_FASE_N. */
    static final List<Phase> PHASES = List.of(
            new Phase("Planificación", List.of(
                    "(QE) Análisis de criterios de aceptación",
                    "(QE) Elaboración de plan de pruebas",
                    "(QE) Estimación de esfuerzo de pruebas",
                    "(QE) Identificación de riesgos de calidad")),
            new Phase("Diseño", List.of(
                    "(QE) Diseño de casos de prueba funcionales",
                    "(QE) Diseño de pruebas de performance",
                    "(QE) Diseño de pruebas de seguridad",
                    "(QE) Revisión de casos de prueba con el equipo")),
            new Phase("Preparación", List.of(
                    "(QE) Preparación de datos de prueba",
                    "(QE) Configuración de ambiente de pruebas",
                    "(QE) Automatización de casos de prueba",
                    "(QE) Smoke test del despliegue en QA")),
            new Phase("Ejecución", List.of(
                    "(QE) Ejecución de pruebas funcionales",
                    "(QE) Ejecución de pruebas de regresión",
                    "(QE) Ejecución de pruebas de performance",
                    "(QE) Ejecución de pruebas de seguridad")),
            new Phase("Cierre", List.of(
                    "(QE) Reporte y seguimiento de defectos",
                    "(QE) Registro de evidencias",
                    "(QE) Pruebas en producción (post-despliegue)",
                    "(QE) Informe de cierre de pruebas")));

    private record Item(String phase, String summary) {}

    private static final Pattern ISSUE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]+-\\d+$");

    /** Historia procesada (para el título del summary) y avisos no fatales. */
    private static String parentKey = "";
    private static final List<String> warnings = new ArrayList<>();
    private static int notSelected = 0;

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
        // Origen de la solicitud: Issue Form (QE_FORM_BODY) o formulario del workflow (QE_ISSUE_KEY + casillas).
        String formBody = env("QE_FORM_BODY");
        IssueForm form = formBody.isEmpty() ? null : IssueForm.parse(formBody);
        parentKey = (form != null ? form.issueKey() : env("QE_ISSUE_KEY")).toUpperCase();
        if (!ISSUE_KEY.matcher(parentKey).matches()) {
            String shown = parentKey.length() > 40 ? parentKey.substring(0, 40) + "…" : parentKey;
            parentKey = "";
            throw new IllegalArgumentException("issue_key inválido: '" + shown + "' (formato esperado: PROJ-123)");
        }
        List<Phase> phases = selectedPhases();
        List<String> rows = new ArrayList<>();
        List<Item> selected = new ArrayList<>();
        int n = 0;
        for (Phase p : PHASES) {
            for (String summary : p.subtasks()) {
                n++;
                boolean chosen = phases.contains(p) && flag("QE_SUBTASK_" + n)
                        && (form == null || form.checked().contains(summary));
                if (chosen) selected.add(new Item(p.name(), summary));
                else notSelected++;
            }
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

        List<Item> toCreate = new ArrayList<>();
        for (Item it : selected) {
            if (existing.contains(it.summary())) rows.add(row("⏭️ Ya existe", it.phase(), "", it.summary()));
            else toCreate.add(it);
        }
        if (toCreate.isEmpty()) return rows;

        String typeId = jira.subtaskTypeId(projectKey);
        ObjectNode body = JiraClient.JSON.createObjectNode();
        ArrayNode updates = body.putArray("issueUpdates");
        for (Item it : toCreate) {
            ObjectNode f = updates.addObject().putObject("fields");
            f.putObject("project").put("key", projectKey);
            f.putObject("parent").put("key", parentKey);
            f.putObject("issuetype").put("id", typeId);
            f.put("summary", it.summary());
            f.putArray("labels").add("qe-helper");
        }

        JsonNode res = jira.bulkCreate(body);
        // Jira devuelve los creados en orden, omitiendo los fallidos (indicados en failedElementNumber).
        Set<Integer> failed = new HashSet<>();
        for (JsonNode err : res.path("errors")) {
            int i = err.path("failedElementNumber").asInt();
            failed.add(i);
            Item it = toCreate.get(i);
            rows.add(row("❌ Error", it.phase(), "", it.summary() + " — " + err.path("elementErrors").path("errors")));
        }
        List<String> createdLines = new ArrayList<>();
        for (int i = 0; i < toCreate.size(); i++) {
            if (failed.contains(i)) continue;
            String key = res.path("issues").path(createdLines.size()).path("key").asText();
            Item it = toCreate.get(i);
            rows.add(row("✅ Creada", it.phase(), "[" + key + "](" + jira.browseUrl(key) + ")", it.summary()));
            createdLines.add(key + " · " + it.summary());
        }
        if (!createdLines.isEmpty()) commentOnParent(jira, createdLines);
        return rows;
    }

    /**
     * Deja constancia en la historia de quién pidió las subtareas (las crea la cuenta de servicio).
     * Si falla no se revierte nada: las subtareas ya existen, solo se avisa en el summary.
     */
    private static void commentOnParent(JiraClient jira, List<String> createdLines) {
        String requester = env("QE_REQUESTED_BY").isEmpty() ? env("GITHUB_ACTOR") : env("QE_REQUESTED_BY");
        String source = env("QE_REQUEST_URL");
        if (source.isEmpty() && !env("GITHUB_RUN_ID").isEmpty()) {
            source = env("GITHUB_SERVER_URL") + "/" + env("GITHUB_REPOSITORY") + "/actions/runs/" + env("GITHUB_RUN_ID");
        }
        ObjectNode doc = JiraClient.JSON.createObjectNode().put("type", "doc").put("version", 1);
        ArrayNode content = doc.putArray("content");
        paragraph(content, "QE Helper creó " + createdLines.size() + " subtarea(s)"
                + (requester.isEmpty() ? "" : " a pedido de " + requester) + ":", null);
        ArrayNode items = content.addObject().put("type", "bulletList").putArray("content");
        for (String line : createdLines) {
            paragraph(items.addObject().put("type", "listItem").putArray("content"), line, null);
        }
        if (!source.isEmpty()) paragraph(content, "Ver solicitud en GitHub", source);
        try {
            jira.addComment(parentKey, doc);
            System.out.println("Comentario agregado en " + parentKey);
        } catch (RuntimeException e) {
            warnings.add("No se pudo comentar en " + parentKey + ": " + e.getMessage());
            System.err.println("AVISO: " + warnings.get(warnings.size() - 1));
        }
    }

    private static void paragraph(ArrayNode parent, String text, String link) {
        ObjectNode t = parent.addObject().put("type", "paragraph").putArray("content").addObject()
                .put("type", "text").put("text", text);
        if (link != null) t.putArray("marks").addObject().put("type", "link").putObject("attrs").put("href", link);
    }

    /**
     * Las casillas del formulario llegan como "true"/"false":
     * QE_FASE_1..5 (workflow por fases) o QE_SUBTASK_1..20 (workflow por subtarea).
     * Una subtarea se crea si su fase y ella misma están seleccionadas; si una variable
     * no está definida (ejecución local o el otro workflow) se considera seleccionada.
     */
    private static List<Phase> selectedPhases() {
        List<Phase> selected = new ArrayList<>();
        for (int i = 0; i < PHASES.size(); i++) {
            if (flag("QE_FASE_" + (i + 1))) selected.add(PHASES.get(i));
        }
        return selected;
    }

    private static boolean flag(String name) {
        String v = env(name).toLowerCase();
        if (!v.isEmpty() && !v.equals("true") && !v.equals("false")) {
            throw new IllegalArgumentException(name + " inválido: '" + v + "' (esperado true o false)");
        }
        return !v.equals("false");
    }

    private static String row(String status, String phase, String key, String summary) {
        String r = "| " + status + " | " + phase + " | " + key + " | " + summary.replace("|", "\\|") + " |";
        System.out.println(r);
        return r;
    }

    /**
     * Resultado en markdown: pestaña Summary del run (GITHUB_STEP_SUMMARY) y, si se pide,
     * QE_RESULT_FILE para publicarlo como comentario en el issue de GitHub.
     */
    private static void writeSummary(List<String> rows, String error) {
        StringBuilder md = new StringBuilder("## Subtareas QE" + (parentKey.isEmpty() ? "" : " · " + parentKey) + "\n\n");
        if (error != null) md.append("> ❌ ").append(error).append("\n\n");
        warnings.forEach(w -> md.append("> ⚠️ ").append(w).append("\n\n"));
        if (error == null && notSelected > 0) {
            md.append(notSelected == PHASES.stream().mapToInt(p -> p.subtasks().size()).sum()
                    ? "Ninguna subtarea seleccionada: no se creó nada.\n\n"
                    : "➖ " + notSelected + " subtareas no seleccionadas.\n\n");
        }
        if (!rows.isEmpty()) {
            md.append("| Estado | Fase | Issue | Resumen |\n|---|---|---|---|\n");
            rows.forEach(r -> md.append(r).append('\n'));
        }
        md.append('\n');
        for (String file : List.of(env("GITHUB_STEP_SUMMARY"), env("QE_RESULT_FILE"))) {
            if (file.isEmpty()) continue;
            try {
                Files.writeString(Path.of(file), md, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("No se pudo escribir " + file + ": " + e.getMessage());
            }
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }
}
