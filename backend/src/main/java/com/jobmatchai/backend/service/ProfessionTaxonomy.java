package com.jobmatchai.backend.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// A configurable profession registry backing JobMatchService's deterministic profession-
// compatibility gate - the highest-priority check in the matching pipeline, run BEFORE any AI
// call. This is a HIERARCHICAL model, not a binary same/different one: two professions land in
// one of six tiers (see CompatibilityTier), because real careers aren't binary either - a
// Software Engineer applying to a QA Automation role is a meaningfully different situation from
// the same candidate applying to a Registered Nurse role, even though neither is their exact
// profession. Sharing a broad field/industry alone is still never enough on its own (that used to
// be scoreable as fieldRelationCloseness="same_broad_field" - found via real usage to produce
// misleading matches like a Software Engineer CV scoring against a QA Engineer posting just
// because both share an industry label) - every tier above UNRELATED here is an EXPLICIT,
// deliberate edge between two specific professions, never inferred from a shared `field` value.
//
// Adding a profession, alias, or relationship is a DATA change to the list below - the matching
// algorithm that walks this table never needs to change for it. Professions not covered here
// resolve to null/UNKNOWN and JobMatchService falls back to its existing AI-judged
// fieldRelationCloseness for that pair, rather than guessing - this table does not need to be
// exhaustive to be safe, only to be correct where it does have an opinion. General/vocational
// roles (cashier, cleaner, security guard, etc.) are deliberately NOT modeled here at all -
// they're handled by JobMatchService's separate isGeneralVocationalRole override, which already
// makes them compatible with any candidate regardless of profession.
public final class ProfessionTaxonomy {

    // Ordered BEST (most compatible) to WORST - see classifyBest, which picks the minimum ordinal
    // across every profession a candidate might resolve to.
    //
    // SAME_ROLE / "Same Specialization" from the product spec are deliberately collapsed into one
    // tier here: within a single taxonomy node, the finer same_role-vs-same_specialization
    // distinction (e.g. general Physician vs a specific specialty) is still made by the AI's own
    // judgment exactly as before - this taxonomy only needs to answer "is it even the same node,"
    // not re-implement that nuance.
    public enum CompatibilityTier {
        SAME_ROLE, CLOSELY_RELATED, RELATED, DIFFERENT_LICENSED_PROFESSION, UNRELATED, UNKNOWN
    }

    // requiresLicense: a formal legal/professional license or credential is required to practice
    // (medical license, bar admission, CPA, PE, etc.) - two DIFFERENT licensed professions are
    // never scoreable as a normal match regardless of how "related" they might otherwise seem
    // (a doctor is not a nurse, no matter how related medicine and nursing are), which is exactly
    // why this is checked independently of, and after, the closelyRelated/related edges below.
    //
    // closelyRelated / related: explicit edges to OTHER node ids reflecting genuine real-world
    // career adjacency/transferability - e.g. a Software Engineer and a QA Automation Engineer
    // share enough real skill overlap (both write code, both work in the SDLC) to be worth a
    // reduced-but-real score, which a Software Engineer and a Registered Nurse do not. These are
    // deliberately curated per pair, not inferred from sharing a `field` - two professions in the
    // same field are RELATED only if a real edge says so.
    public record ProfessionNode(
            String id, String displayName, String field, List<String> aliases,
            boolean requiresLicense, List<String> closelyRelatedIds, List<String> relatedIds) {
    }

