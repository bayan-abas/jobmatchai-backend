package com.jobmatchai.backend.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// A configurable profession registry backing JobMatchService's deterministic profession-
// compatibility gate - the highest-priority check in the matching pipeline, run BEFORE any AI
// call. Two roles are only "the same profession" if they resolve to the SAME node here; sharing
// a broad field/industry is deliberately NOT enough (that used to be scoreable as
// fieldRelationCloseness="same_broad_field" - found via real usage to produce misleading matches
// like a Software Engineer CV scoring against a QA Engineer posting, or a doctor CV against a
// nurse posting, just because both sides share an industry label or a few overlapping keywords).
//
// Each ProfessionNode is a real, distinct profession - siblings under the same `field` are
// DIFFERENT professions unless they're genuinely interchangeable phrasings of the same role (e.g.
// "Software Developer"/"Backend Engineer"/"Full Stack Developer" are all the SAME node here,
// since they're the same real job; "QA Engineer" is a DIFFERENT node from "Software Engineer"
// even though both are "technology" - they are different professions that happen to share a
// field, exactly the case this whole gate exists to stop from silently matching).
//
// Adding a new profession, alias, or fixing a miscategorization is a DATA change to the list
// below - the matching algorithm that walks this table never needs to change for it. Professions
// not covered here resolve to null (UNKNOWN) and JobMatchService falls back to its existing
// AI-judged fieldRelationCloseness for that pair, rather than guessing - this table does not need
// to be exhaustive to be safe, only to be correct where it does have an opinion. General/
// vocational roles (cashier, cleaner, security guard, etc.) are deliberately NOT modeled here at
// all - they're handled by JobMatchService's separate isGeneralVocationalRole override, which
// already makes them compatible with any candidate regardless of profession.
public final class ProfessionTaxonomy {

    public record ProfessionNode(String id, String displayName, String field, List<String> aliases) {
    }

