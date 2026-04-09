package ca.pfv.spmf.server.spmfexecutor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.job.Job;
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
/**
 * Executes SPMF algorithms in a separate child JVM process.
 *
 * Captures stdout/stderr to console.log so clients can later request it via:
 *   GET /api/jobs/{id}/console
 */
public final class SpmfProcessExecutor {

    private static final Logger log = ServerLogger.get(
            SpmfProcessExecutor.class);

    private final ServerConfig config;
    private final SpmfCatalogue catalogue;

    private static final String CHILD_JVM_HEAP = getChildHeap();

    private static String getChildHeap() {
        String env = System.getenv("SPMF_CHILD_XMX");
        return (env != null && !env.isBlank()) ? env : "1g";
    }

    private static final long GRACE_PERIOD_MS = 2_000L;

    public SpmfProcessExecutor(ServerConfig config, SpmfCatalogue catalogue) {
        this.config    = config;
        this.catalogue = catalogue;
    }

    /**
     * Execute the algorithm in a child JVM process with hard timeout enforcement.
     * Captures all console output to console.log in the job directory.
     *
     * @param job the job to execute
     * @return the output file content
     * @throws Exception on I/O, process, or timeout error
     */
    public String execute(Job job) throws Exception {

        // ── 1. Create per-job working directory ────────────────────────────
        Path jobDir = Paths.get(config.getWorkDir(), job.getJobIdString());
        Files.createDirectories(jobDir);
        job.setWorkDirPath(jobDir.toString());

        Path inputPath    = jobDir.resolve("input.txt");
        Path outputPath   = jobDir.resolve("output.txt");
        Path argsPath     = jobDir.resolve("args.txt");
        Path consolePath  = jobDir.resolve("console.log");  // ← NEW

        // ── 2. Write input data supplied by the client ─────────────────────
        Files.writeString(inputPath, job.getInputData());
        log.fine("Job " + job.getJobIdString() + " — input written to: "
                + inputPath);

        // ── 3. Resolve algorithm descriptor ────────────────────────────────
        ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm algorithm =
                ca.pfv.spmf.algorithmmanager.AlgorithmManager.getInstance()
                        .getDescriptionOfAlgorithm(job.getAlgorithmName());

        if (algorithm == null) {
            throw new IllegalArgumentException(
                "No algorithm named '" + job.getAlgorithmName() +
                "' found in SPMF.");
        }

        // ── 4. Determine actual input / output paths ───────────────────────
        String inputFile  = (algorithm.getInputFileTypes() == null)
                            ? null : inputPath.toAbsolutePath().toString();
        String outputFile = (algorithm.getOutputFileTypes() == null)
                            ? null : outputPath.toAbsolutePath().toString();

        // ── 5. Validate and prepare parameters ─────────────────────────────
        List<String> paramList  = job.getParameters();
        String[]     parameters = paramList.toArray(new String[0]);

        if (algorithm.getParametersDescription() != null) {
            int numberOfParameter = algorithm.getParametersDescription().length;

            for (int i = 0; i < numberOfParameter; i++) {
                ca.pfv.spmf.algorithmmanager.DescriptionOfParameter paramDesc =
                        algorithm.getParametersDescription()[i];

                if (i == parameters.length) {
                    if (!paramDesc.isOptional) {
                        throw new IllegalArgumentException(
                            "The " + ordinal(i + 1) +
                            " parameter '" + paramDesc.name +
                            "' is mandatory but was not supplied.");
                    }
                    break;
                }

                String valueI = parameters[i];

                if (valueI == null || valueI.isEmpty()) {
                    if (!paramDesc.isOptional) {
                        throw new IllegalArgumentException(
                            "The " + ordinal(i + 1) +
                            " parameter '" + paramDesc.name +
                            "' is mandatory and cannot be empty.");
                    }
                } else {
                    boolean ok = algorithm.isParameterOfCorrectType(valueI, i);
                    if (!ok) {
                        throw new IllegalArgumentException(
                            "The " + ordinal(i + 1) +
                            " parameter '" + paramDesc.name +
                            "' has incorrect type: '" + valueI + "'.");
                    }
                }
            }
        }

        // Write parameters to file
        List<String> argsLines = new ArrayList<>();
        argsLines.add(job.getAlgorithmName());
        if (inputFile != null) argsLines.add(inputFile);
        if (outputFile != null) argsLines.add(outputFile);
        for (String p : parameters) {
            argsLines.add(p);
        }
        Files.write(argsPath, argsLines);

        // ── 6. Spawn child JVM process (with console capture) ──────────────
        log.info("Job " + job.getJobIdString() +
                 " — spawning child JVM for '" + job.getAlgorithmName() +
                 "' (timeout: " + config.getJobTtlMinutes() + " min)");

        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-Xmx" + CHILD_JVM_HEAP,
                "-cp", System.getProperty("java.class.path"),
                "ca.pfv.spmf.server.spmfexecutor.SpmfChildProcess",
                argsPath.toAbsolutePath().toString()
        );

