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
package org.phenotips.data.internal;

import org.phenotips.Constants;
import org.phenotips.data.Patient;
import org.phenotips.data.PatientRepository;
import org.phenotips.studies.family.internal.migration.XWikiFamilyMigrator;

import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.slf4j.Logger;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseStringProperty;
import com.xpn.xwiki.store.XWikiHibernateBaseStore.HibernateCallback;
import com.xpn.xwiki.store.XWikiHibernateStore;
import com.xpn.xwiki.store.migration.DataMigrationException;
import com.xpn.xwiki.store.migration.XWikiDBVersion;
import com.xpn.xwiki.store.migration.hibernate.AbstractHibernateDataMigration;

/**
 * Migration for PhenoTips issue #2154: migrating existing pedigrees into families with one member.
 *
 * @version $Id$
 */
@Component
@Named("R71292PhenoTips#2154")
@Singleton
public class R71292PhenoTips2154DataMigration extends AbstractHibernateDataMigration
{
    private static final EntityReference PEDIGREE_CLASS = new EntityReference("PedigreeClass", EntityType.DOCUMENT,
        Constants.CODE_SPACE_REFERENCE);

    private static final EntityReference FAMILY_CLASS =
        new EntityReference("FamilyClass", EntityType.DOCUMENT, Constants.CODE_SPACE_REFERENCE);

    @Inject
    private DocumentAccessBridge bridge;

    /** Logging helper object. */
    @Inject
    private Logger logger;

    /** Resolves unprefixed document names to the current wiki. */
    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> resolver;

    /** Serializes the class name without the wiki prefix, to be used in the database query. */
    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private PatientRepository patientRepository;

    @Inject
    private XWikiFamilyMigrator familyImport;

    @Override
    public String getDescription()
    {
        return "migrate pedigrees";
    }

    @Override
    public XWikiDBVersion getVersion()
    {
        return new XWikiDBVersion(71310);
    }

    @Override
    protected void hibernateMigrate() throws DataMigrationException, XWikiException
    {
        getStore().executeWrite(getXWikiContext(), new MigratePedigreeCallback());
    }

    private class MigratePedigreeCallback implements HibernateCallback<Object>
    {
        private R71292PhenoTips2154DataMigration migrator = R71292PhenoTips2154DataMigration.this;

        @Override
        public Object doInHibernate(Session session) throws HibernateException, XWikiException
        {
            XWikiContext context = getXWikiContext();
            // Select all patients ** that do not have family but have pedigree **
            Query q =
                session.createQuery("select distinct o.name from BaseObject o where o.className = '"
                    + migrator.serializer.serialize(Patient.CLASS_REFERENCE)
                    + "' and o.name <> 'PhenoTips.PatientTemplate'");

            @SuppressWarnings("unchecked")
            List<String> documents = q.list();

            migrator.logger.debug("Found {} patient documents", documents.size());

            for (String docName : documents) {

                XWikiDocument patientXDocument = getPatientXdocument(docName);
                BaseObject pedigreeXObject = patientXDocument.getXObject(PEDIGREE_CLASS);

                if (pedigreeXObject == null) {
                    migrator.logger.debug("Patient does not have pedigree. Patient Id: {}.",
                        docName);
                    continue;
                }

                BaseStringProperty data = (BaseStringProperty) pedigreeXObject.get("data");
                BaseStringProperty image = (BaseStringProperty) pedigreeXObject.get("image");

                String dataText = data.toText();
                String imageText = image.toText();

                if (StringUtils.isEmpty(dataText) || StringUtils.isEmpty(imageText)) {
                    migrator.logger.debug(
                        "Patient does not have pedigree data or pedigree image ptoperties. Patient Id: {}.",
                        docName);
                    continue;
                }

                migrator.logger.debug("Creating new family for patient {}.", docName);
                XWikiDocument newFamilyDocument =
                    migrator.familyImport.importPatientWithExistingPedigree(patientXDocument, dataText, imageText);
                String familyDocumentRef = newFamilyDocument.getDocumentReference().toString();

                if (familyDocumentRef == null) {
                    migrator.logger.debug("Could not create a family. Patient Id: {}.",
                        docName);
                    continue;
                }

                setFamilyReference(patientXDocument, familyDocumentRef, context);
                patientXDocument.removeXObject(pedigreeXObject);
                patientXDocument.setComment(migrator.getDescription());
                patientXDocument.setMinorEdit(true);

                try {
                    // There's a bug in XWiki which prevents saving an object in the same session that it was loaded,
                    // so we must clear the session cache first.
                    session.clear();
                    ((XWikiHibernateStore) getStore()).saveXWikiDoc(patientXDocument, context, false);
                    ((XWikiHibernateStore) getStore()).saveXWikiDoc(newFamilyDocument, context, false);

                    Query d = session.createQuery("select distinct o.name from BaseObject o where o.className = '"
                        + migrator.serializer.serialize(FAMILY_CLASS) + "'");

                    Query c = session.createQuery("select distinct o.name from BaseObject o");

                    @SuppressWarnings("unchecked")
                    List<String> docsc = c.list();

                    @SuppressWarnings("unchecked")
                    List<String> docs = d.list();

                    session.flush();
                    migrator.logger.debug("Updated [{}]", docName);
                } catch (DataMigrationException e) {
                    // We're in the middle of a migration, we're not expecting another migration
                }
            }

            return null;
        }

        /**
         * Sets the reference to the family document in the patient document.
         */
        private void setFamilyReference(XWikiDocument patientDoc, String documentReference, XWikiContext context)
            throws XWikiException
        {
            BaseObject pointer = patientDoc.getXObject(FAMILY_CLASS);
            if (pointer == null) {
                pointer = patientDoc.newXObject(FAMILY_CLASS, context);
            }
            pointer.set("reference", documentReference, context);
        }

        private XWikiDocument getPatientXdocument(String docName)
        {
            Patient patient = migrator.patientRepository.getPatientById(docName);
            DocumentReference patientDocument = patient.getDocument();
            XWikiDocument patientXDocument = null;
            try {
                patientXDocument = (XWikiDocument) migrator.bridge.getDocument(patientDocument);
            } catch (Exception ex) {
                migrator.logger.error("Could not get patient document: {}", ex.getMessage());
            }
            return patientXDocument;
        }

    }
}
