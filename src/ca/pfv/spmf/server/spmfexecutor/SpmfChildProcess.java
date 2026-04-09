package ca.pfv.spmf.server.spmfexecutor;

import java.nio.file.*;
import java.util.*;

import ca.pfv.spmf.algorithmmanager.AlgorithmManager;
import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
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
 * Child JVM process that runs a single SPMF algorithm.
 *
 * Called by SpmfProcessExecutor.
 * Reads algorithm name, input file, output file, and parameters from args.txt.
 * Runs the algorithm and exits with code 0 on success, non-zero on failure.
 *
 * Usage:
 *   java SpmfChildProcess <args-file>
 *
 * The args-file contains one line per item:
 *   Line 1: algorithm name
 *   Line 2: input file path (or empty if not needed)
 *   Line 3: output file path (or empty if not needed)
 *   Lines 4+: parameter values
 */
public final class SpmfChildProcess {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SpmfChildProcess <args-file>");
            System.exit(1);
        }

        Path argsFile = Paths.get(args[0]);
        List<String> lines = Files.readAllLines(argsFile);

        if (lines.size() < 1) {
            System.err.println("ERROR: args-file is empty");
            System.exit(1);
        }

        String algoName  = lines.get(0);
        String inputFile = (lines.size() > 1 && !lines.get(1).isBlank())
                         ? lines.get(1) : null;
        String outputFile = (lines.size() > 2 && !lines.get(2).isBlank())
                          ? lines.get(2) : null;

        List<String> paramList = lines.subList(3, lines.size());
        String[] parameters = paramList.toArray(new String[0]);

        try {
            AlgorithmManager manager = AlgorithmManager.getInstance();
            DescriptionOfAlgorithm algorithm =
                    manager.getDescriptionOfAlgorithm(algoName);

            if (algorithm == null) {
                System.err.println("ERROR: Algorithm not found: " + algoName);
                System.exit(2);
            }

            System.out.println("[SpmfChild] Running algorithm: " + algoName +
                             " with " + parameters.length + " parameter(s)");

            // This is the SPMF call — same as in SpmfExecutor
            algorithm.runAlgorithm(parameters, inputFile, outputFile);

            System.out.println("[SpmfChild] Algorithm completed successfully");
            System.exit(0);

        } catch (Throwable t) {
            System.err.println("ERROR: Algorithm execution failed");
            t.printStackTrace(System.err);
            System.exit(3);
        }
    }
}