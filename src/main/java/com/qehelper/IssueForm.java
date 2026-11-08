package com.qehelper;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee el cuerpo de un issue creado con .github/ISSUE_TEMPLATE/qe-subtareas.yml.
 * GitHub lo renderiza en markdown:
 *
 * <pre>
 * ### Historia de usuario
 *
 * QA-100
 *
 * ### Planificación
 *
 * - [X] (QE) Análisis de criterios de aceptación
 * - [ ] (QE) Elaboración de plan de pruebas
 * </pre>
 *
 * El cuerpo lo escribe el usuario: solo se extraen la clave (validada después) y las
 * casillas marcadas cuyo texto coincide exactamente con el catálogo.
 */
record IssueForm(String issueKey, Set<String> checked) {

    static final String KEY_HEADING = "### Historia de usuario";
    private static final Pattern CHECKED = Pattern.compile("^- \\[[xX]\\] (.+)$");

    static IssueForm parse(String body) {
        String[] lines = body.replace("\r", "").split("\n");
        String key = "";
        Set<String> checked = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals(KEY_HEADING)) {
                for (int j = i + 1; j < lines.length && key.isEmpty(); j++) {
                    String v = lines[j].trim();
                    if (v.startsWith("###")) break;
                    key = v;
                }
            }
            Matcher m = CHECKED.matcher(line);
            if (m.matches()) checked.add(m.group(1).trim());
        }
        if (key.isEmpty() || key.equals("_No response_")) {
            throw new IllegalArgumentException("El formulario no tiene la sección '" + KEY_HEADING + "' con una clave");
        }
        return new IssueForm(key, checked);
    }
}
