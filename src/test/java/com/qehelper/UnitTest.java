package com.qehelper;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.config.Config;
import com.qehelper.config.Inputs;
import com.qehelper.jira.Adf;
import com.qehelper.ops.SubtaskCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnitTest {

    @Test
    void catalogSelectsAllOrSubset() {
        SubtaskCatalog c = SubtaskCatalog.load();
        assertEquals(9, c.select("ALL").size());
        assertEquals(List.of("DISENO", "REGRESION"),
                c.select("diseno, REGRESION,diseno").stream().map(SubtaskCatalog.Template::code).toList());
        assertThrows(IllegalArgumentException.class, () -> c.select("DISENO,NOEXISTE"));
    }

    @Test
    void inputsValidateIssueKeys() {
        Inputs ok = Inputs.from(Map.of("QE_ISSUE_KEY", " qa-100 "));
        assertEquals("QA-100", ok.requireIssueKey());
        Inputs bad = Inputs.from(Map.of("QE_ISSUE_KEY", "QA-100; rm -rf /"));
        assertThrows(IllegalArgumentException.class, bad::requireIssueKey);
        assertEquals(List.of("QA-1", "QA-2"), Inputs.parseKeys("qa-1, QA-2 qa-1"));
        assertThrows(IllegalArgumentException.class, () -> Inputs.parseKeys("QA-1,) OR 1=1"));
    }

    @Test
    void configNeverPrintsSecrets() {
        Config cfg = Config.from(Map.of("JIRA_BASE_URL", "https://x.atlassian.net/", "JIRA_EMAIL", "a@b.c",
                "JIRA_API_TOKEN", "SUPERSECRET"));
        assertEquals("https://x.atlassian.net", cfg.jiraBaseUrl());
        assertFalse(cfg.toString().contains("SUPERSECRET"));
        assertThrows(IllegalStateException.class, cfg::requireXray);
    }

    @Test
    void adfBuildsParagraphsAndFooter() {
        ObjectNode doc = Adf.doc("Paso 1\n\nPaso 2", "pie");
        assertEquals("doc", doc.path("type").asText());
        assertEquals(4, doc.path("content").size()); // 2 párrafos + rule + pie
    }
}
