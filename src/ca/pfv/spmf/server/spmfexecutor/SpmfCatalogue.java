package ca.pfv.spmf.server.spmfexecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 *
 * This file is part of the SPMF SERVER
 * (http://www.philippe-fournier-viger.com/spmf).
 *
 * SPMF is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SPMF is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF. If not, see <http://www.gnu.org/licenses/>.
 */

import ca.pfv.spmf.algorithmmanager.AlgorithmManager;
import ca.pfv.spmf.algorithmmanager.AlgorithmType;
import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.server.util.ServerLogger;

/**
 * Thread-safe, lazily-initialised singleton catalogue of all SPMF algorithms
 * that are safe to expose via the REST API.
 * <p>
 * On first access the catalogue queries the SPMF {@link AlgorithmManager} for
 * the full list of registered algorithms and filters out any algorithm type
 * that cannot be executed in a headless, non-interactive environment (see
 * {@link #EXCLUDED_TYPES}).  The resulting collection is stored as two
 * immutable views:
 * <ul>
 *   <li>an ordered {@link List} of algorithm names for catalogue enumeration,
 *       and</li>
 *   <li>a {@link Map} keyed by name for O(1) lookup.</li>
 * </ul>
 * Both views are unmodifiable; attempts to mutate them will throw
 * {@link UnsupportedOperationException}.
 * <p>
 * Double-checked locking with a {@code volatile} instance field ensures that
 * only one catalogue is ever constructed, even under concurrent access.
 *
 * @author Philippe Fournier-Viger
 * @see AlgorithmManager
 * @see DescriptionOfAlgorithm
 */
public final class SpmfCatalogue {

    /** Logger for catalogue initialisation events. */
    private static final Logger log = ServerLogger.get(SpmfCatalogue.class);

    /**
     * The single shared instance, created on the first call to
     * {@link #getInstance()}.
     */
    private static volatile SpmfCatalogue instance;

    /**
     * Algorithm types that must <em>not</em> be exposed via the REST API
     * because they require a graphical display or produce no serialisable
     * output that a remote client can consume.
     * <p>
     * <ul>
     *   <li>{@link AlgorithmType#DATA_VIEWER} — visualisation tools that
     *       open Swing/JavaFX windows.</li>
     *   <li>{@link AlgorithmType#OTHER_GUI_TOOL} — interactive GUI utilities
     *       that block waiting for user input.</li>
     *   <li>{@link AlgorithmType#EXPERIMENT_TOOL} — batch experiment runners
     *       that manage their own I/O and are not suitable for single-job
     *       REST execution.</li>
     * </ul>
     */
    private static final Set<AlgorithmType> EXCLUDED_TYPES =
            Collections.unmodifiableSet(EnumSet.of(
                    AlgorithmType.DATA_VIEWER,
                    AlgorithmType.OTHER_GUI_TOOL,
                    AlgorithmType.EXPERIMENT_TOOL
            ));

    // ── Immutable catalogue views ──────────────────────────────────────────

    /**
     * Ordered list of algorithm names that passed the filter.
     * Insertion order matches the order returned by {@link AlgorithmManager}.
     */
    private final List<String> algorithmNames;

    /**
     * Map from algorithm name to its {@link DescriptionOfAlgorithm}, using
     * the same insertion order as {@link #algorithmNames}.
     */
    private final Map<String, DescriptionOfAlgorithm> descriptorsByName;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Private constructor — called exactly once by {@link #getInstance()}.
     * Queries {@link AlgorithmManager}, applies the exclusion filter, and
     * builds the two immutable catalogue views.
     *
     * @throws Exception if {@link AlgorithmManager} cannot be initialised
     */
    private SpmfCatalogue() throws Exception {

        AlgorithmManager mgr = AlgorithmManager.getInstance();

        // Retrieve all algorithm names registered in SPMF
        List<String> raw = mgr.getListOfAlgorithmsAsString(
                true, true, true, true, true);

        List<String> names               = new ArrayList<>();
        Map<String, DescriptionOfAlgorithm> map = new LinkedHashMap<>();

        for (String entry : raw) {

            // Skip null entries and section-header separators (e.g. " --- Frequent...")
            if (entry == null || entry.startsWith(" --- ")) {
                continue;
            }

            DescriptionOfAlgorithm desc = mgr.getDescriptionOfAlgorithm(entry);
            if (desc == null) {
                continue; // no descriptor registered — skip silently
            }

            // Filter out GUI-only and experiment-tool algorithms
            AlgorithmType type = desc.getAlgorithmType();
            if (type == null || EXCLUDED_TYPES.contains(type)) {
                log.fine("Excluding algorithm '" + entry
                        + "' (type: " + type + ")");
                continue;
            }

            names.add(entry);
            map.put(entry, desc);
        }

        // Seal both collections — no mutations allowed after construction
        this.algorithmNames    = Collections.unmodifiableList(names);
        this.descriptorsByName = Collections.unmodifiableMap(map);

        log.info("SpmfCatalogue initialised: "
                + names.size() + " algorithm(s) available.");
    }

    // ── Singleton accessor ─────────────────────────────────────────────────

    /**
     * Return the shared {@link SpmfCatalogue} instance, creating it on the
     * first call (double-checked locking).
     *
     * @return the singleton catalogue
     * @throws Exception if the underlying {@link AlgorithmManager} cannot be
     *                   initialised on the first call
     */
    public static SpmfCatalogue getInstance() throws Exception {
        if (instance == null) {
            synchronized (SpmfCatalogue.class) {
                if (instance == null) {
                    instance = new SpmfCatalogue();
                }
            }
        }
        return instance;
    }

    // ── Query methods ──────────────────────────────────────────────────────

    /**
     * Return an unmodifiable, ordered list of all algorithm names in the
     * catalogue.
     *
     * @return list of algorithm name strings; never {@code null}
     */
    public List<String> getAlgorithmNames() {
        return algorithmNames;
    }

    /**
     * Look up the {@link DescriptionOfAlgorithm} for a given algorithm name.
     *
     * @param name the algorithm name (case-sensitive)
     * @return the descriptor, or {@code null} if the name is not in the
     *         catalogue
     */
    public DescriptionOfAlgorithm getDescriptor(String name) {
        return descriptorsByName.get(name);
    }

    /**
     * Return an unmodifiable view of all algorithm descriptors in the
     * catalogue, in insertion order.
     *
     * @return collection of {@link DescriptionOfAlgorithm} objects;
     *         never {@code null}
     */
    public Collection<DescriptionOfAlgorithm> getAllDescriptors() {
        return descriptorsByName.values();
    }

    /**
     * Return {@code true} if an algorithm with the given name is present in
     * the catalogue.
     *
     * @param name the algorithm name to check (case-sensitive)
     * @return {@code true} if the algorithm is known and not excluded
     */
    public boolean contains(String name) {
        return descriptorsByName.containsKey(name);
    }

    /**
     * Return the total number of algorithms available in the catalogue.
     *
     * @return number of included algorithms
     */
    public int size() {
        return algorithmNames.size();
    }
}