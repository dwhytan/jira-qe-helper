# Jira QE Helper

Crea en bulk las subtareas QE de una historia de usuario en Jira Cloud desde un
**workflow de GitHub Actions**. El formulario del workflow es la interfaz y las credenciales
las inyecta el pipeline desde un gestor de secretos, nunca la máquina del usuario.

## v2 — Subtareas QE por fase

Dada una historia (`issue_key`), el formulario muestra una casilla por fase (todas marcadas
por defecto). Cada fase crea sus 4 subtareas; puedes marcar todas, algunas o ninguna:

| Fase | Subtareas |
|---|---|
| Planificación | (QE) Análisis de criterios de aceptación · (QE) Elaboración de plan de pruebas · (QE) Estimación de esfuerzo de pruebas · (QE) Identificación de riesgos de calidad |
| Diseño | (QE) Diseño de casos de prueba funcionales · (QE) Diseño de pruebas de performance · (QE) Diseño de pruebas de seguridad · (QE) Revisión de casos de prueba con el equipo |
| Preparación | (QE) Preparación de datos de prueba · (QE) Configuración de ambiente de pruebas · (QE) Automatización de casos de prueba · (QE) Smoke test del despliegue en QA |
| Ejecución | (QE) Ejecución de pruebas funcionales · (QE) Ejecución de pruebas de regresión · (QE) Ejecución de pruebas de performance · (QE) Ejecución de pruebas de seguridad |
| Cierre | (QE) Reporte y seguimiento de defectos · (QE) Registro de evidencias · (QE) Pruebas en producción (post-despliegue) · (QE) Informe de cierre de pruebas |

El catálogo vive en `App.PHASES`.

### Tres formas de pedirlo (para comparar)

| Entrada | Dónde | Selección |
|---|---|---|
| **QE Helper** (`qe-helper.yml`) | Actions → Run workflow | 5 casillas, una por fase |
| **QE Helper · subtareas** (`qe-helper-subtareas.yml`) | Actions → Run workflow | 20 casillas con texto corto (22 de 25 inputs) |
| **Issue Form** (`ISSUE_TEMPLATE/qe-subtareas.yml` + `qe-helper-issue.yml`) | Issues → New issue → *Subtareas QE* | 20 casillas agrupadas por fase, con ayuda; desmarcadas por defecto |

Con el Issue Form, el resultado se publica como comentario en el mismo issue y este se cierra.
Solo lo procesan issues de personas con acceso al repo (`OWNER`, `MEMBER`, `COLLABORATOR`).

- Una sola llamada `POST /rest/api/3/issue/bulk`.
- **No duplica**: si una subtarea ya existe en la historia, la omite.
- El tipo subtarea se detecta del proyecto, sin importar su nombre (`Subtask`, `Subtarea`…).
- Las no marcadas se resumen en una línea ("➖ N subtareas no seleccionadas"); si no marcas ninguna, no se llama a Jira.
- El resultado aparece en la pestaña **Summary** del run.
- Si crea algo, comenta en la historia de Jira qué creó, quién lo pidió y el enlace a la solicitud
  (las subtareas las crea la cuenta de servicio, así queda la trazabilidad).
- El usuario no elige el destino: lo fija la variable de repositorio `QE_TARGET`
  (`mock` por defecto, `jira-cloud` para la instancia real).

## Estructura

```
src/main/java/com/qehelper/App.java         flujo principal
src/main/java/com/qehelper/JiraClient.java  cliente REST de Jira
src/main/java/com/qehelper/IssueForm.java   lee el cuerpo del Issue Form
mock/jira_mock.py                           Jira simulado para probar sin instancia real
.github/workflows/qe-helper.yml             formulario por fase (workflow_dispatch)
.github/workflows/qe-helper-subtareas.yml   formulario por subtarea (opción A)
.github/workflows/qe-helper-issue.yml       procesa el Issue Form y responde en el issue
.github/ISSUE_TEMPLATE/qe-subtareas.yml     Issue Form
.github/actions/qe-subtareas/action.yml     pasos comunes (build, mock, ejecución)
.github/workflows/ci.yml                    e2e contra el mock en cada push
scripts/run-local.sh                        corre el workflow en local
scripts/e2e-mock.sh                         pruebas e2e
.run/                                       configuraciones de IntelliJ (Mock Jira, QE Helper, E2E)
```

El build (`mvn package`) genera `target/qe-helper.jar` con Jackson incluido, y el workflow
lo ejecuta en el runner con `java -jar`.

## Secretos

`JIRA_EMAIL` y `JIRA_API_TOKEN` se guardan en los Secrets del Environment (`mock` o `jira-cloud`).
El Environment `jira-cloud` además necesita la variable `JIRA_BASE_URL`.
La app solo lee variables de entorno: en la corporación esos valores vendrían de HashiCorp Vault.

## Uso

**En GitHub:** Settings → Environments → `mock` → agrega los 2 secrets (valores inventados).
Luego Actions → QE Helper → Run workflow → `issue_key = QA-100`, o Issues → New issue → *Subtareas QE*.
Para apuntar a Jira real: Settings → Variables → `QE_TARGET = jira-cloud`.

**En local** (JDK 21, Maven, Python 3):

```bash
cp .secrets.example .secrets
./scripts/run-local.sh QA-100              # contra el mock
QE_FASE_2=false ./scripts/run-local.sh QA-100      # desmarca la fase Diseño
./scripts/e2e-mock.sh                      # 16 escenarios
```

Para un Jira Cloud real: pon el token en `.secrets`, crea `.vars` con `JIRA_BASE_URL` y usa `--jira-cloud`.

## Versiones

| Versión | Alcance                                                        | Estado |
|---------|----------------------------------------------------------------|--------|
| v1      | 5 subtareas QE en bulk                                         | ✅     |
| v2      | 20 subtareas en 5 fases seleccionables                         | ✅     |
| v3      | Crear defecto vinculado a la historia                          | —      |
| v4      | Crear Test Plan y asociar tests (Xray)                         | —      |
