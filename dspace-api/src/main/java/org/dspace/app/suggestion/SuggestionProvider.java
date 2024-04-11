/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.suggestion;

import java.util.List;
import java.util.UUID;

import org.dspace.core.Context;
import org.dspace.external.model.ExternalDataObject;

public interface SuggestionProvider {
    public List<SuggestionTarget> findAllTargets(Context context, int pageSize, long offset);

    public long countAllTargets(Context context);

    public SuggestionTarget findTarget(Context context, UUID target);

    public List<Suggestion> findAllUnprocessedSuggestions(Context context, UUID target, int pageSize, long offset,
            boolean ascending);

    public long countUnprocessedSuggestionByTarget(Context context, UUID target);

    public Suggestion findUnprocessedSuggestion(Context context, UUID target, String id);

    public void rejectSuggestion(Context context, UUID target, String idPart);

    public void flagRelatedSuggestionsAsProcessed(Context context, ExternalDataObject externalDataObject);

}