        pb.directory(jobDir.toFile());
        // ── FIX: redirect stdout/stderr to console.log ──────────────────────
        pb.redirectOutput(consolePath.toFile());
        pb.redirectError(ProcessBuilder.Redirect.appendTo(consolePath.toFile()));

        Process process = pb.start();
        long timeoutMs = (long) config.getJobTtlMinutes() * 60_000L;

        // ── 7. Wait for process with HARD timeout enforcement ─────────────
        boolean finished = waitForProcessWithTimeout(process, timeoutMs, job);

        if (!finished) {
            // Append timeout message to console.log
            try (FileWriter fw = new FileWriter(consolePath.toFile(), true)) {
                fw.write("\n[TIMEOUT] Algorithm exceeded " +
                        config.getJobTtlMinutes() + " minute(s) and was killed\n");
            }
            throw new RuntimeException(
                "Algorithm timed out after " + config.getJobTtlMinutes() +
                " minute(s). Process was forcibly killed.");
        }

        // ── 8. Check exit code ─────────────────────────────────────────────
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            // Append exit code to console.log
            try (FileWriter fw = new FileWriter(consolePath.toFile(), true)) {
                fw.write("\n[EXIT CODE] " + exitCode + "\n");
            }
            if (exitCode == 143 || exitCode == 137 || exitCode == -9) {
                throw new RuntimeException(
                    "Algorithm process was killed due to timeout or signal");
            }
            throw new RuntimeException(
                "Algorithm process exited with code " + exitCode +
                ". Check server logs and console output.");
        }

        // ── 9. Read and return output ──────────────────────────────────────
        if (outputFile == null) {
            log.info("Job " + job.getJobIdString() +
                     " — algorithm produces no output file.");
            return "";
        }

        if (!Files.exists(outputPath)) {
            throw new RuntimeException(
                "Algorithm did not produce output file at: " + outputPath);
        }

        String result = Files.readString(outputPath);
        log.fine("Job " + job.getJobIdString() +
                 " — output read: " + result.length() + " chars.");
        return result;
    }

    // ── Timeout enforcer ───────────────────────────────────────────────────

    private boolean waitForProcessWithTimeout(Process process, long timeoutMs,
                                              Job job) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        try {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.warning("Job " + job.getJobIdString() +
                   " exceeded timeout of " + (timeoutMs / 1000) +
                   "s — attempting graceful shutdown with SIGTERM");

        process.destroy();

        try {
            if (process.waitFor(GRACE_PERIOD_MS, TimeUnit.MILLISECONDS)) {
                log.info("Job " + job.getJobIdString() +
                         " terminated gracefully after SIGTERM");
                return true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.severe("Job " + job.getJobIdString() +
                  " still alive after grace period — " +
                  "forcibly killing process (SIGKILL)");

        process.destroyForcibly();

        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                log.warning("Job " + job.getJobIdString() +
                           " killed with SIGKILL");
                return true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.severe("Job " + job.getJobIdString() +
                  " STILL ALIVE after SIGKILL");
        return false;
    }

    private static String ordinal(int i) {
        String[] suffixes = {"th","st","nd","rd","th","th","th","th","th","th"};
        switch (i % 100) {
            case 11: case 12: case 13: return i + "th";
            default: return i + suffixes[i % 10];
        }
    }
}