/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authority.script;
import java.sql.SQLException;

import org.apache.commons.cli.Options;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.core.Context;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ScriptConfiguration} for the {@link UpdateItemReference}.
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk at 4science.it)
 */
public class UpdateItemReferenceScriptConfiguration<T extends UpdateItemReference>
        extends ScriptConfiguration<T> {

    private static final Logger log = LoggerFactory.getLogger(UpdateItemReferenceScriptConfiguration.class);

    private Class<T> dspaceRunnableClass;

    @Autowired
    private AuthorizeService authorizeService;

    @Override
    public boolean isAllowedToExecute(Context context) {
        try {
            return authorizeService.isAdmin(context);
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    @Override
    public Options getOptions() {
        if (options == null) {
            Options options = new Options();

            options.addOption("a", "all", false, "Default will search only archived items,"
                                    + " by providing this param will search also not archived items ");
            options.getOption("a").setType(Boolean.class);
            options.getOption("a").setRequired(false);

            super.options = options;
        }
        return options;
    }

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

}