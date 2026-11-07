package com.qehelper.ops;

import com.qehelper.config.Config;
import com.qehelper.config.Inputs;
import com.qehelper.http.HttpGateway;
import com.qehelper.jira.JiraClient;
import com.qehelper.report.Report;
import com.qehelper.xray.XrayClient;

/** Dependencias compartidas por las operaciones. */
public final class Context {
    public final Config cfg;
    public final Inputs in;
    public final JiraClient jira;
    public final Report report;
    private final HttpGateway http;
    private XrayClient xray;

    public Context(Config cfg, Inputs in, HttpGateway http, Report report) {
        this.cfg = cfg;
        this.in = in;
        this.http = http;
        this.report = report;
        this.jira = new JiraClient(cfg, http);
    }

    public XrayClient xray() {
        if (xray == null) {
            cfg.requireXray();
            xray = new XrayClient(cfg, http);
        }
        return xray;
    }

    /** Trazabilidad: el issue lo crea la cuenta de servicio, pero queda registrado quién lo pidió. */
    public String footer() {
        return "Generado por QE Helper vía GitHub Actions · solicitado por @" + cfg.actor()
                + (cfg.runUrl().isBlank() ? "" : " · " + cfg.runUrl());
    }

    public Report.Status createdStatus() {
        return cfg.dryRun() ? Report.Status.DRY_RUN : Report.Status.CREATED;
    }
}
