package ca.pfv.spmf.server.spmfexecutor;

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

import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.algorithmmanager.DescriptionOfParameter;

/**
 * Validates that the parameters supplied in an API request are compatible with
 * the formal parameter specification of an SPMF algorithm.
 * <p>
 * Three distinct checks are performed in order:
 * <ol>
 *   <li><b>Minimum arity</b> — the number of supplied parameters must be at
 *       least equal to the algorithm's mandatory parameter count.</li>
 *   <li><b>Maximum arity</b> — the number of supplied parameters must not
 *       exceed the total number of parameters the algorithm declares
 *       (mandatory + optional).</li>
 *   <li><b>Type compatibility</b> — each supplied value must be parseable as
 *       the declared parameter type (e.g. {@code Integer}, {@code Double}).</li>
 * </ol>
 * All validation errors are returned as human-readable strings rather than
 * thrown exceptions so that callers can forward them directly to HTTP clients.
 * A {@code null} return value means the parameters are valid.
 *
 * @author Philippe Fournier-Viger
 * @see SpmfCatalogue
 * @see SpmfProcessExecutor
 */
public final class ParameterValidator {

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Validate the supplied parameter list against the algorithm descriptor.
     * <p>
     * Returns {@code null} when all checks pass, or a non-null human-readable
     * error message describing the first problem encountered.
     *
     * @param desc       the algorithm descriptor obtained from
     *                   {@link SpmfCatalogue}; must not be {@code null}
     * @param parameters the list of raw string parameter values supplied by
     *                   the API client; may be {@code null} or empty
     * @return {@code null} if valid, or an error message string otherwise
     */
    public String validate(DescriptionOfAlgorithm desc, List<String> parameters) {

        DescriptionOfParameter[] paramDescs = desc.getParametersDescription();

        // Total number of parameters the algorithm declares (mandatory + optional)
        int total    = (paramDescs == null) ? 0 : paramDescs.length;

        // Number of mandatory (non-optional) parameters
        int mandatory = desc.getNumberOfMandatoryParameters();

        // Number of values actually supplied by the client
        int supplied  = (parameters == null) ? 0 : parameters.size();

        // ── Check 1: too few parameters ────────────────────────────────────
        if (supplied < mandatory) {
            return "Algorithm '" + desc.getName()
                    + "' requires at least " + mandatory
                    + " parameter(s) but " + supplied + " were supplied.";
        }

        // ── Check 2: too many parameters ───────────────────────────────────
        if (supplied > total) {
            return "Algorithm '" + desc.getName()
                    + "' accepts at most " + total
                    + " parameter(s) but " + supplied + " were supplied.";
        }

        // ── Check 3: type compatibility for each supplied value ────────────
        if (paramDescs != null) {
            for (int i = 0; i < supplied; i++) {
                String typeError = checkType(paramDescs[i], parameters.get(i));
                if (typeError != null) {
                    return "Parameter " + (i + 1)
                            + " ('" + paramDescs[i].getName() + "'): "
                            + typeError;
                }
            }
        }

        // All checks passed
        return null;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Check whether {@code value} is parseable as the type declared by
     * {@code pd}.
     * <p>
     * Type detection is done by inspecting the simple name of the declared
     * parameter class (e.g. {@code "Integer"}, {@code "Double"}).  String and
     * boolean parameters are accepted without further parsing because any
     * non-null string is a valid {@code String} and boolean handling is done
     * by the algorithm itself.
     *
     * @param pd    the formal parameter descriptor
     * @param value the raw string value supplied by the client
     * @return {@code null} if the value is type-compatible, or an error message
     */
    private String checkType(DescriptionOfParameter pd, String value) {

        // No declared type — skip type check
        if (pd.getParameterType() == null) {
            return null;
        }

        String typeName = pd.getParameterType().getName();

        try {
            if (typeName.contains("Integer") || typeName.contains("int")) {
                Integer.parseInt(value.trim());

            } else if (typeName.contains("Double") || typeName.contains("double")) {
                // Strip a trailing '%' so percentage-style inputs are accepted
                Double.parseDouble(value.trim().replace("%", ""));

            } else if (typeName.contains("Float") || typeName.contains("float")) {
                Float.parseFloat(value.trim());

            } else if (typeName.contains("Long") || typeName.contains("long")) {
                Long.parseLong(value.trim());
            }
            // String and boolean types need no parse check

        } catch (NumberFormatException e) {
            return "expected " + typeName + " but got '" + value + "'.";
        }

        return null; // value is compatible with the declared type
    }
}