    private static final List<ProfessionNode> NODES = List.of(
            // ---- Technology ----
            node("software_engineer", "Software Engineer", "technology", List.of(
                    "software engineer", "software developer", "backend engineer", "backend developer",
                    "frontend engineer", "frontend developer", "full stack developer", "full stack engineer",
                    "web developer", "mobile developer", "ios developer", "android developer",
                    "application developer", "programmer", "systems engineer software", "game developer")),
            node("qa_engineer", "QA / Test Engineer", "technology", List.of(
                    "qa engineer", "quality assurance engineer", "test engineer", "software tester",
                    "automation engineer", "sdet", "qa analyst", "quality assurance analyst",
                    "qa", "quality assurance", "test automation")),
            node("devops_engineer", "DevOps / Site Reliability Engineer", "technology", List.of(
                    "devops engineer", "site reliability engineer", "sre", "platform engineer",
                    "infrastructure engineer", "cloud engineer", "release engineer")),
            node("data_analyst", "Data Analyst", "technology", List.of(
                    "data analyst", "business intelligence analyst", "bi analyst", "reporting analyst")),
            node("data_scientist", "Data Scientist", "technology", List.of(
                    "data scientist", "machine learning engineer", "ml engineer", "ai engineer",
                    "research scientist ml")),
            node("data_engineer", "Data Engineer", "technology", List.of(
                    "data engineer", "etl developer", "big data engineer")),
            node("cybersecurity_engineer", "Cybersecurity Engineer", "technology", List.of(
                    "cybersecurity engineer", "security engineer", "information security analyst",
                    "penetration tester", "security analyst", "soc analyst")),
            node("it_support", "IT Support Specialist", "technology", List.of(
                    "it support", "technical support specialist", "help desk", "desktop support",
                    "it technician", "systems administrator", "network administrator")),
            node("network_engineer", "Network / Telecom Engineer", "technology", List.of(
                    "network engineer", "telecommunications engineer", "noc engineer", "rf engineer",
                    "field service engineer telecom")),
            node("product_manager_tech", "Product Manager", "technology", List.of(
                    "product manager", "technical product manager", "product owner")),
            node("ux_ui_designer", "UX/UI Designer", "technology", List.of(
                    "ux designer", "ui designer", "ux/ui designer", "product designer", "interaction designer")),

            // ---- Healthcare ----
            node("physician", "Physician / Doctor", "healthcare", List.of(
                    "physician", "doctor", "general practitioner", "gp", "medical doctor", "m.d.",
                    "attending physician", "resident physician", "surgeon")),
            node("registered_nurse", "Registered Nurse", "healthcare", List.of(
                    "registered nurse", "nurse", "rn", "nurse practitioner", "licensed practical nurse", "lpn")),
            node("pharmacist", "Pharmacist", "healthcare", List.of("pharmacist", "clinical pharmacist")),
            node("physical_therapist", "Physical Therapist", "healthcare", List.of(
                    "physical therapist", "physiotherapist", "pt")),
            node("dentist", "Dentist", "healthcare", List.of("dentist", "dental surgeon")),
            node("lab_technician", "Lab Technician", "healthcare", List.of(
                    "lab technician", "laboratory technician", "medical lab technologist", "phlebotomist")),
            node("paramedic", "Paramedic / EMT", "healthcare", List.of("paramedic", "emt", "emergency medical technician")),
            node("veterinarian", "Veterinarian", "healthcare", List.of("veterinarian", "vet")),
            node("occupational_therapist", "Occupational Therapist", "healthcare", List.of("occupational therapist")),
            node("healthcare_administrator", "Healthcare Administrator", "healthcare", List.of(
                    "healthcare administrator", "medical office manager", "hospital administrator")),
            node("medical_assistant", "Medical Assistant", "healthcare", List.of("medical assistant", "nursing assistant", "caregiver")),

            // ---- Finance ----
            node("accountant", "Accountant", "finance", List.of("accountant", "staff accountant", "cpa", "tax accountant")),
            node("financial_advisor", "Financial Advisor", "finance", List.of(
                    "financial advisor", "financial planner", "wealth advisor", "investment advisor")),
            node("auditor", "Auditor", "finance", List.of("auditor", "internal auditor", "compliance auditor")),
            node("financial_analyst", "Financial Analyst", "finance", List.of("financial analyst", "fp&a analyst", "investment analyst")),
            node("bank_teller", "Bank Teller", "finance", List.of("bank teller", "teller")),
            node("insurance_underwriter", "Insurance Underwriter", "finance", List.of("underwriter", "insurance underwriter")),
            node("actuary", "Actuary", "finance", List.of("actuary")),
            node("bookkeeper", "Bookkeeper", "finance", List.of("bookkeeper")),
            node("loan_officer", "Loan Officer", "finance", List.of("loan officer", "credit analyst")),
            node("risk_compliance_officer", "Risk / Compliance Officer", "finance", List.of(
                    "compliance officer", "risk officer", "regulatory compliance")),

            // ---- Legal & public safety ----
            node("lawyer", "Lawyer / Attorney", "legal", List.of("lawyer", "attorney", "legal counsel", "associate attorney")),
            node("paralegal", "Paralegal", "legal", List.of("paralegal", "legal assistant")),
            node("police_officer", "Police Officer", "public_safety", List.of("police officer", "law enforcement officer")),
            node("judge", "Judge", "legal", List.of("judge", "magistrate")),
            node("firefighter", "Firefighter", "public_safety", List.of("firefighter")),

            // ---- Education & social services ----
            node("teacher", "Teacher", "education", List.of("teacher", "instructor", "elementary teacher", "high school teacher")),
            node("school_counselor", "School Counselor", "education", List.of("school counselor", "guidance counselor")),
            node("social_worker", "Social Worker", "social_services", List.of("social worker", "case manager", "case worker")),
            node("special_education_teacher", "Special Education Teacher", "education", List.of("special education teacher", "sped teacher")),
            node("principal", "School Principal", "education", List.of("principal", "school administrator", "vice principal")),
            node("librarian", "Librarian", "education", List.of("librarian")),

            // ---- Engineering (non-software) ----
            node("mechanical_engineer", "Mechanical Engineer", "engineering", List.of("mechanical engineer")),
            node("civil_engineer", "Civil Engineer", "engineering", List.of("civil engineer", "structural engineer")),
            node("electrical_engineer_field", "Electrical Engineer", "engineering", List.of("electrical engineer", "power engineer")),
            node("industrial_engineer", "Industrial Engineer", "engineering", List.of("industrial engineer", "manufacturing engineer")),
            node("chemical_engineer", "Chemical Engineer", "engineering", List.of("chemical engineer", "process engineer")),
            node("aerospace_engineer", "Aerospace Engineer", "engineering", List.of("aerospace engineer")),

            // ---- Business / admin ----
            node("hr_manager", "Human Resources", "business", List.of(
                    "hr manager", "human resources", "hr generalist", "recruiter", "talent acquisition")),
            node("administrative_assistant", "Administrative Assistant", "business", List.of(
                    "administrative assistant", "office administrator", "executive assistant", "office manager")),
            node("project_manager_general", "Project / Program Manager", "business", List.of(
                    "project manager", "program manager", "operations manager")),
            node("business_analyst", "Business Analyst", "business", List.of("business analyst")),

            // ---- Sales & marketing ----
            node("sales_representative", "Sales Representative", "sales", List.of(
                    "sales representative", "sales executive", "account executive", "business development representative")),
            node("marketing_manager", "Marketing Manager", "marketing", List.of(
                    "marketing manager", "digital marketing manager", "marketing specialist", "growth marketer")),
            node("real_estate_agent", "Real Estate Agent", "real_estate", List.of("real estate agent", "realtor", "property agent")),
            node("account_manager", "Account Manager", "sales", List.of("account manager", "client relationship manager")),

            // ---- Hospitality & food service ----
            node("chef", "Chef / Cook", "hospitality", List.of("chef", "cook", "sous chef", "line cook")),
            node("event_planner", "Event Planner", "hospitality", List.of("event planner", "event coordinator")),
            node("hotel_manager", "Hotel Manager", "hospitality", List.of("hotel manager", "hospitality manager")),

            // ---- Skilled trades / construction ----
            node("electrician", "Electrician", "trades", List.of("electrician")),
            node("plumber", "Plumber", "trades", List.of("plumber")),
            node("hvac_technician", "HVAC Technician", "trades", List.of("hvac technician", "hvac installer")),
            node("welder", "Welder", "trades", List.of("welder")),
            node("carpenter", "Carpenter", "trades", List.of("carpenter")),
            node("construction_manager", "Construction Manager", "construction", List.of(
                    "construction manager", "site supervisor", "construction superintendent")),

            // ---- Transportation & logistics ----
            node("driver", "Driver", "transportation", List.of("driver", "truck driver", "delivery driver")),
            node("logistics_coordinator", "Logistics Coordinator", "logistics", List.of(
                    "logistics coordinator", "supply chain coordinator", "supply chain manager", "shipping coordinator")),
            node("pilot", "Pilot", "transportation", List.of("pilot", "airline pilot")),

            // ---- Manufacturing ----
            node("manufacturing_worker", "Manufacturing / Production Worker", "manufacturing", List.of(
                    "manufacturing worker", "production worker", "assembly line worker")),
            node("quality_control_inspector", "Quality Control Inspector", "manufacturing", List.of(
                    "quality control inspector", "qc inspector")),
            node("production_supervisor", "Production Supervisor", "manufacturing", List.of("production supervisor", "plant supervisor")),

            // ---- Customer service ----
            node("customer_service_representative", "Customer Service Representative", "customer_service", List.of(
                    "customer service representative", "customer support specialist", "client support representative")),

            // ---- Creative & media ----
            node("graphic_designer", "Graphic Designer", "creative", List.of("graphic designer", "visual designer")),
            node("translator", "Translator / Interpreter", "creative", List.of("translator", "interpreter")),
            node("journalist", "Journalist", "media", List.of("journalist", "reporter", "editor")),
            node("photographer", "Photographer", "creative", List.of("photographer")),
            node("video_editor", "Video Editor", "creative", List.of("video editor", "videographer"))
    );

