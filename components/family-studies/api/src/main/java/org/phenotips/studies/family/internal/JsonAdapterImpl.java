/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.phenotips.studies.family.internal;

import org.phenotips.configuration.RecordConfigurationManager;
import org.phenotips.ontology.OntologyService;
import org.phenotips.ontology.OntologyTerm;
import org.phenotips.studies.family.JsonAdapter;

import org.xwiki.component.annotation.Component;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Converts the JSON generated by the pedigree into the default format accepted by PhenoTips. {@link
 * org.phenotips.data.PatientDataController}.
 */
@Component
public class JsonAdapterImpl implements JsonAdapter
{
    @Inject
    Logger logger;

    @Inject
    private RecordConfigurationManager configurationManager;

    @Inject
    @Named("hpo")
    private OntologyService hpoService;

    @Inject
    @Named("omim")
    private OntologyService omimService;

    @Override
    public List<JSONObject> convert(JSONObject toConvert)
    {
        if (toConvert.containsKey("JSON_version") &&
            !StringUtils.equalsIgnoreCase(toConvert.getString("JSON_version"), "1.0"))
        {
            logger.warn("The version of the pedigree JSON differs from the expected.");
        }

        DateFormat dateFormat =
            new SimpleDateFormat(this.configurationManager.getActiveConfiguration().getISODateFormat());
        List<JSONObject> convertedPatients = new LinkedList<>();
        List<JSONObject> patientJson = PedigreeUtils.extractPatientJSONPropertiesFromPedigree(toConvert);

        ServicesHolder holder = new ServicesHolder();
        holder.hpo = hpoService;
        holder.omim = omimService;
        holder.dateFormat = dateFormat;
        holder.logger = this.logger;

        for (JSONObject singlePatient : patientJson) {
            convertedPatients.add(JsonAdapterImpl.patientJsonToObject(singlePatient, holder));
        }
        return convertedPatients;
    }

    private class ServicesHolder
    {
        OntologyService hpo;
        OntologyService omim;
        DateFormat dateFormat;
        Logger logger;
    }

    private static JSONObject patientJsonToObject(JSONObject externalPatient, ServicesHolder holder)
    {
        JSONObject internalPatient = new JSONObject();

        try {
            internalPatient = exchangeIds(externalPatient, internalPatient);
            internalPatient = exchangeBasicPatientData(externalPatient, internalPatient);
            internalPatient = exchangeDates(externalPatient, internalPatient, holder.dateFormat);
            internalPatient = exchangePhenotypes(externalPatient, internalPatient, holder.hpo);
            internalPatient = exchangeDisorders(externalPatient, internalPatient, holder.omim);
        } catch (Exception ex) {
            holder.logger.warn("Could not convert patient. {}", ex.getMessage());
        }

        return internalPatient;
    }

    private static JSONObject putIfNotNull(JSONObject json, String name, Object value)
    {
        if (value != null) {
            json.put(name, value);
        }
        return json;
    }

    private static JSONObject exchangeIds(JSONObject ex, JSONObject inter)
    {
        inter.put("id", ex.get("phenotipsId"));
        inter.put("external_id", ex.get("externalID"));
        return inter;
    }

    private static JSONObject exchangeBasicPatientData(JSONObject ex, JSONObject inter)
    {
        JSONObject name = new JSONObject();
        name.put("first_name", ex.get("fName"));
        name.put("last_name", ex.get("lName"));

        inter.put("sex", ex.get("gender"));
        inter.put("patient_name", name);
        return inter;
    }

    private static JSONObject exchangeDates(JSONObject ex, JSONObject inter, DateFormat format)
    {
        String dob = "dob";
        String dod = "dod";
        if (ex.containsKey(dob)) {
            inter.put("date_of_birth", format.format(
                JsonAdapterImpl.pedigreeDateToDate(ex.getJSONObject(dob))
            ));
        }
        if (ex.containsKey(dod)) {
            inter.put("date_of_death", format.format(
                JsonAdapterImpl.pedigreeDateToDate(ex.getJSONObject(dod))
            ));
        }
        return inter;
    }

    private static JSONObject exchangePhenotypes(JSONObject ex, JSONObject inter, OntologyService hpoService)
        throws Exception
    {
        JSONArray internalTerms = new JSONArray();
        JSONArray externalTerms = ex.optJSONArray("hpoTerms");

        if (externalTerms != null) {
            for (Object termIdObj : externalTerms) {
                OntologyTerm term = hpoService.getTerm(termIdObj.toString());
                if (term != null) {
                    JSONObject termJson = JSONObject.fromObject(term.toJson());
                    termJson.put("observed", "yes");
                    termJson.put("type", "phenotype");
                    internalTerms.add(termJson);
                }
            }
        }

        inter.put("features", internalTerms);
        return inter;
    }

    private static JSONObject exchangeDisorders(JSONObject ex, JSONObject inter, OntologyService omimService)
        throws Exception
    {
        JSONArray internalTerms = new JSONArray();
        JSONArray externalTerms = ex.optJSONArray("disorders");

        if (externalTerms != null) {
            for (Object termIdObj : externalTerms) {
                OntologyTerm term = omimService.getTerm(termIdObj.toString());
                if (term != null) {
                    internalTerms.add(term.toJson());
                }
            }
        }

        inter.put("disorders", internalTerms);
        return inter;
    }

    /**
     * Used for converting a pedigree date to a {@link Date}.
     *
     * @param pedigreeDate cannot be null. Must contain at least the decade field.
     */
    private static Date pedigreeDateToDate(JSONObject pedigreeDate)
    {
        String yearString = "year";
        String monthString = "month";
        String dayString = "day";
        DateTime jodaDate;
        if (pedigreeDate.containsKey(yearString)) {
            Integer year = Integer.parseInt(pedigreeDate.getString(yearString));
            Integer month =
                pedigreeDate.containsKey(monthString) ? Integer.parseInt(pedigreeDate.getString(monthString)) : 1;
            Integer day = pedigreeDate.containsKey(dayString) ? Integer.parseInt(pedigreeDate.getString(dayString)) : 1;
            jodaDate = new DateTime(year, month, day, 0, 0);
        } else {
            String decade = pedigreeDate.getString("decade").substring(0, 4);
            jodaDate = new DateTime(Integer.parseInt(decade), 1, 1, 0, 0);
        }
        return new Date(jodaDate.getMillis());
    }
}
