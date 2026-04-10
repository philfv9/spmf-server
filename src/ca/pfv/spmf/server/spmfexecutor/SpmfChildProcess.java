package ca.pfv.spmf.server.spmfexecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;

/**
 * Entry point for the child JVM process that executes a single SPMF algorithm
 * in isolation.
 * <p>
 * This class is launched as a separate JVM by {@link SpmfProcessExecutor}
 * so that algorithm execution is fully isolated from the server process.
 * Benefits of this isolation include:
 * <ul>
 *   <li>Hard memory limits via {@code -Xmx} on the child JVM.</li>
 *   <li>Ability to forcibly kill a timed-out algorithm without affecting the
 *       server.</li>
 *   <li>Clean separation of algorithm stdout/stderr from server logging.</li>
 * </ul>
 *
 * <b>Invocation:</b>
 * <pre>
 *   java [jvm-flags] ca.pfv.spmf.server.spmfexecutor.SpmfChildProcess &lt;args-file&gt;
 * </pre>
 *
 * <b>Args-file format</b> (one item per line, UTF-8):
 * <pre>
 *   Line 1 : algorithm name (mandatory)
 *   Line 2 : absolute path to the input file, or empty if not required
 *   Line 3 : absolute path to the output file, or empty if not required
 *   Line 4+: parameter values, one per line
 * </pre>
 *
 * <b>Exit codes:</b>
 * <pre>
 *   0 — algorithm completed successfully
 *   1 — wrong number of command-line arguments (usage error)
 *   2 — algorithm name not found in the SPMF catalogue
 *   3 — algorithm threw an exception during execution
 * </pre>
 *
 * All diagnostic output is written to {@code stdout} / {@code stderr}, which
 * {@link SpmfProcessExecutor} redirects to {@code console.log} in the job
 * working directory.
 *
 * @author Philippe Fournier-Viger
 * @see SpmfProcessExecutor
 */
public final class SpmfChildProcess {

    /** Exit code indicating a usage / invocation error. */
    private static final int EXIT_USAGE_ERROR     = 1;

    /** Exit code indicating the requested algorithm was not found. */
    private static final int EXIT_NOT_FOUND       = 2;

    /** Exit code indicating an exception during algorithm execution. */
    private static final int EXIT_EXECUTION_ERROR = 3;

    /** Prevent instantiation — this class is used only as a JVM entry point. */
    private SpmfChildProcess() {}

    // ── Entry point ────────────────────────────────────────────────────────

    /**
     * JVM entry point for the child process.
     * <p>
     * Reads the args-file, resolves the algorithm from the SPMF catalogue,
     * runs it, and exits with the appropriate exit code.
     *
     * @param args command-line arguments; {@code args[0]} must be the path to
     *             the args-file written by {@link SpmfProcessExecutor}
     * @throws Exception propagated only in the unlikely event that JVM
     *                   infrastructure itself fails; all algorithm exceptions
     *                   are caught and converted to exit code 3
     */
    public static void main(String[] args) throws Exception {

        // ── 1. Validate command-line arguments ─────────────────────────────
        if (args.length < 1) {
            System.err.println("Usage: SpmfChildProcess <args-file>");
            System.exit(EXIT_USAGE_ERROR);
        }

        // ── 2. Read the args-file ──────────────────────────────────────────
        Path argsFile = Paths.get(args[0]);
        List<String> lines = Files.readAllLines(argsFile);

        if (lines.isEmpty()) {
            System.err.println("ERROR: args-file is empty: " + argsFile);
            System.exit(EXIT_USAGE_ERROR);
        }

        // Line 1: algorithm name (mandatory)
        String algorithmName = lines.get(0);

        // Line 2: input file path (blank → no input file required)
        String inputFile = (lines.size() > 1 && !lines.get(1).isBlank())
                ? lines.get(1) : null;

        // Line 3: output file path (blank → algorithm writes no output file)
        String outputFile = (lines.size() > 2 && !lines.get(2).isBlank())
                ? lines.get(2) : null;

        // Lines 4+: parameter values, one per line
        List<String> paramList = lines.subList(3, lines.size());
        String[] parameters    = paramList.toArray(new String[0]);

        // ── 3. Resolve the algorithm from the SPMF catalogue ──────────────
        AlgorithmManager manager = AlgorithmManager.getInstance();
        DescriptionOfAlgorithm algorithm =
                manager.getDescriptionOfAlgorithm(algorithmName);

        if (algorithm == null) {
            System.err.println("ERROR: Algorithm not found in catalogue: '"
                    + algorithmName + "'");
            System.exit(EXIT_NOT_FOUND);
        }

        // ── 4. Execute the algorithm ───────────────────────────────────────
        System.out.println("[SpmfChild] Starting algorithm: '" + algorithmName
                + "' with " + parameters.length + " parameter(s)");

        try {
            // Delegates to the same SPMF runAlgorithm() path used by the
            // desktop GUI — parameters, inputFile, and outputFile are passed
            // exactly as the algorithm expects them.
            algorithm.runAlgorithm(parameters, inputFile, outputFile);

            System.out.println("[SpmfChild] Algorithm completed successfully.");
            System.exit(0);

        } catch (Throwable t) {
            // Catch Throwable (not just Exception) to handle OutOfMemoryError
            // and other JVM errors that algorithms might trigger
            System.err.println("ERROR: Algorithm execution failed: "
                    + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(EXIT_EXECUTION_ERROR);
        }
    }
}