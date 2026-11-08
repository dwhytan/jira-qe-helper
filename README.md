# Jira QE Helper

Crea en bulk las subtareas QE de una historia de usuario en Jira Cloud desde un
**workflow de GitHub Actions**. El formulario del workflow es la interfaz y las credenciales
las inyecta el pipeline desde un gestor de secretos, nunca la máquina del usuario.

## v1 — Subtareas QE

Dada una historia (`issue_key`), crea estas subtareas:

1. (QE) Análisis y elaboración de plan de pruebas
2. (QE) Creación de casos de prueba
3. (QE) Preparación de datos y ambiente de pruebas
4. (QE) Ejecución de pruebas y reporte de defectos
5. (QE) Registro de evidencias y cierre de pruebas

- Una sola llamada `POST /rest/api/3/issue/bulk`.
- **No duplica**: si una subtarea ya existe en la historia, la omite.
- El tipo subtarea se detecta del proyecto, sin importar su nombre (`Subtask`, `Subtarea`…).
- El resultado aparece en la pestaña **Summary** del run.

## Estructura

```
src/main/java/com/qehelper/App.java         flujo de la v1
src/main/java/com/qehelper/JiraClient.java  cliente REST de Jira
mock/jira_mock.py                           Jira simulado para probar sin instancia real
.github/workflows/qe-helper.yml             formulario (workflow_dispatch)
.github/workflows/ci.yml                    e2e contra el mock en cada push
scripts/run-local.sh                        corre el workflow en local
scripts/e2e-mock.sh                         pruebas e2e
```

El build (`mvn package`) genera `target/qe-helper.jar` con Jackson incluido, y el workflow
lo ejecuta en el runner con `java -jar`.

## Secretos

`JIRA_EMAIL` y `JIRA_API_TOKEN` se guardan en los Secrets del Environment (`mock` o `jira-cloud`).
El Environment `jira-cloud` además necesita la variable `JIRA_BASE_URL`.
La app solo lee variables de entorno: en la corporación esos valores vendrían de HashiCorp Vault.

## Uso

**En GitHub:** Settings → Environments → `mock` → agrega los 2 secrets (valores inventados).
Luego Actions → QE Helper → Run workflow → `issue_key = QA-100`.

**En local** (JDK 21, Maven, Python 3):

```bash
cp .secrets.example .secrets
./scripts/run-local.sh QA-100              # contra el mock
./scripts/e2e-mock.sh                      # 6 escenarios
```

Para un Jira Cloud real: pon el token en `.secrets`, crea `.vars` con `JIRA_BASE_URL` y usa `--jira-cloud`.

## Versiones

| Versión | Alcance                                                        | Estado |
|---------|----------------------------------------------------------------|--------|
| v1      | 5 subtareas QE en bulk                                         | ✅     |
| v2      | Elegir qué subtareas crear                                     | —      |
| v3      | Crear defecto vinculado a la historia                          | —      |
| v4      | Crear Test Plan y asociar tests (Xray)                         | —      |
