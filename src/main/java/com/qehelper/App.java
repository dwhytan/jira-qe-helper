package com.qehelper;

import com.qehelper.config.Config;
import com.qehelper.config.Inputs;
import com.qehelper.http.HttpGateway;
import com.qehelper.ops.Context;
import com.qehelper.ops.CreateDefect;
import com.qehelper.ops.CreateSubtasks;
import com.qehelper.ops.CreateTestPlan;
import com.qehelper.ops.Operation;
import com.qehelper.report.Report;

/**
 * Punto de entrada headless. Ya no hay MyData/QEHelperView: el formulario es workflow_dispatch
 * y las credenciales las inyecta el pipeline como variables de entorno.
 */
public final class App {

    public static void main(String[] args) {
        Config cfg = Config.fromEnv();
        Inputs in = Inputs.fromEnv();
        if (args.length > 0 && in.operation().isBlank()) {
            in = new Inputs(args[0].toLowerCase(), in.issueKey(), in.subtasks(), in.summary(), in.description(), in.tests());
        }
        Report report = new Report(in.operation(), cfg.actor(), cfg.runUrl(), cfg.dryRun());
        String fatal = null;

        System.out.println("QE Helper · operación=" + in.operation() + " · " + cfg);
        try {
            Operation op = switch (in.operation()) {
                case "create-subtasks" -> new CreateSubtasks();
                case "create-defect" -> new CreateDefect();
                case "create-test-plan" -> new CreateTestPlan();
                default -> throw new IllegalArgumentException("Operación desconocida: '" + in.operation()
                        + "'. Usa create-subtasks | create-defect | create-test-plan");
            };
            cfg.requireJira();
            Context ctx = new Context(cfg, in, new HttpGateway(cfg.dryRun()), report);
            System.out.println("Autenticado en Jira como: " + ctx.jira.whoAmI());
            op.run(ctx);
        } catch (HttpGateway.ApiException e) {
            fatal = e.getMessage() + (e.status() == 401 || e.status() == 403
                    ? " — revisa que el secreto (email/token) sea válido y tenga permisos en el proyecto" : "");
        } catch (RuntimeException e) {
            fatal = e.getMessage();
        }

        if (fatal != null) System.err.println("ERROR: " + fatal);
        report.publish(cfg.reportPath(), fatal);
        System.exit(fatal != null || report.hasFailures() ? 1 : 0);
    }
}