    private static final List<ProfessionNode> NODES = List.of(
            // ---- Technology ----
            node("software_engineer", "Software Engineer", "technology", List.of(
                    "software engineer", "software developer", "application developer", "programmer",
                    "game developer", "systems engineer software"),
                    false,
                    List.of("backend_developer", "frontend_developer", "full_stack_developer", "mobile_developer",
                            "qa_automation_engineer"),
                    List.of("qa_engineer", "devops_engineer", "product_manager_tech", "data_engineer")),
            node("backend_developer", "Backend Developer", "technology", List.of(
                    "backend engineer", "backend developer", "server side developer"),
                    false,
                    List.of("software_engineer", "full_stack_developer", "qa_automation_engineer"),
                    List.of("devops_engineer")),
            node("frontend_developer", "Frontend Developer", "technology", List.of(
                    "frontend engineer", "frontend developer", "web developer", "ui developer"),
                    false,
                    List.of("software_engineer", "full_stack_developer"),
                    List.of("ux_ui_designer")),
            node("full_stack_developer", "Full Stack Developer", "technology", List.of(
                    "full stack developer", "full stack engineer"),
                    false,
                    List.of("software_engineer", "backend_developer", "frontend_developer"),
                    List.of()),
            node("mobile_developer", "Mobile Developer", "technology", List.of(
                    "mobile developer", "ios developer", "android developer"),
                    false,
                    List.of("software_engineer"),
                    List.of("frontend_developer")),
            node("qa_automation_engineer", "QA Automation Engineer", "technology", List.of(
                    "automation engineer", "sdet", "test automation engineer", "qa automation"),
                    false,
                    List.of("software_engineer", "backend_developer"),
                    List.of("qa_engineer", "devops_engineer")),
            node("qa_engineer", "QA / Test Engineer", "technology", List.of(
                    "qa engineer", "quality assurance engineer", "test engineer", "software tester",
                    "qa analyst", "quality assurance analyst", "qa", "quality assurance"),
                    false,
                    List.of(),
                    List.of("software_engineer", "qa_automation_engineer")),
            node("devops_engineer", "DevOps / Site Reliability Engineer", "technology", List.of(
                    "devops engineer", "site reliability engineer", "sre", "platform engineer",
                    "infrastructure engineer", "release engineer"),
                    false,
                    List.of("cloud_engineer"),
                    List.of("software_engineer", "backend_developer", "qa_automation_engineer", "network_engineer")),
            node("cloud_engineer", "Cloud Engineer", "technology", List.of(
                    "cloud engineer", "cloud architect", "cloud infrastructure engineer"),
                    false,
                    List.of("devops_engineer"),
                    List.of("network_engineer")),
            node("data_analyst", "Data Analyst", "technology", List.of(
                    "data analyst", "reporting analyst"),
                    false,
                    List.of("business_intelligence_analyst"),
                    List.of("data_scientist", "data_engineer", "financial_analyst", "business_analyst")),
            node("business_intelligence_analyst", "Business Intelligence Analyst", "technology", List.of(
                    "business intelligence analyst", "bi analyst", "bi developer"),
                    false,
                    List.of("data_analyst"),
                    List.of("data_engineer")),
            node("data_scientist", "Data Scientist", "technology", List.of(
                    "data scientist", "machine learning engineer", "ml engineer", "ai engineer",
                    "research scientist ml"),
                    false,
                    List.of(),
                    List.of("data_analyst", "data_engineer")),
            node("data_engineer", "Data Engineer", "technology", List.of(
                    "data engineer", "etl developer", "big data engineer"),
                    false,
                    List.of(),
                    List.of("data_analyst", "data_scientist", "business_intelligence_analyst", "software_engineer")),
            node("cybersecurity_engineer", "Cybersecurity Engineer", "technology", List.of(
                    "cybersecurity engineer", "security engineer", "information security analyst",
                    "penetration tester", "security analyst", "soc analyst"),
                    false,
                    List.of(),
                    List.of("it_support", "devops_engineer", "network_engineer")),
            node("it_support", "IT Support Specialist", "technology", List.of(
                    "it support", "technical support specialist", "help desk", "desktop support",
                    "it technician", "systems administrator", "network administrator"),
                    false,
                    List.of(),
                    List.of("network_engineer", "cybersecurity_engineer")),
            node("network_engineer", "Network / Telecom Engineer", "technology", List.of(
                    "network engineer", "telecommunications engineer", "noc engineer", "rf engineer",
                    "field service engineer telecom"),
                    false,
                    List.of(),
                    List.of("it_support", "cloud_engineer", "cybersecurity_engineer")),
            node("product_manager_tech", "Product Manager", "technology", List.of(
                    "product manager", "technical product manager", "product owner"),
                    false,
                    List.of(),
                    List.of("software_engineer", "business_analyst")),
            node("ux_ui_designer", "UX/UI Designer", "technology", List.of(
                    "ux designer", "ui designer", "ux/ui designer", "product designer", "interaction designer"),
                    false,
                    List.of(),
                    List.of("frontend_developer", "graphic_designer")),

            // ---- Healthcare (every node here requires a license to practice) ----
            node("physician", "Physician / Doctor", "healthcare", List.of(
                    "physician", "doctor", "general practitioner", "gp", "medical doctor", "m.d.",
                    "attending physician", "resident physician", "surgeon"),
                    true, List.of(), List.of()),
            node("registered_nurse", "Registered Nurse", "healthcare", List.of(
                    "registered nurse", "nurse", "rn", "nurse practitioner", "licensed practical nurse", "lpn"),
                    true, List.of(), List.of()),
            node("pharmacist", "Pharmacist", "healthcare", List.of("pharmacist", "clinical pharmacist"),
                    true, List.of(), List.of()),
            node("physical_therapist", "Physical Therapist", "healthcare", List.of(
                    "physical therapist", "physiotherapist"),
                    true, List.of(), List.of("occupational_therapist")),
            node("dentist", "Dentist", "healthcare", List.of("dentist", "dental surgeon"),
                    true, List.of(), List.of()),
            node("lab_technician", "Lab Technician", "healthcare", List.of(
                    "lab technician", "laboratory technician", "medical lab technologist", "phlebotomist"),
                    false, List.of(), List.of("medical_assistant")),
            node("paramedic", "Paramedic / EMT", "healthcare", List.of(
                    "paramedic", "emt", "emergency medical technician"),
                    true, List.of(), List.of("registered_nurse")),
            node("veterinarian", "Veterinarian", "healthcare", List.of("veterinarian", "vet"),
                    true, List.of(), List.of()),
            node("occupational_therapist", "Occupational Therapist", "healthcare", List.of("occupational therapist"),
                    true, List.of(), List.of("physical_therapist")),
            node("healthcare_administrator", "Healthcare Administrator", "healthcare", List.of(
                    "healthcare administrator", "medical office manager", "hospital administrator"),
                    false, List.of(), List.of("administrative_assistant")),
            node("medical_assistant", "Medical Assistant", "healthcare", List.of(
                    "medical assistant", "nursing assistant", "caregiver"),
                    false, List.of(), List.of("lab_technician")),

            // ---- Finance ----
            node("accountant", "Accountant", "finance", List.of(
                    "accountant", "staff accountant", "cpa", "tax accountant"),
                    true, List.of(), List.of("bookkeeper", "auditor")),
            node("financial_advisor", "Financial Advisor", "finance", List.of(
                    "financial advisor", "financial planner", "wealth advisor", "investment advisor"),
                    true, List.of(), List.of("financial_analyst")),
            node("auditor", "Auditor", "finance", List.of("auditor", "internal auditor", "compliance auditor"),
                    true, List.of(), List.of("accountant", "risk_compliance_officer")),
            node("financial_analyst", "Financial Analyst", "finance", List.of(
                    "financial analyst", "fp&a analyst", "investment analyst"),
                    false, List.of(), List.of("financial_advisor", "data_analyst", "business_analyst")),
            node("bank_teller", "Bank Teller", "finance", List.of("bank teller", "teller"),
                    false, List.of(), List.of("loan_officer")),
            node("insurance_underwriter", "Insurance Underwriter", "finance", List.of(
                    "underwriter", "insurance underwriter"),
                    false, List.of(), List.of("risk_compliance_officer")),
            node("actuary", "Actuary", "finance", List.of("actuary"), true, List.of(), List.of("financial_analyst")),
            node("bookkeeper", "Bookkeeper", "finance", List.of("bookkeeper"), false, List.of(), List.of("accountant")),
            node("loan_officer", "Loan Officer", "finance", List.of("loan officer", "credit analyst"),
                    false, List.of(), List.of("bank_teller", "financial_analyst")),
            node("risk_compliance_officer", "Risk / Compliance Officer", "finance", List.of(
                    "compliance officer", "risk officer", "regulatory compliance"),
                    false, List.of(), List.of("auditor", "insurance_underwriter")),

            // ---- Legal & public safety ----
            node("lawyer", "Lawyer / Attorney", "legal", List.of(
                    "lawyer", "attorney", "legal counsel", "associate attorney"),
                    true, List.of(), List.of("paralegal")),
            node("paralegal", "Paralegal", "legal", List.of("paralegal", "legal assistant"),
                    false, List.of(), List.of("lawyer")),
            node("police_officer", "Police Officer", "public_safety", List.of(
                    "police officer", "law enforcement officer"),
                    true, List.of(), List.of()),
            node("judge", "Judge", "legal", List.of("judge", "magistrate"), true, List.of(), List.of("lawyer")),
            node("firefighter", "Firefighter", "public_safety", List.of("firefighter"), true, List.of(), List.of()),

            // ---- Education & social services ----
            node("teacher", "Teacher", "education", List.of(
                    "teacher", "instructor", "elementary teacher", "high school teacher"),
                    true, List.of(), List.of("special_education_teacher", "tutor")),
            node("school_counselor", "School Counselor", "education", List.of(
                    "school counselor", "guidance counselor"),
                    true, List.of(), List.of("social_worker", "teacher")),
            node("social_worker", "Social Worker", "social_services", List.of(
                    "social worker", "case manager", "case worker"),
                    true, List.of(), List.of("school_counselor")),
            node("special_education_teacher", "Special Education Teacher", "education", List.of(
                    "special education teacher", "sped teacher"),
                    true, List.of("teacher"), List.of()),
            node("principal", "School Principal", "education", List.of(
                    "principal", "school administrator", "vice principal"),
                    true, List.of(), List.of("teacher")),
            node("librarian", "Librarian", "education", List.of("librarian"), false, List.of(), List.of()),
            node("tutor", "Tutor", "education", List.of("tutor"), false, List.of(), List.of("teacher")),

            // ---- Engineering (non-software) ----
            node("mechanical_engineer", "Mechanical Engineer", "engineering", List.of("mechanical engineer"),
                    true, List.of(), List.of("industrial_engineer")),
            node("civil_engineer", "Civil Engineer", "engineering", List.of("civil engineer", "structural engineer"),
                    true, List.of(), List.of()),
            node("electrical_engineer_field", "Electrical Engineer", "engineering", List.of(
                    "electrical engineer", "power engineer"),
                    true, List.of(), List.of()),
            node("industrial_engineer", "Industrial Engineer", "engineering", List.of(
                    "industrial engineer", "manufacturing engineer"),
                    false, List.of(), List.of("mechanical_engineer", "production_supervisor")),
            node("chemical_engineer", "Chemical Engineer", "engineering", List.of(
                    "chemical engineer", "process engineer"),
                    true, List.of(), List.of()),
            node("aerospace_engineer", "Aerospace Engineer", "engineering", List.of("aerospace engineer"),
                    true, List.of(), List.of("mechanical_engineer")),

            // ---- Business / admin ----
            node("hr_manager", "Human Resources", "business", List.of(
                    "hr manager", "human resources", "hr generalist", "recruiter", "talent acquisition"),
                    false, List.of(), List.of("administrative_assistant")),
            node("administrative_assistant", "Administrative Assistant", "business", List.of(
                    "administrative assistant", "office administrator", "executive assistant", "office manager"),
                    false, List.of(), List.of("hr_manager", "healthcare_administrator")),
            node("project_manager_general", "Project / Program Manager", "business", List.of(
                    "project manager", "program manager", "operations manager"),
                    false, List.of(), List.of("product_manager_tech", "business_analyst")),
            node("business_analyst", "Business Analyst", "business", List.of("business analyst"),
                    false, List.of(), List.of("data_analyst", "project_manager_general", "product_manager_tech")),

            // ---- Sales & marketing ----
            node("sales_representative", "Sales Representative", "sales", List.of(
                    "sales representative", "sales executive", "account executive",
                    "business development representative"),
                    false, List.of(), List.of("account_manager", "real_estate_agent")),
            node("marketing_manager", "Marketing Manager", "marketing", List.of(
                    "marketing manager", "digital marketing manager", "marketing specialist", "growth marketer"),
                    false, List.of(), List.of("sales_representative")),
            node("real_estate_agent", "Real Estate Agent", "real_estate", List.of(
                    "real estate agent", "realtor", "property agent"),
                    true, List.of(), List.of("sales_representative")),
            node("account_manager", "Account Manager", "sales", List.of(
                    "account manager", "client relationship manager"),
                    false, List.of(), List.of("sales_representative", "customer_service_representative")),

            // ---- Hospitality & food service ----
            node("chef", "Chef / Cook", "hospitality", List.of("chef", "cook", "sous chef", "line cook"),
                    false, List.of(), List.of()),
            node("event_planner", "Event Planner", "hospitality", List.of("event planner", "event coordinator"),
                    false, List.of(), List.of("hotel_manager")),
            node("hotel_manager", "Hotel Manager", "hospitality", List.of("hotel manager", "hospitality manager"),
                    false, List.of(), List.of("event_planner")),

            // ---- Skilled trades / construction ----
            node("electrician", "Electrician", "trades", List.of("electrician"), true, List.of(), List.of()),
            node("plumber", "Plumber", "trades", List.of("plumber"), true, List.of(), List.of()),
            node("hvac_technician", "HVAC Technician", "trades", List.of("hvac technician", "hvac installer"),
                    true, List.of(), List.of()),
            node("welder", "Welder", "trades", List.of("welder"), false, List.of(), List.of()),
            node("carpenter", "Carpenter", "trades", List.of("carpenter"), false, List.of(), List.of()),
            node("construction_manager", "Construction Manager", "construction", List.of(
                    "construction manager", "site supervisor", "construction superintendent"),
                    false, List.of(), List.of("civil_engineer")),

            // ---- Transportation & logistics ----
            node("driver", "Driver", "transportation", List.of("driver", "truck driver", "delivery driver"),
                    false, List.of(), List.of()),
            node("logistics_coordinator", "Logistics Coordinator", "logistics", List.of(
                    "logistics coordinator", "supply chain coordinator", "supply chain manager",
                    "shipping coordinator"),
                    false, List.of(), List.of()),
            node("pilot", "Pilot", "transportation", List.of("pilot", "airline pilot"), true, List.of(), List.of()),

            // ---- Manufacturing ----
            node("manufacturing_worker", "Manufacturing / Production Worker", "manufacturing", List.of(
                    "manufacturing worker", "production worker", "assembly line worker"),
                    false, List.of(), List.of()),
            node("quality_control_inspector", "Quality Control Inspector", "manufacturing", List.of(
                    "quality control inspector", "qc inspector"),
                    false, List.of(), List.of("manufacturing_worker")),
            node("production_supervisor", "Production Supervisor", "manufacturing", List.of(
                    "production supervisor", "plant supervisor"),
                    false, List.of(), List.of("industrial_engineer", "manufacturing_worker")),

            // ---- Customer service ----
            node("customer_service_representative", "Customer Service Representative", "customer_service", List.of(
                    "customer service representative", "customer support specialist",
                    "client support representative"),
                    false, List.of(), List.of("it_support", "account_manager")),

            // ---- Creative & media ----
            node("graphic_designer", "Graphic Designer", "creative", List.of("graphic designer", "visual designer"),
                    false, List.of(), List.of("ux_ui_designer")),
            node("translator", "Translator / Interpreter", "creative", List.of("translator", "interpreter"),
                    false, List.of(), List.of()),
            node("journalist", "Journalist", "media", List.of("journalist", "reporter", "editor"),
                    false, List.of(), List.of()),
            node("photographer", "Photographer", "creative", List.of("photographer"), false, List.of(), List.of()),
            node("video_editor", "Video Editor", "creative", List.of("video editor", "videographer"),
                    false, List.of(), List.of())
    );

