/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.batch;

import static org.dspace.app.matcher.MetadataValueMatcher.with;
import static org.dspace.batch.service.ImpRecordService.DELETE_OPERATION;
import static org.dspace.batch.service.ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authority.service.AuthorityValueService;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.batch.ImpBitstream;
import org.dspace.batch.ImpBitstreamMetadatavalue;
import org.dspace.batch.ImpMetadatavalue;
import org.dspace.batch.ImpRecord;
import org.dspace.batch.service.ImpBitstreamMetadatavalueService;
import org.dspace.batch.service.ImpBitstreamService;
import org.dspace.batch.service.ImpMetadatavalueService;
import org.dspace.batch.service.ImpRecordService;
import org.dspace.batch.service.ImpServiceFactory;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.MetadataSchemaEnum;
import org.dspace.content.MetadataValue;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.xmlworkflow.factory.XmlWorkflowServiceFactory;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.dspace.xmlworkflow.storedcomponents.service.XmlWorkflowItemService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ImportBatchIT extends AbstractControllerIntegrationTest {
    /**
     * log4j category
     */
    private static final Logger log = LogManager.getLogger(ImportBatchIT.class);

    private static final String SOURCE_REF = "TEST";

    private GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();
    private WorkspaceItemService workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
    private ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
    private AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
    private XmlWorkflowItemService workflowItemService = XmlWorkflowServiceFactory.getInstance()
            .getXmlWorkflowItemService();
    private ImpBitstreamService impBitstreamService = ImpServiceFactory.getInstance().getImpBitstreamService();
    private ImpBitstreamMetadatavalueService impBitstreamMetadatavalueService = ImpServiceFactory.getInstance()
            .getImpBitstreamMetadatavalueService();
    private ImpMetadatavalueService impMetadatavalueService = ImpServiceFactory.getInstance()
            .getImpMetadatavalueService();
    private ImpRecordService impRecordService = ImpServiceFactory.getInstance().getImpRecordService();
    private ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

    private Community owningCommunity;
    private Collection collection;

    private int impSeq = 0;
    private int impMedataSeq = 0;
    private int impBitstreamSeq;
    private int impBitstreamMetadatavalueSeq = 0;

    @Autowired
    private InstallItemService installItemService;

    @Before
    @Override
    public void setUp() throws Exception {
        try {
            super.setUp();

            context.turnOffAuthorisationSystem();
            owningCommunity = CommunityBuilder.createCommunity(context).withTitle("Main Community").build();

            collection = CollectionBuilder.createCollection(context, owningCommunity)
                    .withName("My Collection")
                    .withWorkflowGroup(1, admin)
                    .withWorkflowGroup(2, admin)
                    .withWorkflowGroup(3, admin)
                    .build();
            context.restoreAuthSystemState();
        } catch (Exception ex) {
            log.error("Error during test initialization", ex);
        }
    }

    @After
    @Override
    public void destroy() throws Exception {
        context.turnOffAuthorisationSystem();
        // cleanup all workspace items
        for (WorkspaceItem w : workspaceItemService.findAll(context)) {
            workspaceItemService.deleteAll(context, w);
        }
        for (XmlWorkflowItem w : workflowItemService.findAll(context)) {
            workflowItemService.delete(context, w);
        }
        Iterator<Item> allItems = itemService.findAll(context);
        while (allItems.hasNext()) {
            Item item = allItems.next();
            itemService.delete(context, item);
        }
        context.restoreAuthSystemState();
        impRecordService.cleanupTables(context);
        context.commit();
        super.destroy();
    }
    /***
     * Create a new workspace item.
     *
     * @throws IOException
     */
    @Test
    public void createNewWorkspaceItem() throws IOException {
        try {
            // create imp_record records
            int impRecordKey = 1;
            ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

            // create imp_metadatavalue records
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(), "title",
                    null, null, "Sample Item");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };

            ItemImportMainOA.main(argv);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("One workspace item found for " + admin.getID(), 1, nItem);

            List<WorkspaceItem> wis = workspaceItemService.findByEPerson(context, admin);
            assertEquals("One workspace item found for " + admin.getID(), 1, wis.size());

            WorkspaceItem wi = wis.get(0);
            Item item = wi.getItem();

            List<MetadataValue> metadata = item.getMetadata();
            // one metadata is explicit the other is the cris.sourceid
            assertEquals("Only two metadata found", 2, metadata.size());

            String defLanguage = configurationService.getProperty("default.language");
            metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "title", null, defLanguage);
            assertEquals("Only one metadata is assigned to the item", 1, metadata.size());
            assertEquals("Is the new metadata value the right one?", metadata.get(0).getValue(), "Sample Item");
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Remove an item.
     *
     * @throws IOException
     */
    @Test
    public void deleteItem() {
        try {
            context.turnOffAuthorisationSystem();
            // create an item
            WorkspaceItem wi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                    .withTitle("sample item").build();
            Item item = wi.getItem();
            itemService.update(context, item);
            context.restoreAuthSystemState();

            // create imp_record records
            int impRecordKey = 1;
            createImpRecord(context, impRecordKey, SEND_BACK_TO_WORKSPACE_STATUS, DELETE_OPERATION, admin, collection);

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };

            ItemImportMainOA.main(argv);

            wi = workspaceItemService.find(context, wi.getID());
            assertNull("Is the workspace item null?", wi);

            item = itemService.find(context, item.getID());
            assertNull("Is the item null?", item);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("No workspace item found for " + admin.getID(), 0, nItem);
        } catch (SQLException | AuthorizeException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Add some metadata to an existing item. Old metadata are cleared.
     *
     * @throws IOException
     */
    @Test
    public void updateItemAndClean() throws IOException {
        try {
            int impRecordKey = 1;

            context.turnOffAuthorisationSystem();
            // create an item
            context.setCurrentUser(admin);
            WorkspaceItem wi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("sample item")
                .withCrisSourceId(SOURCE_REF + AuthorityValueService.SPLIT + impRecordKey)
                .build();
            Item item = wi.getItem();
            context.commit();
            context.restoreAuthSystemState();

            // create imp_record records
            ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "author", null, "Francesco Cadili");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };

            ItemImportMainOA.main(argv);

            assertNotNull("Does the workspace item exist?", workspaceItemService.find(context, wi.getID()));
            item = itemService.find(context, item.getID());
            assertNotNull("Does the item exist?", item);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("One workspace item found for " + admin.getID(), 1, nItem);

            List<MetadataValue> metadata = item.getMetadata();
            assertEquals("Only one metadata found", 1, metadata.size());
            assertEquals("Is the value the right one?", metadata.get(0).getValue(), "Francesco Cadili");

            String defLanguage = configurationService.getProperty("default.language");
            metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "contributor", "author",
                    defLanguage);
            assertEquals("Only one metadata is assigned to the item", 1, metadata.size());
            assertEquals("Is the new metadata value the right one?", metadata.get(0).getValue(), "Francesco Cadili");
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Add some metadata to an existing item. Old metadata are kept.
     *
     * @throws IOException
     */
    @Test
    public void updateItemAndKeep() throws IOException {
        try {
            int impRecordKey = 1;

            context.turnOffAuthorisationSystem();
            // create an item
            context.setCurrentUser(admin);
            WorkspaceItem wi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("sample item")
                .withCrisSourceId(SOURCE_REF + AuthorityValueService.SPLIT + impRecordKey)
                .build();
            Item item = wi.getItem();
            context.restoreAuthSystemState();

            // create imp_record records
            ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

            // create imp_metadatavalue records
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "author", null, "Francesco Cadili");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail(), "-m", "dc.title", "-s" };

            ItemImportMainOA.main(argv);

            assertNotNull("Does the workspace item exist?", workspaceItemService.find(context, wi.getID()));
            item = itemService.find(context, item.getID());
            assertNotNull("Does the item exist?", item);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("One workspace item found for " + admin.getID(), 1, nItem);

            List<MetadataValue> metadata = item.getMetadata();
            assertEquals("Three metadata found", 3, metadata.size());
            for (MetadataValue m : metadata) {
                if ("contributor".equals(m.getElement())) {
                    assertEquals("dc.contibutor.autor is the right one", m.getValue(), "Francesco Cadili");
                } else if ("title".equals(m.getElement())) {
                    assertEquals("dc.title value is the right one", m.getValue(), "sample item");
                } else if ("sourceId".equals(m.getElement())) {
                    assertEquals("cris.sourceId value is the right one", m.getValue(), "TEST::1");
                } else {
                    assertTrue("Metadata is not valid.", m == null);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Create some new workspace items.
     *
     * @throws IOException
     */
    @Test
    public void createSomeNewWorkspaceItem() throws IOException {
        try {
            // create imp_record records
            for (int impRecordKey = 1; impRecordKey < 15; impRecordKey++) {
                ImpRecord impRecord = createImpRecord(context, impRecordKey,
                        ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                        ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);
                createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                        "title", null, null, "Sample Item (" + impRecordKey + ")");
            }

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };

            ItemImportMainOA.main(argv);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("14 workspace Items found for " + admin.getID(), 14, nItem);

            List<WorkspaceItem> wis = workspaceItemService.findByEPerson(context, admin);
            assertEquals("14 workspace items found for " + admin.getID(), 14, wis.size());

            for (WorkspaceItem wi : wis) {
                Item item = wi.getItem();

                List<MetadataValue> metadata = item.getMetadata();
                // one metadata is explicit the other is the cris.sourceid
                assertEquals("Only two metadata found", 2, metadata.size());

                String defLanguage = configurationService.getProperty("default.language");
                metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "title", null, defLanguage);
                assertEquals("Only one metadata is assigned to the item", 1, metadata.size());
                assertTrue("Is the new metadata value the right one?",
                        metadata.get(0).getValue().indexOf("Sample Item") == 0);
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Complex example: some insert, followed by some deletion and updates.
     *
     * @throws IOException
     */
    @Test
    public void runComplexExample() throws IOException {
        try {
            context.turnOffAuthorisationSystem();
            // create imp_record records
            for (int impRecordKey = 1; impRecordKey < 15; impRecordKey++) {
                ImpRecord impRecord = createImpRecord(context, impRecordKey,
                        ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                        ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);
                createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                        "title", null, null, "Sample Object (" + impRecordKey + ")");
                createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                        "contributor", "author", null, "Francesco Cadili");
            }

            // Create 14 new items
            String argv[] = new String[] { "-E", admin.getEmail() };
            ItemImportMainOA.main(argv);

            List<WorkspaceItem> wis = workspaceItemService.findByEPerson(context, admin);
            assertEquals("14 workspace items found for " + admin.getID(), 14, wis.size());

            WorkspaceItem del01 = null;
            WorkspaceItem upd02 = null;
            WorkspaceItem upd10 = null;
            WorkspaceItem del11 = null;
            for (WorkspaceItem wi : wis) {
                List<MetadataValue> metadata = wi.getItem().getMetadata();

                // two metadata are explicit the other is the cris.sourceid
                assertEquals("Only three metadata found", 3, metadata.size());

                for (MetadataValue m : metadata) {
                    if ("title".equals(m.getElement())) {
                        assertTrue("the title is: ", m.getValue().indexOf("Sample Object (") == 0);

                        int start = "Sample Object (".length();
                        int end = m.getValue().indexOf(")");
                        int recordKey = Integer.parseInt(m.getValue().substring(start, end));

                        switch (recordKey) {
                            case 1: {
                                del01 = wi;
                                break;
                            }
                            case 2: {
                                upd02 = wi;
                                break;
                            }
                            case 10: {
                                upd10 = wi;
                                break;
                            }
                            case 11: {
                                del11 = wi;
                                break;
                            }
                            default: {
                                assertTrue(recordKey != 1 && recordKey != 2 && recordKey != 10 && recordKey != 11);
                                break;
                            }
                        }
                    } else if ("contributor".equals(m.getElement())) {
                        assertEquals("The contributor.author is: ", "Francesco Cadili", m.getValue());
                    } else if ("sourceId".equals(m.getElement())) {
                        assertNotNull("The source id is null ", m.getValue());
                    } else {
                        assertEquals("Invalid Metadata", null, m.getValue());
                    }
                }
            }

            assertTrue("Selected workspace exists (del01)", del01 != null);
            assertTrue("Selected workspace exists (upd02)", upd02 != null);
            assertTrue("Selected workspace exists (upd10)", upd10 != null);
            assertTrue("Selected workspace exists (del11)", del11 != null);

            // remove del01 and del11
            ImpRecord impRecord = createImpRecord(context, 1,
                    ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS, ImpRecordService.DELETE_OPERATION, admin,
                    collection);
            impRecord = createImpRecord(context, 11, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.DELETE_OPERATION, admin, collection);

            // Delete two items
            argv = new String[] { "-E", admin.getEmail() };
            ItemImportMainOA.main(argv);

            assertNull("Is the workspace item null?", workspaceItemService.find(context, del01.getID()));
            assertNull("Is the workspace item null?", workspaceItemService.find(context, del11.getID()));

            assertNull("Is the item null?", itemService.find(context, del01.getItem().getID()));
            assertNull("Is the item null?", itemService.find(context, del11.getItem().getID()));

            wis = workspaceItemService.findByEPerson(context, admin);
            assertEquals("12 workspace items found for " + admin.getID(), 12, wis.size());

            for (WorkspaceItem wi : wis) {
                List<MetadataValue> metadata = wi.getItem().getMetadata();

                assertEquals("Only three metadata found", 3, metadata.size());

                for (MetadataValue m : metadata) {
                    if ("title".equals(m.getElement())) {
                        assertTrue("The title is: ", m.getValue().indexOf("Sample Object (") == 0);

                        int start = "Sample Object (".length();
                        int end = m.getValue().indexOf(")");
                        int recordKey = Integer.parseInt(m.getValue().substring(start, end));

                        switch (recordKey) {
                            case 1: {
                                assertTrue("Failed to remove ", recordKey != 1);
                                break;
                            }
                            case 11: {
                                assertTrue("Failed to remove ", recordKey != 11);
                                break;
                            }
                            default: {
                                assertTrue(recordKey != 1 && recordKey != 11);
                                break;
                            }
                        }
                    } else if ("contributor".equals(m.getElement())) {
                        assertEquals("The contributor.author is: ", "Francesco Cadili", m.getValue());
                    } else if ("sourceId".equals(m.getElement())) {
                        assertNotNull("The source id is null ", m.getValue());
                    } else {
                        assertEquals("Invalid Metadata", null, m.getValue());
                    }
                }
            }

            // update (using two records instead of one)
            impRecord = createImpRecord(context, 2,
                    ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "editor", null, "Matteo Perelli");

            impRecord = createImpRecord(context, 10,
                    ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "date", null, null, "2020/03/23");

            // Create a new item
            argv = new String[] { "-E", admin.getEmail(), "-m", "dc.title", "-m", "dc.contributor.author", "-m",
                    "cris.sourceId", "-s" };
            ItemImportMainOA.main(argv);

            wis = workspaceItemService.findByEPerson(context, admin);
            assertEquals("Workspace Item found 12 for " + admin.getID(), 12, wis.size());

            for (WorkspaceItem wi : wis) {
                List<MetadataValue> metadata = wi.getItem().getMetadata();

                if (!wi.getID().equals(upd02.getID()) && !wi.getID().equals(upd10.getID())) {
                    assertEquals("Only three metadata found", 3, metadata.size());
                } else {
                    assertEquals("Only four metadata found", 4, metadata.size());
                }

                for (MetadataValue m : metadata) {
                    if ("title".equals(m.getElement())) {
                        assertTrue("The title is: ", m.getValue().indexOf("Sample Object (") == 0);

                        int start = "Sample Object (".length();
                        int end = m.getValue().indexOf(")");
                        int recordKey = Integer.parseInt(m.getValue().substring(start, end));

                        switch (recordKey) {
                            case 1: {
                                assertTrue("Failed to remove ", recordKey != 1);
                                break;
                            }
                            case 11: {
                                assertTrue("Failed to remove ", recordKey != 11);
                                break;
                            }
                            default: {
                                assertTrue(recordKey != 1 && recordKey != 11);
                                break;
                            }
                        }
                    } else if ("contributor".equals(m.getElement()) && "author".equals(m.getQualifier())) {
                        assertEquals("The dc.contributor.author is: ", "Francesco Cadili", m.getValue());
                    } else if ("contributor".equals(m.getElement()) && "editor".equals(m.getQualifier())) {
                        assertEquals("The dc.contributor.author is: ", "Matteo Perelli", m.getValue());
                        assertEquals("The workspace item is: ", upd02.getID(), wi.getID());
                    } else if ("date".equals(m.getElement())) {
                        assertEquals("The dc.date is: ", "2020/03/23", m.getValue());
                        assertEquals("The workspace item is: ", upd10.getID(), wi.getID());
                    } else if ("sourceId".equals(m.getElement())) {
                        assertNotNull("The source id is null ", m.getValue());
                    } else {
                        assertEquals("Invalid Metadata", null, m.getValue());
                    }
                }
            }

        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Test bitstream creation with one metadata. Embargo group is not set.
     *
     * @throws IOException
     * @throws URISyntaxException
     */
    @Test
    public void addBitstream() throws IOException, URISyntaxException {
        try {
            int impRecordKey = 1;

            context.turnOffAuthorisationSystem();
            // create a workflowitem
            context.setCurrentUser(admin);
            WorkspaceItem wi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("sample item")
                .withCrisSourceId(SOURCE_REF + AuthorityValueService.SPLIT + impRecordKey)
                .build();
            // create groups
            context.restoreAuthSystemState();

            // create imp_record records
            ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

            // create imp_metadatavalue records
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "author", null, "Francesco Cadili");

            // create imp_record_to_item records
            Item item = wi.getItem();

            InputStream resource = getClass().getResourceAsStream("/org/dspace/app/rest/simple-article.pdf");
            ImpBitstream impBitstream = createImpBitstream(context, impRecord,
                    resource, "simple-article.pdf",
                    "Simple article", null, null);

            createImpBitstreamMetadatavalue(context, impBitstream,
                    MetadataSchemaEnum.DC.getName(), "description", null, null, "Simple article");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };

            ItemImportMainOA.main(argv);

            assertNotNull("Does workspace item exist?", workspaceItemService.find(context, wi.getID()));
            item = itemService.find(context, item.getID());
            assertNotNull("Does theh item exist?", item);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("Workspace Item found 1 for " + admin.getID(), 1, nItem);

            List<MetadataValue> metadata = item.getMetadata();
            assertEquals("Only one metadata", 1, metadata.size());
            assertEquals("Is the value the right one?", metadata.get(0).getValue(), "Francesco Cadili");

            String defLanguage = configurationService.getProperty("default.language");
            metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "contributor", "author",
                    defLanguage);
            assertEquals("Only one metadata is assigned to the item", 1, metadata.size());
            assertEquals("Is the new metadata value the right one?", metadata.get(0).getValue(), "Francesco Cadili");

            Iterator<Bitstream> iter = bitstreamService.getItemBitstreams(context, item);
            while (iter.hasNext()) {
                Bitstream b = iter.next();

                // check metadata
                String m = bitstreamService.getMetadata(b, "dc.description");
                assertEquals("check metadata", "Simple article", m);

                // check policy
                List<ResourcePolicy> p = authorizeService.getPolicies(context, b);
                assertEquals("Only one embrago policy", 1, p.size());

                Group g = groupService.findByName(context, Group.ANONYMOUS);
                assertEquals("Use anonymous group in emmargo policy", g.getName(), p.get(0).getGroup().getName());

                context.turnOffAuthorisationSystem();
                bitstreamService.delete(context, b);
                context.restoreAuthSystemState();

                assertEquals("Only one bitstream", false, iter.hasNext());
            }
        } catch (SQLException | AuthorizeException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Test bitstream creation with one metadata. Embargo group is set to
     * administrator.
     *
     * @throws IOException
     * @throws URISyntaxException
     */
    @Test
    public void addBitstreamWithEmbargoGroup() throws IOException, URISyntaxException {
        try {
            int impRecordKey = 1;

            context.turnOffAuthorisationSystem();
            Group adminGroup = groupService.findByName(context, Group.ADMIN);

            // create an item
            context.setCurrentUser(admin);
            WorkspaceItem wi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("sample item")
                .withCrisSourceId(SOURCE_REF + AuthorityValueService.SPLIT + impRecordKey)
                .build();
            Item item = wi.getItem();
            context.restoreAuthSystemState();

            // create imp_record records
            ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

            // create imp_metadatavalue records
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "author", null, "Francesco Cadili");

            InputStream resource = getClass().getResourceAsStream("/org/dspace/app/rest/simple-article.pdf");
            ImpBitstream impBitstream = createImpBitstream(context, impRecord,
                    resource, "simple-article.pdf",
                    "Simple article", adminGroup.getID(), null);

            createImpBitstreamMetadatavalue(context, impBitstream,
                    MetadataSchemaEnum.DC.getName(), "description", null, null, "Simple article");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };

            ItemImportMainOA.main(argv);

            assertNotNull("Does the workspace item exist?", workspaceItemService.find(context, wi.getID()));
            item = itemService.find(context, item.getID());
            assertNotNull("Does theh item exist?", item);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("One workspace item found for " + admin.getID(), 1, nItem);

            List<MetadataValue> metadata = item.getMetadata();
            assertEquals("Only one metadata found", 1, metadata.size());
            assertEquals("Is the value the right one?", metadata.get(0).getValue(), "Francesco Cadili");

            String defLanguage = configurationService.getProperty("default.language");
            metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "contributor", "author",
                    defLanguage);
            assertEquals("Only one metadata is assigned to the item", 1, metadata.size());
            assertEquals("Is the new metadata value the right one?", metadata.get(0).getValue(), "Francesco Cadili");

            Iterator<Bitstream> iter = bitstreamService.getItemBitstreams(context, item);
            while (iter.hasNext()) {
                Bitstream b = iter.next();

                // check metadata
                String m = bitstreamService.getMetadata(b, "dc.description");
                assertEquals("Check metadata", "Simple article", m);

                // check policy
                List<ResourcePolicy> p = authorizeService.getPolicies(context, b);
                assertEquals("Only one embrago policy", 1, p.size());

                assertEquals("Use administrator group in embargo policy", adminGroup.getName(),
                        p.get(0).getGroup().getName());

                context.turnOffAuthorisationSystem();
                bitstreamService.delete(context, b);
                context.restoreAuthSystemState();

                assertEquals("Only one bitstream", false, iter.hasNext());
            }
        } catch (SQLException | AuthorizeException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Test bitstream creation with one metadata. Embargo group is set to
     * administrator. Embargo start date is set to a valid data in format
     * dd/MM/yyyy.
     *
     * @See {@link SimpleDateFormat}
     *
     * @throws IOException
     * @throws URISyntaxException
     */
    @Test
    public void addBitstreamWithEmbargoGroupAndDate() throws IOException, URISyntaxException {
        try {
            int impRecordKey = 1;
            context.turnOffAuthorisationSystem();
            Group adminGroup = groupService.findByName(context, Group.ADMIN);

            // create an item
            context.setCurrentUser(admin);
            WorkspaceItem wi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("sample item")
                .withCrisSourceId(SOURCE_REF + AuthorityValueService.SPLIT + impRecordKey)
                .build();
            Item item = wi.getItem();
            context.restoreAuthSystemState();

            // create imp_record records
            ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

            // create imp_metadatavalue records
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "author", null, "Francesco Cadili");

            InputStream resource = getClass().getResourceAsStream("/org/dspace/app/rest/simple-article.pdf");
            ImpBitstream impBitstream = createImpBitstream(context, impRecord,
                     resource, "simple-article.pdf",
                     "Simple article", adminGroup.getID(), "01/02/2020");

            createImpBitstreamMetadatavalue(context, impBitstream,
                    MetadataSchemaEnum.DC.getName(), "description", null, null, "Simple article");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };

            ItemImportMainOA.main(argv);

            assertNotNull("Does the workspace item exist?", workspaceItemService.find(context, wi.getID()));
            item = itemService.find(context, item.getID());
            assertNotNull("Does theh item exist?", item);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("One workspace item found for " + admin.getID(), 1, nItem);

            List<MetadataValue> metadata = item.getMetadata();
            assertEquals("Only one metadata found", 1, metadata.size());
            assertEquals("Is the value the right one?", metadata.get(0).getValue(), "Francesco Cadili");

            String defLanguage = configurationService.getProperty("default.language");
            metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "contributor", "author",
                    defLanguage);
            assertEquals("Only one metadata is assigned to the item", 1, metadata.size());
            assertEquals("Is the new metadata value the right one?", metadata.get(0).getValue(), "Francesco Cadili");

            Iterator<Bitstream> iter = bitstreamService.getItemBitstreams(context, item);
            while (iter.hasNext()) {
                Bitstream b = iter.next();

                // check metadata
                String m = bitstreamService.getMetadata(b, "dc.description");
                assertEquals("Check metadata", "Simple article", m);

                // check policy
                List<ResourcePolicy> p = authorizeService.getPolicies(context, b);
                assertEquals("Only one embrago policy", 1, p.size());

                assertEquals("Use administrator group in emmargo policy", adminGroup.getName(),
                        p.get(0).getGroup().getName());

                Calendar cal = Calendar.getInstance();
                cal.clear();
                // The month starts from 0
                cal.set(2020, 01, 01, 0, 0, 0);
                assertEquals("Embargo end date is set", cal.getTimeInMillis(), p.get(0).getStartDate().getTime());

                context.turnOffAuthorisationSystem();
                bitstreamService.delete(context, b);
                context.restoreAuthSystemState();

                assertEquals("Only one bitstream", false, iter.hasNext());
            }
        } catch (SQLException | AuthorizeException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Start workflow.
     *
     * @throws IOException
     */
    @Test
    public void startWorkflow() throws IOException {
        try {
            int impRecordKey = 1;

            context.turnOffAuthorisationSystem();
            // create an item
            context.setCurrentUser(admin);
            WorkspaceItem wi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("sample item")
                .withCrisSourceId(SOURCE_REF + AuthorityValueService.SPLIT + impRecordKey)
                .build();
            Item item = wi.getItem();
            context.restoreAuthSystemState();

            // create imp_record records
            ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_THROUGH_WORKFLOW_STATUS,
                    ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

            // create imp_metadatavalue records
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "author", null, "Francesco Cadili");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail(), "-m", "dc.title", "-s" };

            ItemImportMainOA.main(argv);

            assertNull("Does the workspace item exist?", workspaceItemService.find(context, wi.getID()));

            item = itemService.find(context, item.getID());
            assertNotNull("Does theh item exist?", item);

            List<XmlWorkflowItem> xwil = workflowItemService.findByCollection(context, collection);
            assertEquals("Ony one workflow item in the collection", 1, xwil.size());
            assertEquals("Is the workflow item the right one?", item, xwil.get(0).getItem());

            XmlWorkflowItem xwi = workflowItemService.findByItem(context, item);
            assertEquals("Is the workflow item the right one?", item, xwi.getItem());

            xwil = workflowItemService.findBySubmitter(context, admin);
            assertEquals("Ony one workflow item in the collection", 1, xwil.size());
            assertEquals("Is the workflow item the right one?", item, xwil.get(0).getItem());

            List<MetadataValue> metadata = item.getMetadata();
            assertEquals("Four metadata found", 4, metadata.size());
            for (MetadataValue m : metadata) {
                if ("contributor".equals(m.getElement())) {
                    assertEquals("The dc.contibutor.autor is the right one!", m.getValue(), "Francesco Cadili");
                } else if ("title".equals(m.getElement())) {
                    assertEquals("The dc.title value is the right one!", m.getValue(), "sample item");
                } else if ("description".equals(m.getElement()) && "provenance".equals(m.getQualifier())) {
                    assertTrue("The dc.description.provenance value is the right one!",
                            m.getValue().indexOf("Submitted by first (admin) last (admin) (admin@email.com) on ") == 0);
                    assertTrue("The dc.description.provenance value is the right one!",
                            m.getValue().indexOf("workflow start=Step: reviewstep - action:claimaction") > 0);
                } else if ("sourceId".equals(m.getElement())) {
                    assertNotNull("The source id is the right one! ", "TEST::1");
                } else {
                    assertTrue("Metadata is not valid.", m == null);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /***
     * Reinstate an item.
     *
     * @throws IOException
     */
    @Test
    public void reinstateItem() throws IOException {
        try {
            int impRecordKey = 1;

            context.turnOffAuthorisationSystem();
            // create an item
            Item item = ItemBuilder.createItem(context, collection)
                .withTitle("sample item")
                .withdrawn()
                .withCrisSourceId(SOURCE_REF + AuthorityValueService.SPLIT + impRecordKey)
                .build();
            context.restoreAuthSystemState();

            // create imp_record records
            ImpRecord impRecord = createImpRecord(context, impRecordKey,
                    ImpRecordService.REINSTATE_WITHDRAW_ITEM_STATUS, ImpRecordService.INSERT_OR_UPDATE_OPERATION,
                    admin, collection);

            // create imp_metadatavalue records
            createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                    "contributor", "author", null, "Francesco Cadili");

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail(), "-m", "dc.title", "-s" };

            ItemImportMainOA.main(argv);

            item = itemService.find(context, item.getID());
            assertNotNull("Does item exist?", item);

            assertEquals("Is item withdraw ?", false, item.isWithdrawn());
            assertEquals("Is item archived ?", true, item.isArchived());

            List<MetadataValue> metadata = item.getMetadata();
            assertEquals("Four metadata found", 4, metadata.size());
            for (MetadataValue m : metadata) {
                if ("contributor".equals(m.getElement())) {
                    assertEquals("The dc.contibutor.autor is the right one!", m.getValue(), "Francesco Cadili");
                } else if ("title".equals(m.getElement())) {
                    assertEquals("The dc.title value is the right one!", m.getValue(), "sample item");
                } else if ("description".equals(m.getElement()) && "provenance".equals(m.getQualifier())) {
                    assertTrue("The dc.description.provenance value is the right one!",
                            m.getValue()
                            .indexOf("Item reinstated by first (admin) last (admin) (admin@email.com) on ") == 0);
                } else if ("sourceId".equals(m.getElement())) {
                    assertNotNull("The source id is the right one! ", "TEST::1");
                } else {
                    assertTrue("Metadata is not valid.", m == null);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    @Test
    public void testInsertionWithMetadataValueSecurityLevelSet() throws IOException, SQLException {
        // create imp_record records
        int impRecordKey = 1;
        ImpRecord impRecord = createImpRecord(context, impRecordKey, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
            ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);

        // create imp_metadatavalue records
        createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(), "title",
            null, null, "Sample Item", 1);
        createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(), "type",
            null, null, "Item type", 2);

        // Create a new item
        String argv[] = new String[] { "-E", admin.getEmail() };

        ItemImportMainOA.main(argv);

        int nItem = workspaceItemService.countByEPerson(context, admin);
        assertEquals("One workspace item found for " + admin.getID(), 1, nItem);

        List<WorkspaceItem> wis = workspaceItemService.findByEPerson(context, admin);
        assertEquals("One workspace item found for " + admin.getID(), 1, wis.size());

        WorkspaceItem wi = wis.get(0);
        Item item = wi.getItem();

        List<MetadataValue> metadata = item.getMetadata();
        assertEquals("Only two metadata found", 3, metadata.size());

        String defLanguage = configurationService.getProperty("default.language");
        metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "title", null, defLanguage);
        assertThat(metadata, hasSize(1));
        assertThat(metadata.get(0).getValue(), is("Sample Item"));
        assertThat(metadata.get(0).getSecurityLevel(), is(Integer.valueOf(1)));

        metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "type", null, defLanguage);
        assertThat(metadata, hasSize(1));
        assertThat(metadata.get(0).getValue(), is("Item type"));
        assertThat(metadata.get(0).getSecurityLevel(), is(Integer.valueOf(2)));
    }

    @Test
    public void createItemWithAvailableDAteTest() throws Exception {
        List<WorkspaceItem> wis = null;
        String dateAvailable = "2010-11-21T08:56:26Z";
        try {
            // create one imp_record record with dc.date.accessioned metadata
            for (int impRecordKey = 1; impRecordKey < 2; impRecordKey++) {
                ImpRecord impRecord = createImpRecord(context, impRecordKey,
                        ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
                        ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, collection);
                createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(),
                        "title", null, null, "Sample Item (" + impRecordKey + ")");
                createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(), "date",
                        "available", null, dateAvailable, 1);
            }

            // Create a new item
            String argv[] = new String[] { "-E", admin.getEmail() };
            ItemImportMainOA.main(argv);

            int nItem = workspaceItemService.countByEPerson(context, admin);
            assertEquals("1 workspace Items found for " + admin.getID(), 1, nItem);

            wis = workspaceItemService.findByEPerson(context, admin);
            assertEquals("1 workspace items found for " + admin.getID(), 1, wis.size());

            for (WorkspaceItem wi : wis) {
                Item item = wi.getItem();

                List<MetadataValue> metadata = item.getMetadata();
                String defLanguage = configurationService.getProperty("default.language");
                metadata = itemService.getMetadata(item, MetadataSchemaEnum.DC.getName(), "title", null, defLanguage);
                assertEquals("Only one metadata is assigned to the item", 1, metadata.size());
                assertTrue("Is the new metadata value the right one?",
                        metadata.get(0).getValue().indexOf("Sample Item") == 0);
            }
            context.turnOffAuthorisationSystem();
            installItemService.installItem(context, wis.get(0));
            context.restoreAuthSystemState();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }

        // check that the metadata dc.date.accessioned was not replaced
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/items/" + wis.get(0).getItem().getID()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.metadata['dc.date.available'][0].value", is(dateAvailable)))
                        .andExpect(jsonPath("$.metadata['dc.date.available'][1].value").doesNotExist())
                        .andExpect(jsonPath("$.metadata['dc.title'][0].value", is("Sample Item (1)")));

    }

    @Test
    public void testDSpaceEntityTypeIsKeeped() throws SQLException {

        context.turnOffAuthorisationSystem();

        Collection publicationCollection = CollectionBuilder.createCollection(context, owningCommunity)
            .withName("Publications")
            .withEntityType("Publication")
            .build();

        Item publication = ItemBuilder.createItem(context, publicationCollection)
            .withTitle("Test publication")
            .withIssueDate("2020/01/02")
            .withSubject("Research")
            .build();

        ImpRecord impRecord = createImpRecord(context, 1, ImpRecordService.SEND_BACK_TO_WORKSPACE_STATUS,
            ImpRecordService.INSERT_OR_UPDATE_OPERATION, admin, publicationCollection);

        createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(), "title",
            null, null, "New Test publication", null);

        createImpMetadatavalue(context, impRecord, MetadataSchemaEnum.DC.getName(), "contributor",
            "author", null, "John Smith", null);

        context.restoreAuthSystemState();

        assertThat(publication.getMetadata(), hasItem(with("dspace.entity.type", "Publication")));

        // Create a new item
        String argv[] = new String[] { "-E", admin.getEmail(),
            "-o", publication.getID().toString(),
            "-I", impRecord.getImpId().toString(),
            "-e", admin.getID().toString(),
            "-r",
            "-c", publicationCollection.getID().toString() };

        ItemImportOA.main(argv);

        publication = context.reloadEntity(publication);

        List<MetadataValue> metadataValues = publication.getMetadata();
        assertThat(metadataValues, hasItem(with("dspace.entity.type", "Publication")));
        assertThat(metadataValues, hasItem(with("dc.title", "New Test publication", "en_US", null, 0, -1)));
        assertThat(metadataValues, hasItem(with("dc.contributor.author", "John Smith", "en_US", null, 0, -1)));
    }

    /***
     * Create an ImpRecord.
     *
     * @param context      The context
     * @param impRecordKey The key
     * @param eperson      The submitter
     * @param collection   The collection
     * @return
     * @throws SQLException
     */
    private ImpRecord createImpRecord(Context context, int impRecordKey, Character status, String operation,
            EPerson eperson, Collection collection) throws SQLException {
        // create imp_record records
        String sourceRecordId = "" + impRecordKey;
        ImpRecord impRecord = new ImpRecord();
        impRecord.setImpId(impSeq++);
        impRecordService.setImpCollection(impRecord, collection);
        impRecordService.setImpEperson(impRecord, eperson);
        impRecord.setImpRecordId(sourceRecordId);
        impRecord.setImpSourceref(SOURCE_REF);
        impRecordService.setStatus(impRecord, status);
        impRecordService.setOperation(impRecord, operation);

        return impRecordService.create(context, impRecord);
    }

    /***
     * Create a Metadata of ImpRecord
     *
     * @param context             The context
     * @param impRecordKey        The ImpRecord key
     * @param schema              The schema
     * @param qualifier           The qualifier
     * @param language            The language
     * @param value               The metadata value
     * @return
     * @throws SQLException
     */
    private ImpMetadatavalue createImpMetadatavalue(Context context, ImpRecord impRecord,
            String schema, String element, String qualifier, String language, String value) throws SQLException {
        return createImpMetadatavalue(context, impRecord, schema, element, qualifier, language, value, null);
    }

    /***
     * Create a Metadata of ImpRecord
     *
     * @param  context      The context
     * @param  impRecordKey The ImpRecord key
     * @param  schema       The schema
     * @param  qualifier    The qualifier
     * @param  language     The language
     * @param  value        The metadata value
     * @return
     * @throws SQLException
     */
    private ImpMetadatavalue createImpMetadatavalue(Context context, ImpRecord impRecord,
        String schema, String element, String qualifier, String language, String value, Integer securityLevel)
        throws SQLException {
        ImpMetadatavalue impMetadatavalue = new ImpMetadatavalue();
        impMetadatavalue.setMetadatavalueId(impMedataSeq++);
        impMetadatavalue.setImpRecord(impRecord);
        List<ImpMetadatavalue> metadata = impMetadatavalueService.searchByImpRecordId(context, impRecord);
        impMetadatavalueService.setMetadata(impMetadatavalue, schema, element, qualifier, language, value);
        impMetadatavalue.setMetadataOrder(metadata.size() + 1);
        impMetadatavalue.setSecurityLevel(securityLevel);

        return impMetadatavalueService.create(context, impMetadatavalue);
    }

    /***
     * Create a Metadata of ImpRecord
     *
     * @param context          The context
     * @param impRecordKey     The ImpRecord key
     * @param impBitstreamSeq   The impBitstream key
     * @param resouce          The resource to upload
     * @param name             The resource name
     * @param description      The resource description
     * @param embargoGroup     The embargo group (or null)
     * @param embargoStartDate The embargo data (or null)
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws URISyntaxException
     */
    private ImpBitstream createImpBitstream(Context context, ImpRecord impRecord, InputStream resource,
            String name, String description, UUID embargoGroup, String embargoStartDate)
            throws SQLException, IOException, URISyntaxException {
        File f = File.createTempFile("myTempFile", ".pdf");
        java.nio.file.Files.copy(resource, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        resource.close();
        ImpBitstream impBitstream = new ImpBitstream();
        impBitstream.setImpBitstreamId(impBitstreamSeq++);
        impBitstream.setImpRecord(impRecord);
        impBitstream.setDescription(description);
        impBitstream.setBitstreamOrder(1);
        impBitstream.setName(name);
        impBitstream.setFilepath(f.getAbsolutePath());
        try (InputStream is = Files.newInputStream(Paths.get(f.getAbsolutePath()))) {
            String md5 = DigestUtils.md5Hex(is);
            impBitstream.setMd5value(md5);
        }

        if (embargoGroup != null) {
            impBitstream.setEmbargoPolicy(ImpBitstream.USE_GROUP);
            impBitstream.setEmbargoGroup(embargoGroup);
        }
        if (embargoStartDate != null) {
            impBitstream.setEmbargoStartDate(embargoStartDate);
        }
        return impBitstreamService.create(context, impBitstream);
    }

    /***
     * Create a Metadata of ImpRecord
     *
     * @param context                      The context
     * @param schema                       The schema
     * @param qualifier                    The qualifier
     * @param language                     The language
     * @param value                        The metadata value
     * @return
     * @throws SQLException
     */
    private ImpBitstreamMetadatavalue createImpBitstreamMetadatavalue(Context context, ImpBitstream impBitstream,
            String schema, String element, String qualifier, String language,
            String value) throws SQLException {
        ImpBitstreamMetadatavalue impBitstreamMetadatavalue = new ImpBitstreamMetadatavalue();
        impBitstreamMetadatavalue.setImpBitstreamMetadatavalueId(impBitstreamMetadatavalueSeq++);
        impBitstreamMetadatavalue.setImpBitstream(impBitstream);
        List<ImpBitstreamMetadatavalue> metadata = impBitstreamMetadatavalueService.searchByImpBitstream(context,
                impBitstream);
        impBitstreamMetadatavalueService.setMetadata(impBitstreamMetadatavalue, schema, element, qualifier, language,
                value);
        impBitstreamMetadatavalue.setMetadataOrder(metadata.size() + 1);

        return impBitstreamMetadatavalueService.create(context, impBitstreamMetadatavalue);
    }
}
