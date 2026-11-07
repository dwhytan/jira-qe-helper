package com.qehelper.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lo que antes eran los campos de QEHelperView: ahora son los inputs del formulario
 * workflow_dispatch, pasados como variables de entorno QE_* (nunca interpolados en el script).
 */
public record Inputs(
        String operation,
        String issueKey,
        String subtasks,
        String summary,
        String description,
        String tests) {

    private static final Pattern ISSUE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]+-\\d+$");

    public static Inputs fromEnv() {
        return from(System.getenv());
    }

    public static Inputs from(Map<String, String> env) {
        return new Inputs(
                v(env, "QE_OPERATION").toLowerCase(),
                v(env, "QE_ISSUE_KEY").toUpperCase(),
                v(env, "QE_SUBTASKS"),
                v(env, "QE_SUMMARY"),
                env.getOrDefault("QE_DESCRIPTION", ""),
                v(env, "QE_TESTS"));
    }

    public String requireIssueKey() {
        if (!ISSUE_KEY.matcher(issueKey).matches()) {
            throw new IllegalArgumentException("issue_key inválido: '" + issueKey + "' (formato esperado: PROJ-123)");
        }
        return issueKey;
    }

    public String requireSummary() {
        if (summary.isBlank()) throw new IllegalArgumentException("summary es obligatorio para esta operación");
        if (summary.length() > 255) throw new IllegalArgumentException("summary excede 255 caracteres");
        return summary;
    }

    /** "QA-1, QA-2" -> [QA-1, QA-2]; valida cada key. */
    public static List<String> parseKeys(String csv) {
        List<String> keys = Arrays.stream(csv.split("[,;\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).distinct().toList();
        for (String k : keys) {
            if (!ISSUE_KEY.matcher(k).matches()) throw new IllegalArgumentException("Key inválida en tests: " + k);
        }
        return keys;
    }

    private static String v(Map<String, String> env, String name) {
        String s = env.get(name);
        return s == null ? "" : s.trim();
    }
}
