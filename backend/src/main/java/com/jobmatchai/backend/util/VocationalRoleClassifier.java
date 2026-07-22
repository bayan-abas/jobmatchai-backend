package com.jobmatchai.backend.util;

import java.util.List;
import java.util.Locale;

// General/entry-level/vocational roles - ones that don't require specialized prior training, a
// degree, or domain-specific tools to perform (cashier, cleaner, delivery driver, etc.). Shared by
// JobMatchService (which exempts these from the profession-taxonomy hard-block gate and the
// education/experience scoring components, on the theory that almost any reliable adult can do
// one of these regardless of their specialized background) and by the job-listing payload (which
// uses the exact same classification to route these into a separate "General & Vocational Jobs"
// section instead of mixing them into profession-based match results) - one keyword list so a
// job's vocational-ness can never be judged differently by the two.
public final class VocationalRoleClassifier {

    private VocationalRoleClassifier() {}

    // Deliberately specific phrases rather than single ambiguous words - a bare "delivery",
    // "driver", "warehouse", or "stock" is a substring of plenty of skilled/senior titles
    // ("Director, Delivery", "Device Driver Engineer", "Warehouse Operations Manager", "Senior
    // Stock Analyst") that have nothing to do with an entry-level vocational role.
    private static final List<String> GENERAL_VOCATIONAL_ROLE_KEYWORDS = List.of(
            "cashier", "sales assistant", "sales associate", "cleaner", "cleaning",
            "warehouse associate", "warehouse worker", "delivery driver", "delivery associate",
            "food delivery", "waiter", "waitress", "barista",
            "security guard", "courier", "stock associate", "stock clerk", "retail assistant",
            "housekeeping", "kitchen porter", "dishwasher", "receptionist",
            "customer service representative"
    );

    // A leadership/strategic-scope title is never "anyone can do this regardless of background" -
    // overrides a keyword match above so a future keyword addition can't accidentally reintroduce
    // a false positive shaped like "Director, Delivery" or "Customer Service Manager".
    private static final List<String> SENIORITY_EXCLUSION_KEYWORDS = List.of(
            "director", "manager", "head of", " vp ", "vp,", "vice president", "chief",
            "principal", "executive", "president"
    );

    public static boolean isGeneralVocationalRole(String jobTitle) {
        if (jobTitle == null) {
            return false;
        }

        String lowerTitle = " " + jobTitle.toLowerCase(Locale.ROOT) + " ";

        if (SENIORITY_EXCLUSION_KEYWORDS.stream().anyMatch(lowerTitle::contains)) {
            return false;
        }

        return GENERAL_VOCATIONAL_ROLE_KEYWORDS.stream().anyMatch(lowerTitle::contains);
    }
}
