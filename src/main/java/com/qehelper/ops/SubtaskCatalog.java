package com.qehelper.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.qehelper.http.HttpGateway;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de subtareas QA (lo que antes eran los checkboxes de la pestaña "Subtareas").
 * Vive en src/main/resources/subtasks-catalog.json para poder versionarlo y revisarlo por PR.
 */
public final class SubtaskCatalog {

    public record Template(String code, String name, String description) { }

    private final Map<String, Template> byCode = new LinkedHashMap<>();

    public static SubtaskCatalog load() {
        try (InputStream is = SubtaskCatalog.class.getResourceAsStream("/subtasks-catalog.json")) {
            if (is == null) throw new IllegalStateException("No se encontró subtasks-catalog.json");
            List<Template> list = HttpGateway.JSON.readValue(is, new TypeReference<>() { });
            SubtaskCatalog c = new SubtaskCatalog();
            list.forEach(t -> c.byCode.put(t.code().toUpperCase(), t));
            return c;
        } catch (IOException e) {
            throw new IllegalStateException("Catálogo inválido: " + e.getMessage(), e);
        }
    }

    /** "ALL" o "DISENO,FUNCIONAL,REGRESION". */
    public List<Template> select(String csv) {
        if (csv == null || csv.isBlank() || csv.trim().equalsIgnoreCase("ALL")) {
            return new ArrayList<>(byCode.values());
        }
        List<Template> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String code : Arrays.stream(csv.split("[,;\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            Template t = byCode.get(code.toUpperCase());
            if (t == null) unknown.add(code);
            else if (!out.contains(t)) out.add(t);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Códigos de subtarea desconocidos: " + unknown
                    + ". Válidos: " + String.join(", ", byCode.keySet()));
        }
        return out;
    }
}
