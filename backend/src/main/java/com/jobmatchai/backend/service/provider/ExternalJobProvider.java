package com.jobmatchai.backend.service.provider;

import java.util.List;

/**
 * A source of external job listings. Implement this interface as a @Component to plug in a new
 * job API (The Muse, USAJobs, ...) without changing ExternalJobService, ExternalJobController,
 * or the frontend - Spring auto-collects every ExternalJobProvider bean and ExternalJobService
 * queries all of them, so a new provider just needs to exist to be included.
 */
public interface ExternalJobProvider {
    List<ExternalJobData> fetchJobs(String keywords, String country, int maxResults);

    /**
     * Whether this provider's results actually vary based on the keywords argument. A provider
     * backed by a single fixed feed that ignores keywords entirely (see JobicyJobProvider)
     * should override this to false, so a multi-category import (see
     * ExternalJobService#importAllCategories) calls it once per cycle instead of hitting the
     * same endpoint for the same unchanging result set once per category.
     */
    default boolean usesKeywords() {
        return true;
    }
}
