package com.jobmatchai.backend.util;

import java.util.List;
import java.util.Locale;

public final class VocationalRoleClassifier {

    private VocationalRoleClassifier() {}

    private static final List<String> GENERAL_VOCATIONAL_ROLE_KEYWORDS = List.of(
            "cashier", "sales assistant", "sales associate", "cleaner", "cleaning",
            "warehouse associate", "warehouse worker", "delivery driver", "delivery associate",
            "food delivery", "waiter", "waitress", "barista",
            "security guard", "courier", "stock associate", "stock clerk", "retail assistant",
            "housekeeping", "kitchen porter", "dishwasher", "receptionist",
            "customer service representative"
    );

    private static final List<String> SENIORITY_EXCLUSION_KEYWORDS = List.of(
            "director", "manager", "head of", " vp ", "vp,", "vice president", "chief",
            "principal", "executive", "president"
    );

    // מזהה אם המשרה היא תפקיד כללי (כמו קופאי או מלצר) ולא תפקיד מקצועי ספציפי, כדי להקל על סף ההתאמה
    public static boolean isGeneralVocationalRole(String jobTitle) {
        if (jobTitle == null) {
            return false;
        }

        // ריפוד ברווחים כדי שההתאמה על " vp " תעבוד גם בקצוות המחרוזת
        String lowerTitle = " " + jobTitle.toLowerCase(Locale.ROOT) + " ";

        if (SENIORITY_EXCLUSION_KEYWORDS.stream().anyMatch(lowerTitle::contains)) {
            return false;
        }

        return GENERAL_VOCATIONAL_ROLE_KEYWORDS.stream().anyMatch(lowerTitle::contains);
    }
}
