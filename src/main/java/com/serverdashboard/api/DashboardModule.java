package com.serverdashboard.api;

import com.serverdashboard.DashboardPlugin;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;

/**
 * Implement this interface to create a ServerDashboard module.
 *
 * Quick-start:
 *   1. Add ServerDashboard as a compile-only dependency.
 *   2. Create a class that implements DashboardModule.
 *   3. Add META-INF/services/com.serverdashboard.api.DashboardModule
 *      containing your class's fully-qualified name.
 *   4. Drop the compiled JAR into plugins/ServerDashboard/modules/.
 *   5. Restart (or run /dashboard reload-modules).
 */
public interface DashboardModule {

    /** Unique lowercase slug used in URLs and DOM IDs. e.g. "economy" */
    String getId();

    /** Label shown in the sidebar. e.g. "Economy" */
    String getName();

    /**
     * Tabler icon class suffix. e.g. "ti-coin" renders as
     * {@code <i class="ti ti-coin">}. Browse icons at tabler.io/icons.
     */
    String getIcon();

    /**
     * Full HTML placed inside the module's scroll area.
     * May reference /api/module/{id}/... for data.
     */
    String getSectionHtml();

    /**
     * JavaScript executed once after the section HTML is injected into the DOM.
     * Use this to wire up buttons, start polling, etc.
     * The {@code api(method, path, body)} helper and {@code toast(msg, type)}
     * are available from the main dashboard script.
     * Module API paths are relative to /api/module/{id}/:
     *   api('GET', '/module/economy/balance') → /api/module/economy/balance
     */
    default String getInitScript() { return ""; }

    /**
     * Handle an HTTP request routed to /api/module/{id}/{path}.
     * {@code path} is the part after the module id (starts with '/').
     * The request has already been authenticated by the dashboard.
     * Default: responds with 404.
     */
    default void handleRoute(String path, String method, HttpExchange ex) throws Exception {
        byte[] body = "{\"error\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(404, body.length);
        try (var os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    /**
     * Called once after the module JAR is loaded and the plugin is ready.
     * Use this to initialize schedulers, load config, etc.
     */
    default void onLoad(DashboardPlugin plugin) {}

    /** Called before the module is unloaded. Cancel schedulers here. */
    default void onUnload() {}
}
