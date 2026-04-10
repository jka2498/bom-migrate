package dev.jka.bommigrate.core.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable map of resolved BOM-managed dependencies, keyed by
 * "groupId:artifactId:type:classifier".
 */
public final class DependencyManagementMap {

    private final Map<String, ResolvedDependency> entries;

    public DependencyManagementMap(Collection<ResolvedDependency> deps) {
        var map = new LinkedHashMap<String, ResolvedDependency>();
        for (ResolvedDependency dep : deps) {
            // First-wins for duplicates, matching Maven's behavior
            map.putIfAbsent(dep.key(), dep);
        }
        this.entries = Collections.unmodifiableMap(map);
    }

    /**
     * Lookup by exact key (groupId:artifactId:type:classifier).
     */
    public Optional<ResolvedDependency> lookup(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Lookup by groupId + artifactId, assuming type=jar and classifier="".
     */
    public Optional<ResolvedDependency> lookup(String groupId, String artifactId) {
        return lookup(groupId + ":" + artifactId + ":jar:");
    }

    /**
     * Returns true if this map manages the given dependency (by key match).
     */
    public boolean manages(ResolvedDependency dep) {
        return entries.containsKey(dep.key());
    }

    public int size() {
        return entries.size();
    }

    public Collection<ResolvedDependency> all() {
        return entries.values();
    }
}
