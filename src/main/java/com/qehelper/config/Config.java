package com.qehelper.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reemplaza a UserSession: ya no hay login, las credenciales llegan como variables de entorno
 * inyectadas por el pipeline (GitHub Secrets en la simulación, HashiCorp Vault en la corporación).
 * Nunca se escriben a disco ni se imprimen.
 */
public record Config(
        String jiraBaseUrl,
        String jiraEmail,
        String jiraApiToken,
        String xrayBaseUrl,
        String xrayClientId,
        String xrayClientSecret,
        boolean dryRun,
        String actor,
        String runUrl,
        String subtaskType,
        String bugType,
        String testPlanType,
        String linkType,
        String reportPath) {

    public static Config fromEnv() {
        return from(System.getenv());
    }

    public static Config from(Map<String, String> env) {
        String runUrl = "";
        if (!blank(env.get("GITHUB_SERVER_URL")) && !blank(env.get("GITHUB_RUN_ID"))) {
            runUrl = env.get("GITHUB_SERVER_URL") + "/" + env.get("GITHUB_REPOSITORY")
                    + "/actions/runs/" + env.get("GITHUB_RUN_ID");
        }
        return new Config(
                stripSlash(env.get("JIRA_BASE_URL")),
                trim(env.get("JIRA_EMAIL")),
                trim(env.get("JIRA_API_TOKEN")),
                stripSlash(or(env.get("XRAY_BASE_URL"), "https://xray.cloud.getxray.app")),
                trim(env.get("XRAY_CLIENT_ID")),
                trim(env.get("XRAY_CLIENT_SECRET")),
                Boolean.parseBoolean(or(env.get("DRY_RUN"), "false")),
                or(env.get("GITHUB_ACTOR"), System.getProperty("user.name", "local")),
                runUrl,
                or(env.get("JIRA_SUBTASK_TYPE"), "Sub-task"),
                or(env.get("JIRA_BUG_TYPE"), "Bug"),
                or(env.get("XRAY_TEST_PLAN_TYPE"), "Test Plan"),
                or(env.get("JIRA_LINK_TYPE"), "Relates"),
                or(env.get("QE_REPORT_PATH"), "qe-report.json"));
    }

    /** Valida que el pipeline haya inyectado lo necesario. Mensajes sin revelar valores. */
    public void requireJira() {
        List<String> missing = new ArrayList<>();
        if (blank(jiraBaseUrl)) missing.add("JIRA_BASE_URL");
        if (blank(jiraEmail)) missing.add("JIRA_EMAIL");
        if (blank(jiraApiToken)) missing.add("JIRA_API_TOKEN");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Faltan secretos/variables: " + String.join(", ", missing)
                    + ". Revisa los secrets del environment en GitHub (o el path en Vault).");
        }
    }

    public void requireXray() {
        List<String> missing = new ArrayList<>();
        if (blank(xrayClientId)) missing.add("XRAY_CLIENT_ID");
        if (blank(xrayClientSecret)) missing.add("XRAY_CLIENT_SECRET");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Faltan secretos de Xray: " + String.join(", ", missing));
        }
    }

    @Override
    public String toString() { // evita filtrar secretos si alguien loguea el record
        return "Config[jira=" + jiraBaseUrl + ", xray=" + xrayBaseUrl + ", dryRun=" + dryRun + ", actor=" + actor + "]";
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String trim(String s) { return s == null ? null : s.trim(); }
    private static String or(String s, String def) { return blank(s) ? def : s.trim(); }
    private static String stripSlash(String s) {
        if (blank(s)) return s;
        s = s.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