    private static ProfessionNode node(String id, String displayName, String field, List<String> aliases) {
        return new ProfessionNode(id, displayName, field, aliases);
    }

    private ProfessionTaxonomy() {
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    // A handful of legitimate short acronyms that are otherwise excluded by the length-3 minimum
    // on single-word aliases (see below) - the minimum exists to stop generic short words from
    // causing false positives, but these specific acronyms are unambiguous professional terms.
    private static final Set<String> SHORT_ALIAS_EXCEPTIONS = Set.of("qa", "hr", "it");

    // Resolves free text (a profession title, a previous job title, a recommended role) to the
    // single BEST-matching node, or null if nothing in the taxonomy recognizably matches.
    //
    // Word-set matching, not substring matching: an alias matches when EVERY one of its own words
    // appears somewhere in the input's word set, regardless of order or words in between - found
    // necessary via real production data, where a plain substring check missed real postings like
    // "QA Automation Software Engineer" and "QA Backend Test Role" (neither contains the exact
    // phrase "qa engineer" or "automation engineer" as a contiguous substring, even though both
    // are obviously QA/testing roles to a human reader) - those slipped through the gate entirely
    // and scored a normal 56-58% match against a Senior Software Engineer CV, exactly the failure
    // mode this whole gate exists to close.
    //
    // Ranks candidate matches by (1) most alias words matched, then (2) the EARLIEST position any
    // of the alias's words appears in the input, tie-break lowest wins. (2) matters because a
    // title like "QA Automation Software Engineer" satisfies BOTH qa_engineer's "qa engineer" and
    // software_engineer's "software engineer" - two equally-long (2-word) aliases - and real job
    // titles conventionally lead with the most defining term ("QA" here, at position 0, vs
    // "Software" at position 2), so the earliest-appearing signal is the more reliable one.
    public static ProfessionNode resolve(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return null;
        }
        List<String> inputWordList = List.of(normalized.split(" "));

        ProfessionNode best = null;
        int bestAliasWordCount = 0;
        int bestEarliestPosition = Integer.MAX_VALUE;

        for (ProfessionNode candidate : NODES) {
            for (String alias : candidate.aliases()) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.isBlank()) {
                    continue;
                }
                String[] aliasWords = normalizedAlias.split(" ");
                if (aliasWords.length == 1 && aliasWords[0].length() < 3
                        && !SHORT_ALIAS_EXCEPTIONS.contains(aliasWords[0])) {
                    continue;
                }

                boolean allWordsPresent = true;
                int earliestPosition = Integer.MAX_VALUE;
                for (String word : aliasWords) {
                    int position = inputWordList.indexOf(word);
                    if (position < 0) {
                        allWordsPresent = false;
                        break;
                    }
                    earliestPosition = Math.min(earliestPosition, position);
                }
                if (!allWordsPresent) {
                    continue;
                }

                boolean isBetter = aliasWords.length > bestAliasWordCount
                        || (aliasWords.length == bestAliasWordCount && earliestPosition < bestEarliestPosition);
                if (isBetter) {
                    best = candidate;
                    bestAliasWordCount = aliasWords.length;
                    bestEarliestPosition = earliestPosition;
                }
            }
        }

        return best;
    }

    // Resolves every plausible profession the candidate might genuinely be qualified in - not
    // just the single primary professionTitle - mirroring the existing "a candidate can be
    // qualified in more than one field at once" support (see JobMatchService's prompt comments
    // on career changers / dual-qualified candidates). A job matching ANY one of these is
    // profession-compatible.
    public static Set<ProfessionNode> resolveAll(String professionTitle, String previousJobTitlesCsv, String recommendedRolesCsv) {
        Set<ProfessionNode> nodes = new LinkedHashSet<>();
        addIfResolved(nodes, professionTitle);
        for (String title : splitCsv(previousJobTitlesCsv)) {
            addIfResolved(nodes, title);
        }
        for (String role : splitCsv(recommendedRolesCsv)) {
            addIfResolved(nodes, role);
        }
        return nodes;
    }

    private static void addIfResolved(Set<ProfessionNode> nodes, String text) {
        ProfessionNode resolved = resolve(text);
        if (resolved != null) {
            nodes.add(resolved);
        }
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("[,;\\n]"));
    }
}
