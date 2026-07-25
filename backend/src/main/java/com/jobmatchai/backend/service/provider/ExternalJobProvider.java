package com.jobmatchai.backend.service.provider;

import java.util.List;

public interface ExternalJobProvider {
    // כל ספק חיצוני (Jooble, JSearch, Jobicy) מממש את הממשק הזה ומחזיר משרות בפורמט אחיד למרות שה-API שלהם שונה
    List<ExternalJobData> fetchJobs(String keywords, String country, int maxResults);

    default boolean usesKeywords() {
        return true;
    }
}
