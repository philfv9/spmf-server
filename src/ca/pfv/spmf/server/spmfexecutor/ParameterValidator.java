package ca.pfv.spmf.server.spmfexecutor;

import ca.pfv.spmf.algorithmmanager.DescriptionOfAlgorithm;
import ca.pfv.spmf.algorithmmanager.DescriptionOfParameter;

import java.util.List;
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
public final class ParameterValidator {

    public String validate(DescriptionOfAlgorithm desc, List<String> parameters) {
        DescriptionOfParameter[] paramDescs = desc.getParametersDescription();
        int mandatory = desc.getNumberOfMandatoryParameters();
        int total     = (paramDescs == null) ? 0 : paramDescs.length;
        int supplied  = (parameters == null) ? 0 : parameters.size();

        if (supplied < mandatory) {
            return "Algorithm '" + desc.getName() + "' requires at least " +
                   mandatory + " parameter(s) but " + supplied + " were supplied.";
        }
        if (supplied > total) {
            return "Algorithm '" + desc.getName() + "' accepts at most " +
                   total + " parameter(s) but " + supplied + " were supplied.";
        }
        if (paramDescs != null) {
            for (int i = 0; i < supplied; i++) {
                String err = checkType(paramDescs[i], parameters.get(i));
                if (err != null) {
                    return "Parameter " + (i + 1) + " ('" +
                           paramDescs[i].getName() + "'): " + err;
                }
            }
        }
        return null;
    }

    private String checkType(DescriptionOfParameter pd, String value) {
        if (pd.getParameterType() == null) return null;
        String t = pd.getParameterType().getName();
        try {
            if (t.contains("Integer") || t.contains("int"))
                Integer.parseInt(value.trim());
            else if (t.contains("Double") || t.contains("double"))
                Double.parseDouble(value.trim().replace("%", ""));
            else if (t.contains("Float") || t.contains("float"))
                Float.parseFloat(value.trim());
            else if (t.contains("Long") || t.contains("long"))
                Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return "expected " + t + " but got '" + value + "'";
        }
        return null;
    }
}