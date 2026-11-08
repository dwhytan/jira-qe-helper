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
        List<Phase> phases = selectedPhases();
        List<String> rows = new ArrayList<>();
        List<Item> selected = new ArrayList<>();
        int n = 0;
        for (Phase p : PHASES) {
            for (String summary : p.subtasks()) {
                n++;
                if (phases.contains(p) && flag("QE_SUBTASK_" + n)) selected.add(new Item(p.name(), summary));
                else rows.add(row("➖ No seleccionada", p.name(), "", summary));
            }
        }
        if (selected.isEmpty()) {
            System.out.println("Ninguna fase seleccionada: no se llama a Jira.");
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
        int created = 0;
        for (int i = 0; i < toCreate.size(); i++) {
            if (failed.contains(i)) continue;
            String key = res.path("issues").path(created++).path("key").asText();
            Item it = toCreate.get(i);
            rows.add(row("✅ Creada", it.phase(), "[" + key + "](" + jira.browseUrl(key) + ")", it.summary()));
        }
        return rows;
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

    /** Resultado visible en la pestaña Summary del run de GitHub Actions. */
    private static void writeSummary(List<String> rows, String error) {
        String file = env("GITHUB_STEP_SUMMARY");
        if (file.isEmpty()) return;
        StringBuilder md = new StringBuilder("## Subtareas QE · " + env("QE_ISSUE_KEY").toUpperCase() + "\n\n");
        if (error != null) md.append("> ❌ ").append(error).append("\n\n");
        if (!rows.isEmpty()) {
            md.append("| Estado | Fase | Issue | Resumen |\n|---|---|---|---|\n");
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
