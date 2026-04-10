package dev.jka.bommigrate.web;

import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.Desktop;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Programmatically starts the Spring Boot web server for discovery review.
 * Called from the CLI when {@code --web} is passed. Seeds the session with
 * the pre-computed {@link DiscoveryReport} and initial modules.
 */
public final class WebServerLauncher {

    /**
     * Starts the embedded web server, seeds the session, and (if a desktop is
     * available) opens the browser.
     *
     * @param report          the discovery report to review
     * @param initialModules  initial module definitions
     * @param requestedPort   preferred server port (will auto-increment if taken)
     * @param outputDir       directory to write generated BOM files into
     * @param parentGroupId   parent POM groupId
     * @param parentArtifactId parent POM artifactId
     * @param parentVersion   parent POM version
     * @return the running Spring {@link ConfigurableApplicationContext}
     */
    public static ConfigurableApplicationContext start(
            DiscoveryReport report,
            ScanMetadata scanMetadata,
            List<BomModule> initialModules,
            int requestedPort,
            Path outputDir,
            String parentGroupId,
            String parentArtifactId,
            String parentVersion) {

        int port = findAvailablePort(requestedPort);

        ConfigurableApplicationContext context = new SpringApplicationBuilder(BomMigrateWebApplication.class)
                .properties("server.port=" + port)
                .run();

        DiscoverySessionService session = context.getBean(DiscoverySessionService.class);
        session.setReport(report);
        session.setScanMetadata(scanMetadata);
        session.setModules(initialModules);
        session.setOutputDir(outputDir);
        session.setParentCoordinates(parentGroupId, parentArtifactId, parentVersion);

        String url = "http://localhost:" + port;
        System.out.println("Web UI started at " + url);
        openBrowser(url);
        return context;
    }

    private static int findAvailablePort(int requested) {
        int port = requested;
        for (int i = 0; i < 20; i++) {
            try (ServerSocket s = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                port++;
            }
        }
        System.err.println("[WARN] Could not find free port near " + requested + " — using requested port anyway");
        return requested;
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            // Headless environment or browser not available — that's fine
        }
    }
}
