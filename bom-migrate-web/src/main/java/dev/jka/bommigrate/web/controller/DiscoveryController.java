package dev.jka.bommigrate.web.controller;

import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for the discovery review flow. Holds no state itself —
 * delegates to {@link DiscoverySessionService}.
 */
@RestController
@RequestMapping("/api")
public class DiscoveryController {

    private final DiscoverySessionService session;

    public DiscoveryController(DiscoverySessionService session) {
        this.session = session;
    }

    @GetMapping("/discovery")
    public ResponseEntity<DiscoveryReport> getDiscovery() {
        DiscoveryReport report = session.getReport();
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/modules")
    public ResponseEntity<List<BomModule>> getModules() {
        return ResponseEntity.ok(session.getModules());
    }

    @PostMapping("/modules")
    public ResponseEntity<List<BomModule>> setModules(@RequestBody List<BomModule> modules) {
        session.setModules(modules);
        return ResponseEntity.ok(session.getModules());
    }

    public record ConfirmRequest(List<BomModuleAssignment> assignments) {}

    public record ConfirmResponse(int accepted, int modules) {}

    @PostMapping("/discovery/confirm")
    public ResponseEntity<ConfirmResponse> confirm(@RequestBody ConfirmRequest request) {
        session.setAssignments(request.assignments());
        return ResponseEntity.ok(new ConfirmResponse(
                request.assignments().size(),
                session.getModules().size()
        ));
    }

    /** Convenience view of the total candidates known to the server. */
    @GetMapping("/discovery/candidates")
    public ResponseEntity<List<BomCandidate>> getCandidates() {
        DiscoveryReport report = session.getReport();
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report.candidates());
    }
}
