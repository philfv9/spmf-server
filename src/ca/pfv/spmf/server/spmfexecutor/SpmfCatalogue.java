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

import ca.pfv.spmf.algorithmmanager.AlgorithmManager;
import ca.pfv.spmf.algorithmmanager.AlgorithmType;
import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.server.util.ServerLogger;
/*
 *  Copyright (c) 2026 Philippe Fournier-Viger
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF.  If not, see <http://www.gnu.org/licenses/>.
 */
public final class SpmfCatalogue {

    private static final Logger log = ServerLogger.get(SpmfCatalogue.class);
    private static volatile SpmfCatalogue instance;

    // ── Algorithm types that must NOT be exposed via the REST API ──────────
    // These require a graphical display or produce no serialisable output.
    private static final Set<AlgorithmType> EXCLUDED_TYPES =
            Collections.unmodifiableSet(EnumSet.of(
                    AlgorithmType.DATA_VIEWER,     // visualisation tools — need a screen
                    AlgorithmType.OTHER_GUI_TOOL,  // interactive Swing/JavaFX utilities
                    AlgorithmType.EXPERIMENT_TOOL  // batch-experiment runners
            ));

    private final List<String>                        algorithmNames;
    private final Map<String, DescriptionOfAlgorithm> descriptorsByName;

    private SpmfCatalogue() throws Exception {
        AlgorithmManager mgr = AlgorithmManager.getInstance();
        List<String> raw = mgr.getListOfAlgorithmsAsString(true, true, true, true, true);

        List<String> names = new ArrayList<>();
        Map<String, DescriptionOfAlgorithm> map = new LinkedHashMap<>();

        for (String entry : raw) {
            if (entry == null || entry.startsWith(" --- ")) continue;
            DescriptionOfAlgorithm desc = mgr.getDescriptionOfAlgorithm(entry);
            if (desc == null) continue;

            // ── FILTER: skip GUI-only and experiment algorithms ────────────
            AlgorithmType type = desc.getAlgorithmType();
            if (type == null || EXCLUDED_TYPES.contains(type)) continue;
            // ─────────────────────────────────────────────────────────────

            names.add(entry);
            map.put(entry, desc);
        }

        this.algorithmNames    = Collections.unmodifiableList(names);
        this.descriptorsByName = Collections.unmodifiableMap(map);
        log.info("SpmfCatalogue: " + names.size() + " algorithms loaded.");
    }

    public static SpmfCatalogue getInstance() throws Exception {
        if (instance == null) {
            synchronized (SpmfCatalogue.class) {
                if (instance == null) instance = new SpmfCatalogue();
            }
        }
        return instance;
    }

    public List<String>                       getAlgorithmNames()    { return algorithmNames;            }
    public DescriptionOfAlgorithm             getDescriptor(String n) { return descriptorsByName.get(n);  }
    public Collection<DescriptionOfAlgorithm> getAllDescriptors()     { return descriptorsByName.values(); }
    public boolean                            contains(String n)      { return descriptorsByName.containsKey(n); }
    public int                                size()                  { return algorithmNames.size();      }
}