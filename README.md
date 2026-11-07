# Jira QE Helper — headless sobre GitHub Actions

Evolución de la app Swing de QE Helper. Se eliminó la capa **View** (MyData/QEHelperView):
el formulario ahora es el **`workflow_dispatch`** de GitHub Actions, y las credenciales
ya no viven en la máquina del usuario: las **inyecta el pipeline** desde un gestor de secretos.

| Antes (Swing, local)                     | Ahora (Actions, headless)                                   |
|------------------------------------------|-------------------------------------------------------------|
| `MyData.java` — login con email + token   | Secrets del environment (simulación) / **HashiCorp Vault**  |
| `QEHelperView.java` — pestañas            | Inputs del workflow (`operation`, `issue_key`, …)           |
| `JiraController.java`                    | `jira/JiraClient.java` + `xray/XrayClient.java`             |
| `UserSession.java`                       | `config/Config.java` (variables de entorno, nunca a disco)  |
| JOptionPane con resultados               | Job Summary + output `created_keys` + artifact `qe-report.json` |
| Fat jar distribuido a cada usuario        | Fat jar construido en el runner; nada se distribuye         |

## Operaciones

| `operation`        | Inputs                                         | Qué hace |
|--------------------|------------------------------------------------|----------|
| `create-subtasks`  | `issue_key`, `subtasks` (`ALL` o códigos)       | Crea en bulk (lotes de 50) las subtareas QA del [catálogo](src/main/resources/subtasks-catalog.json). **Idempotente**: si ya existe una subtarea con el mismo resumen, la omite. |
| `create-defect`    | `issue_key`, `summary`, `description`          | Crea un Bug en el proyecto del issue y lo vincula (`Relates`). |
| `create-test-plan` | `issue_key`, `summary`, `description`, `tests` | Crea un Test Plan, lo vincula a la historia y le asocia tests vía **Xray GraphQL** (`tests` = keys o `jql:<consulta>`). |

Todas aceptan `dry_run`: lee de Jira de verdad pero solo **imprime** los payloads de escritura.

Cada issue creado lleva la etiqueta `qe-helper` y un pie con **quién lo pidió** (`github.actor`)
y el link al run: al usar una cuenta de servicio centralizada, el reporter en Jira ya no es la persona,
así que la trazabilidad queda en el issue, en el Job Summary y en el artifact.

## Arquitectura

```
 Usuario QA ──► Actions "Run workflow" (formulario)
                       │  inputs → env QE_*   (nunca interpolados en shell)
                       ▼
               ┌─────────────────────┐   secrets → env     ┌──────────────────────┐
               │ runner ubuntu        │◄────────────────────│ GitHub Secrets (sim) │
               │  java -jar qe-helper │                     │ HashiCorp Vault (corp)│
               └──────────┬──────────┘                     └──────────────────────┘
                          │ REST v3 / GraphQL
             ┌────────────┴─────────────┐
             ▼                          ▼
     Jira Cloud (o mock)         Xray Cloud (o mock)
```

La app **solo lee variables de entorno**, así que cambiar de GitHub Secrets a Vault es
cambiar un step del YAML, no código.

## Puesta en marcha (simulación)

1. Crear el repo y subir el código:
   ```bash
   git remote add origin git@github.com:<tu-usuario>/jira-qe-helper.git
   git push -u origin main
   ```
2. **Settings → Environments → New environment `mock`** y añadir estos *environment secrets*
   (valores inventados; el mock exige que coincidan, simulando a Jira validando el token):
   `JIRA_EMAIL`, `JIRA_API_TOKEN`, `XRAY_CLIENT_ID`, `XRAY_CLIENT_SECRET`.
3. **Actions → QE Helper → Run workflow** con `target = mock`, `issue_key = QA-100`.
   Issues semilla del mock: `QA-100`, `QA-101` (Story), `QA-201..QA-205` (Test).

### Contra un Jira Cloud real (opcional)

Puedes usar un sitio gratuito de Atlassian con Xray en trial:

1. Environment **`jira-cloud`** con secrets `JIRA_EMAIL`, `JIRA_API_TOKEN`
   ([crear token](https://id.atlassian.com/manage-profile/security/api-tokens)),
   `XRAY_CLIENT_ID`, `XRAY_CLIENT_SECRET` (Xray → Settings → API Keys).
2. *Environment variables*: `JIRA_BASE_URL` (`https://<sitio>.atlassian.net`) y, si tu Jira está
   en español u otra configuración, `JIRA_SUBTASK_TYPE` (p. ej. `Subtarea`), `JIRA_BUG_TYPE`,
   `XRAY_TEST_PLAN_TYPE`, `JIRA_LINK_TYPE`.
3. Recomendado: **Required reviewers** y restricción a la rama `main` en ese environment.

## Paso a la corporación: HashiCorp Vault

En [`qe-helper.yml`](.github/workflows/qe-helper.yml) está comentado el step con
`hashicorp/vault-action`. Para activarlo:

1. Descomentar `id-token: write` y el step "Obtener secretos desde HashiCorp Vault".
2. Quitar las líneas `secrets.*` del step "Ejecutar QE Helper" (vault-action ya exporta las variables).
3. En Vault (lo configura el equipo de plataforma): auth method **JWT/OIDC** con
   `bound_issuer = https://token.actions.githubusercontent.com`, un role `qe-helper` con
   `bound_claims` al repo/rama/environment, y una policy de solo lectura sobre
   `secret/data/qa/jira-svc` y `secret/data/qa/xray-svc`.

Así no hay ningún token estático en GitHub: el runner obtiene un JWT efímero, Vault lo valida y
entrega los secretos solo para ese job.

## Desarrollo local

```bash
mvn package                 # fat jar en target/qe-helper.jar (Java 21)
./scripts/e2e-mock.sh       # 11 escenarios contra el mock (también corre en CI)
```

Ejecutar manualmente contra el mock:

```bash
export MOCK_JIRA_EMAIL=me@x.com MOCK_JIRA_TOKEN=t MOCK_XRAY_CLIENT_ID=i MOCK_XRAY_CLIENT_SECRET=s
python3 mock/jira_xray_mock.py &
JIRA_BASE_URL=http://127.0.0.1:8089 XRAY_BASE_URL=http://127.0.0.1:8089 \
JIRA_EMAIL=me@x.com JIRA_API_TOKEN=t XRAY_CLIENT_ID=i XRAY_CLIENT_SECRET=s \
QE_OPERATION=create-subtasks QE_ISSUE_KEY=QA-100 QE_SUBTASKS=DISENO,FUNCIONAL \
java -jar target/qe-helper.jar
```

## Seguridad

- Inputs pasan por `env:` y se validan (formato de keys) → sin inyección de comandos ni de JQL vía keys.
- Secretos enmascarados por Actions; `Config.toString()` nunca los imprime; no se escriben a disco.
- `concurrency` por `issue_key` evita ejecuciones duplicadas en paralelo.
- Quien tenga permiso *write* en el repo puede ejecutar el workflow: controla el acceso con
  los permisos del repo y los *Required reviewers* del environment `jira-cloud`.