    private static ProfessionNode node(
            String id, String displayName, String field, List<String> aliases,
            boolean requiresLicense, List<String> closelyRelatedIds, List<String> relatedIds) {
        return new ProfessionNode(id, displayName, field, aliases, requiresLicense, closelyRelatedIds, relatedIds);
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
    // are obviously QA/testing roles to a human reader).
    //
    // Ranks candidate matches by (1) most alias words matched, then (2) the EARLIEST position any
    // of the alias's words appears in the input, tie-break lowest wins - real job titles
    // conventionally lead with the most defining term (e.g. "QA" in "QA Automation Software
    // Engineer" at position 0, vs "Software" at position 2), so the earliest-appearing signal is
    // the more reliable one when two equally-long aliases both match.
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
    // on career changers / dual-qualified candidates).
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

    // The hierarchical classification between two SPECIFIC resolved nodes - see CompatibilityTier
    // for what each tier means. DIFFERENT_LICENSED_PROFESSION is checked BEFORE, and takes
    // ABSOLUTE priority over, the closely-related/related edges - a real, curated edge between two
    // professions can never overcome one of them requiring a DIFFERENT license than the other
    // (e.g. Physical Therapist and Occupational Therapist are genuinely closely related
    // disciplines with real skill overlap, but they are still two separate licenses, so this must
    // never be a normal, scored match, per the explicit product requirement that different
    // licensed professions never receive one - regardless of how related they otherwise are).
    // Some closelyRelated/related edges in the data above DO connect two licensed nodes (e.g.
    // Teacher -> Special Education Teacher) - this ordering is what makes those inert/unreachable
    // rather than a bug that needs hunting down edge-by-edge.
    public static CompatibilityTier classify(ProfessionNode candidate, ProfessionNode job) {
        if (candidate.id().equals(job.id())) {
            return CompatibilityTier.SAME_ROLE;
        }
        if (candidate.requiresLicense() && job.requiresLicense()) {
            return CompatibilityTier.DIFFERENT_LICENSED_PROFESSION;
        }
        if (candidate.closelyRelatedIds().contains(job.id()) || job.closelyRelatedIds().contains(candidate.id())) {
            return CompatibilityTier.CLOSELY_RELATED;
        }
        if (candidate.relatedIds().contains(job.id()) || job.relatedIds().contains(candidate.id())) {
            return CompatibilityTier.RELATED;
        }
        return CompatibilityTier.UNRELATED;
    }

    // The candidate may resolve to several professions at once (see resolveAll) - this picks the
    // MOST favorable (lowest-ordinal, per CompatibilityTier's declared best-to-worst order) tier
    // across all of them against the one job profession, so a candidate genuinely qualified in
    // more than one field is judged by whichever of their professions fits best.
    public static CompatibilityTier classifyBest(Set<ProfessionNode> candidateProfessions, ProfessionNode jobProfession) {
        CompatibilityTier best = CompatibilityTier.UNKNOWN;
        for (ProfessionNode candidateProfession : candidateProfessions) {
            CompatibilityTier tier = classify(candidateProfession, jobProfession);
            if (best == CompatibilityTier.UNKNOWN || tier.ordinal() < best.ordinal()) {
                best = tier;
            }
        }
        return best;
    }
}
