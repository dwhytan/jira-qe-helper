package com.qehelper.jira;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qehelper.http.HttpGateway;

/** Atlassian Document Format mínimo: la API v3 de Jira Cloud exige ADF en description. */
public final class Adf {

    private Adf() { }

    /** Cada línea del texto es un párrafo; se añade un pie de trazabilidad en cursiva. */
    public static ObjectNode doc(String text, String footer) {
        ObjectNode doc = HttpGateway.JSON.createObjectNode();
        doc.put("type", "doc").put("version", 1);
        ArrayNode content = doc.putArray("content");
        if (text != null) {
            for (String line : text.replace("\\n", "\n").split("\\R")) {
                if (!line.isBlank()) content.add(paragraph(line, false));
            }
        }
        if (footer != null && !footer.isBlank()) {
            content.add(HttpGateway.JSON.createObjectNode().put("type", "rule"));
            content.add(paragraph(footer, true));
        }
        return doc;
    }

    private static ObjectNode paragraph(String text, boolean italic) {
        ObjectNode p = HttpGateway.JSON.createObjectNode().put("type", "paragraph");
        ObjectNode t = p.putArray("content").addObject().put("type", "text").put("text", text);
        if (italic) t.putArray("marks").addObject().put("type", "em");
        return p;
    }
}
