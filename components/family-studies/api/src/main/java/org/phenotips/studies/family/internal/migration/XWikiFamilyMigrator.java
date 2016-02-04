/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.studies.family.internal.migration;

import org.phenotips.Constants;
import org.phenotips.studies.family.Family;
import org.phenotips.studies.family.Pedigree;
import org.phenotips.studies.family.internal.XWikiFamilyPermissions;

import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * This class converts pedigree information before version ?, into the new format, using the Family interface.
 * Previously, the pedigree object associated with a patient was stored in the patient. Currently, there's a new family
 * object, that contains the pedigree, and a list of members (patients). Each patient has a link to the family object as
 * well. One difference
 *
 * @version $Id$
 */
@Component(roles = { XWikiFamilyMigrator.class })
@Singleton
public class XWikiFamilyMigrator
{
    private static final EntityReference OWNER_CLASS =
        new EntityReference("OwnerClass", EntityType.DOCUMENT, Constants.CODE_SPACE_REFERENCE);

    private static final EntityReference RIGHTS_CLASS =
        new EntityReference("XWikiRights", EntityType.DOCUMENT, new EntityReference("XWiki", EntityType.SPACE));

    private static final String PREFIX = "FAM";

    @Inject
    protected XWikiFamilyPermissions familyPermissions;

    @Inject
    private DocumentAccessBridge bridge;

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> provider;

    @Inject
    private QueryManager queryManager;

    /**
     * Importing a patient with an old version pedigree to have a family object. If a patient has a pedigree object
     * associated with it (and it is not marked as imported), a new family object is created for it. All the family
     * members from that pedigree are assigned to the new family as well, and marked as imported.
     *
     * @param patientXDocument to import to new version
     * @param pedigreeDataText pedigree data
     * @param pedigreeImageText SVG 'image'
     * @return new family document reference string
     */
    public XWikiDocument importPatientWithExistingPedigree(XWikiDocument patientXDocument, String pedigreeDataText,
        String pedigreeImageText)
    {
        if (patientXDocument == null) {
            return null;
        }

        XWikiDocument newFamilyXDocument = null;
        try {
            newFamilyXDocument = this.createFamilyDocument(patientXDocument, pedigreeDataText, pedigreeImageText);
        } catch (Exception e) {
            this.logger.error("Could not create a new family document: {}", e.getMessage());
            throw new IllegalArgumentException();
        }

        String patientId = patientXDocument.getDocumentReference().getName();
        this.processPedigree(JSONObject.fromObject(pedigreeDataText), patientId);

        return newFamilyXDocument;
    }

    private XWikiDocument createFamilyDocument(XWikiDocument patientXDoc, String pedigreeData, String pedigreeImage)
        throws QueryException, XWikiException, Exception
    {
        XWikiContext context = this.provider.get();
        XWiki wiki = context.getWiki();

        DocumentReference patientDocument = patientXDoc.getDocumentReference();

        long nextId = getLastUsedId() + 1;
        String nextStringId = String.format("%s%07d", PREFIX, nextId);

        EntityReference newFamilyRef =
            new EntityReference(nextStringId, EntityType.DOCUMENT, Family.DATA_SPACE);
        XWikiDocument newFamilyXDocument = wiki.getDocument(newFamilyRef, context);
        if (!newFamilyXDocument.isNew()) {
            throw new IllegalArgumentException("The new family id was already taken.");
        }

        // Set owner
        BaseObject ownerObject = newFamilyXDocument.newXObject(OWNER_CLASS, context);
        String ownerId = patientDocument.getName();
        ownerObject.set("owner", ownerId, context);

        // Set family properties info
        BaseObject familyObject = newFamilyXDocument.newXObject(Family.CLASS_REFERENCE, context);
        familyObject.setLongValue("identifier", nextId);
        familyObject.setStringListValue("members", Arrays.asList(patientDocument.getName()));
        familyObject.setStringValue("external_id", "");
        familyObject.setIntValue("warning", 0);
        familyObject.setStringValue("warning_message", "");

        // Set pedigree object properties
        BaseObject pedigreeObject = newFamilyXDocument.newXObject(Family.PEDIGREE_CLASS, context);
        pedigreeObject.set(Pedigree.IMAGE, pedigreeImage, context);
        pedigreeObject.set(Pedigree.DATA, pedigreeData, context);

        // Set permissions object properties
        BaseObject permissionsObject = newFamilyXDocument.newXObject(RIGHTS_CLASS, context);
        String[] fullRights = this.familyPermissions.getEntitiesWithEditAccessAsString(patientXDoc);
        permissionsObject.setStringValue("groups", fullRights[1]);
        permissionsObject.setStringValue("levels", "view,edit");
        permissionsObject.setStringValue("users", fullRights[0]);
        permissionsObject.setIntValue("allow", 1);

        newFamilyXDocument.setAuthorReference(patientXDoc.getAuthorReference());
        newFamilyXDocument.setCreatorReference(patientXDoc.getCreatorReference());
        newFamilyXDocument.setContentAuthorReference(patientXDoc.getContentAuthorReference());

        wiki.saveDocument(newFamilyXDocument, context);

        return newFamilyXDocument;
    }

    /*
     * Adds to the pedigree JSON: "phenotipsId": patient ID in XWiki; "probandNodeID": 0; "JSON_version": "1.0"
     */
    private void processPedigree(JSONObject data, String patientId)
    {
        // Adding patient id under the patient prop
        JSONArray gg = (JSONArray) data.get("GG");
        for (Object nodeObj : gg) {
            JSONObject node = (JSONObject) nodeObj;

            int id = (int) node.get("id");
            if (id != 0) {
                continue;
            }

            JSONObject properties = (JSONObject) node.get("prop");
            properties.accumulate("phenotipsId", patientId);
            break;
        }

        data.accumulate("probandNodeID", 0);
        data.accumulate("JSON_version", "1.0");
    }

    /*
     * The HQL version of XWQL query of XWikiFamilyRepository.getLastUsedId() written in HQL so that it can be run
     * during migration Returns the largest family identifier id
     */
    private long getLastUsedId() throws QueryException
    {
        long crtMaxID = 0;
        Query q = this.queryManager.createQuery(
            "select prop.id.value from BaseObject as obj, LongProperty as prop "
                + "where obj.className='PhenoTips.FamilyClass' and obj.id=prop.id.id "
                + "and prop.id.name='identifier' and prop.id.value is not null order by prop.id.value desc", Query.HQL)
            .setLimit(1);
        List<Long> crtMaxIDList = q.execute();
        if (crtMaxIDList.size() > 0 && crtMaxIDList.get(0) != null) {
            crtMaxID = crtMaxIDList.get(0);
        }
        crtMaxID = Math.max(crtMaxID, 0);
        return crtMaxID;
    }

}
