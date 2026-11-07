package com.qehelper.report;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.http.HttpGateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * La "vista de resultados": antes era un JOptionPane, ahora es el Job Summary de Actions,
 * los outputs del step y un JSON que se sube como artifact (trazabilidad/auditoría).
 */
public final class Report {

    public enum Status { CREATED, SKIPPED, FAILED, DRY_RUN, INFO }

    public record Row(Status status, String key, String summary, String url, String detail) { }

    private final String operation;
    private final String actor;
    private final String runUrl;
    private final boolean dryRun;
    private final List<Row> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public Report(String operation, String actor, String runUrl, boolean dryRun) {
        this.operation = operation;
        this.actor = actor;
        this.runUrl = runUrl;
        this.dryRun = dryRun;
    }

    public void add(Status status, String key, String summary, String url, String detail) {
        rows.add(new Row(status, key, summary, url, detail));
        String icon = switch (status) {
            case CREATED -> "✅"; case SKIPPED -> "⏭ "; case FAILED -> "❌"; case DRY_RUN -> "🧪"; case INFO -> "ℹ️";
        };
        System.out.printf("  %s %-12s %s%s%n", icon, key == null ? "" : key, summary == null ? "" : summary,
                detail == null || detail.isBlank() ? "" : "  (" + detail + ")");
    }

    public void note(String text) {
        notes.add(text);
        System.out.println("  · " + text);
    }

    public boolean hasFailures() {
        return rows.stream().anyMatch(r -> r.status() == Status.FAILED);
    }

    public List<String> createdKeys() {
        return rows.stream().filter(r -> r.status() == Status.CREATED || r.status() == Status.DRY_RUN)
                .map(Row::key).filter(k -> k != null && !k.isBlank()).toList();
    }

    public void publish(String jsonPath, String fatalError) {
        writeJson(jsonPath, fatalError);
        appendEnvFile("GITHUB_STEP_SUMMARY", markdown(fatalError));
        appendEnvFile("GITHUB_OUTPUT", "created_keys=" + String.join(",", createdKeys()) + "\n"
                + "failed=" + (hasFailures() || fatalError != null) + "\n");
    }

    private String markdown(String fatalError) {
        StringBuilder md = new StringBuilder();
        md.append("## QE Helper · `").append(operation).append("`")
                .append(dryRun ? " 🧪 DRY-RUN" : "").append("\n\n");
        md.append("Ejecutado por **@").append(actor).append("** · ").append(OffsetDateTime.now()).append("\n\n");
        if (fatalError != null) md.append("> ❌ **Error:** ").append(escape(fatalError)).append("\n\n");
        if (!rows.isEmpty()) {
            md.append("| Estado | Issue | Resumen | Detalle |\n|---|---|---|---|\n");
            for (Row r : rows) {
                String key = r.key() == null ? "" : (r.url() == null ? r.key() : "[" + r.key() + "](" + r.url() + ")");
                md.append("| ").append(r.status()).append(" | ").append(key).append(" | ")
                        .append(escape(r.summary())).append(" | ").append(escape(r.detail())).append(" |\n");
            }
            md.append("\n");
        }
        for (String n : notes) md.append("- ").append(escape(n)).append("\n");
        return md.append("\n").toString();
    }

    private void writeJson(String path, String fatalError) {
        ObjectNode root = HttpGateway.JSON.createObjectNode()
                .put("operation", operation).put("actor", actor).put("runUrl", runUrl)
                .put("dryRun", dryRun).put("timestamp", OffsetDateTime.now().toString());
        if (fatalError != null) root.put("error", fatalError);
        ArrayNode arr = root.putArray("results");
        for (Row r : rows) {
            arr.addObject().put("status", r.status().name()).put("key", r.key())
                    .put("summary", r.summary()).put("url", r.url()).put("detail", r.detail());
        }
        notes.forEach(root.putArray("notes")::add);
        try {
            Files.writeString(Path.of(path), HttpGateway.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            System.err.println("No se pudo escribir " + path + ": " + e.getMessage());
        }
    }

    private static void appendEnvFile(String envVar, String content) {
        String file = System.getenv(envVar);
        if (file == null || file.isBlank()) return; // ejecución local
        try {
            Files.writeString(Path.of(file), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("No se pudo escribir " + envVar + ": " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
