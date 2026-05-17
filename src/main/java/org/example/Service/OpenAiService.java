package org.example.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    private final WebClient webClient;
    private final String deploymentName;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String LOG_DIRECTORY = "logs";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Represents a single phase (PRINCE2) or sprint (Scrum) specification in the skeleton. */
    public record ItemSpec(String name, String description) {}

    public OpenAiService(
        @Value("${azure.openai.api-key}") String apiKey,
        @Value("${azure.openai.endpoint}") String endpoint,
        @Value("${azure.openai.deployment-name}") String deploymentName
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(endpoint)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.deploymentName = deploymentName;

        log.info("OpenAiService initialized with endpoint: {}", endpoint);
    }

    // ========== PUBLIC API ==========

    /**
     * Phase 1: Generate a lightweight skeleton (proposed phases + sprints with names and short descriptions).
     * The user can then edit, add, remove items and disable one methodology before calling generateProjectPlan.
     * Returns the raw JSON skeleton response — parsing is done by the caller.
     */
    public String generateSkeleton(String projectName, String projectDescription,
                                   String startDate, String deadline, String lang) {
        if (lang == null || lang.isBlank()) lang = "sk";
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        log.info("Generating SKELETON for: {} (lang={})", projectName, lang);

        String inputBlock = buildInputBlock(projectName, projectDescription, startDate, deadline, lang);
        String prompt = "en".equals(lang)
            ? buildSkeletonPromptEN(inputBlock)
            : buildSkeletonPrompt(inputBlock);

        String logName = String.format("request_%s_skeleton.log", timestamp);
        logToFile(logName, "=== REQUEST ===", prompt);

        String json = callOpenAiAsync(prompt, logName)
            .timeout(java.time.Duration.ofSeconds(90))
            .block();

        if (json == null || json.isBlank()) {
            throw new RuntimeException("OpenAI nevratil skeleton.");
        }
        return extractJsonBlock(json);
    }

    /**
     * Phase 2: Generate a full plan based on user-customized phases and sprints.
     * Either list may be null or empty to disable that methodology entirely.
     * Each list is capped at 10 items.
     */
    public String generateProjectPlan(String projectName, String projectDescription,
                                      String startDate, String deadline, String lang,
                                      List<ItemSpec> phases, List<ItemSpec> sprints) {
        if (lang == null || lang.isBlank()) lang = "sk";
        boolean hasPrince = phases != null && !phases.isEmpty();
        boolean hasScrum = sprints != null && !sprints.isEmpty();
        if (!hasPrince && !hasScrum) {
            throw new RuntimeException("Musi byt vybrana aspon jedna metodologia (PRINCE2 alebo Scrum).");
        }
        if (hasPrince && phases.size() > 10) {
            throw new RuntimeException("Maximum 10 faz pre PRINCE2.");
        }
        if (hasScrum && sprints.size() > 10) {
            throw new RuntimeException("Maximum 10 sprintov pre Scrum.");
        }

        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        log.info("Generating plan for: {} (phases={}, sprints={}, lang={})",
            projectName, hasPrince ? phases.size() : 0, hasScrum ? sprints.size() : 0, lang);

        String inputBlock = buildInputBlock(projectName, projectDescription, startDate, deadline, lang);
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dateFrom = (startDate != null && !startDate.isEmpty()) ? startDate : today;

        // Build monos (one per phase + one per sprint)
        List<Mono<String>> monos = new ArrayList<>();
        List<String> phaseLogs = new ArrayList<>();
        List<String> sprintLogs = new ArrayList<>();
        String[][] phaseDates = null;
        String[][] sprintDates = null;

        if (hasPrince) {
            phaseDates = calculateRangeDates(dateFrom, deadline, phases.size());
            for (int i = 0; i < phases.size(); i++) {
                ItemSpec p = phases.get(i);
                String prompt = "en".equals(lang)
                    ? buildPrince2PhasePromptEN(inputBlock, p, i + 1, phases.size(), phaseDates)
                    : buildPrince2PhasePrompt(inputBlock, p, i + 1, phases.size(), phaseDates);
                String logName = String.format("request_%s_prince2_phase%d.log", timestamp, i + 1);
                logToFile(logName, "=== REQUEST ===", prompt);
                phaseLogs.add(logName);
                monos.add(callOpenAiAsync(prompt, logName));
            }
        }

        if (hasScrum) {
            sprintDates = calculateRangeDates(dateFrom, deadline, sprints.size());
            for (int i = 0; i < sprints.size(); i++) {
                ItemSpec s = sprints.get(i);
                String prompt = "en".equals(lang)
                    ? buildScrumSprintPromptEN(inputBlock, s, i + 1, sprints.size(), sprintDates)
                    : buildScrumSprintPrompt(inputBlock, s, i + 1, sprints.size(), sprintDates);
                String logName = String.format("request_%s_scrum_sprint%d.log", timestamp, i + 1);
                logToFile(logName, "=== REQUEST ===", prompt);
                sprintLogs.add(logName);
                monos.add(callOpenAiAsync(prompt, logName));
            }
        }

        log.info("🚀 Odosielam {} paralelnych poziadaviek na AI ({} PRINCE2 faz + {} Scrum sprintov)...",
            monos.size(), hasPrince ? phases.size() : 0, hasScrum ? sprints.size() : 0);

        List<String> results = Mono.zip(monos, array -> {
                List<String> out = new ArrayList<>(array.length);
                for (Object o : array) out.add((String) o);
                return out;
            })
            .timeout(java.time.Duration.ofSeconds(240))
            .block();

        if (results == null) {
            throw new RuntimeException("OpenAI nevratil odpoved.");
        }

        // Split results back into prince and scrum lists
        List<String> phaseJsons = hasPrince ? new ArrayList<>(results.subList(0, phases.size())) : List.of();
        List<String> sprintJsons = hasScrum
            ? new ArrayList<>(results.subList(hasPrince ? phases.size() : 0, results.size()))
            : List.of();

        StringBuilder combined = new StringBuilder("<ProjectPlans>\n");
        if (hasPrince) {
            String princeXml = convertPrince2PhasesToXml(phaseJsons, projectName, projectDescription);
            combined.append(princeXml).append("\n");
        }
        if (hasScrum) {
            if (hasPrince) combined.append("\n");
            String scrumXml = convertScrumSprintsToXml(sprintJsons, projectName, projectDescription);
            combined.append(scrumXml).append("\n");
        }
        combined.append("</ProjectPlans>");

        String combinedXml = combined.toString();
        logToFile(String.format("request_%s_combined.log", timestamp), "=== COMBINED_XML ===", combinedXml);

        return combinedXml;
    }

    /**
     * Backward-compatible version — uses default 4 PRINCE2 phases + 3 Scrum sprints
     * with AI-proposed names via a skeleton call. Kept for any legacy callers/tests.
     */
    public String generateProjectPlan(String projectName, String projectDescription,
                                      String startDate, String deadline, String lang) {
        // Default legacy behaviour: 4 PRINCE2 phases + 3 Scrum sprints with generic placeholder names.
        // The user-facing flow now goes through generateSkeleton + generateProjectPlan(..., phases, sprints).
        List<ItemSpec> phases = List.of(
            new ItemSpec("Priprava a analyza", ""),
            new ItemSpec("Navrh a planovanie", ""),
            new ItemSpec("Realizacia", ""),
            new ItemSpec("Dokoncenie a nasadenie", "")
        );
        List<ItemSpec> sprints = List.of(
            new ItemSpec("Sprint 1 — zaciatok", ""),
            new ItemSpec("Sprint 2 — realizacia", ""),
            new ItemSpec("Sprint 3 — finalizacia", "")
        );
        return generateProjectPlan(projectName, projectDescription, startDate, deadline, lang, phases, sprints);
    }

    /** Splits [dateFrom, deadline] into N roughly equal periods. Returns [N][2] array of [start, end]. */
    // package-private for testing
    String[][] calculateRangeDates(String dateFrom, String deadline, int count) {
        if (count < 1) count = 1;
        LocalDate start = LocalDate.parse(dateFrom);
        LocalDate end;
        if (deadline != null && !deadline.isEmpty()) {
            end = LocalDate.parse(deadline);
        } else {
            end = start.plusMonths(6);
        }

        long totalDays = ChronoUnit.DAYS.between(start, end);
        if (totalDays < count * 7L) totalDays = count * 7L;
        long slice = Math.max(totalDays / count, 7);

        String[][] result = new String[count][2];
        LocalDate cursor = start;
        for (int i = 0; i < count; i++) {
            LocalDate next = (i == count - 1) ? end : cursor.plusDays(slice);
            result[i][0] = cursor.toString();
            result[i][1] = next.toString();
            cursor = next;
        }
        return result;
    }

    /** Legacy 3-sprint helper kept for tests. */
    String[][] calculateSprintDates(String dateFrom, String deadline) {
        return calculateRangeDates(dateFrom, deadline, 3);
    }

    // ========== INPUT BLOCK (shared) ==========

    private String buildInputBlock(String projectName, String projectDescription,
                                   String startDate, String deadline, String lang) {
        StringBuilder sb = new StringBuilder();
        boolean en = "en".equals(lang);
        sb.append(en ? "- Project name: " : "- Názov projektu: ").append(projectName).append("\n");
        sb.append(en ? "- Project description: " : "- Popis projektu: ").append(projectDescription).append("\n");

        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dateFrom = (startDate != null && !startDate.isEmpty()) ? startDate : today;
        sb.append(en ? "- Start date: " : "- Dátum začiatku: ").append(dateFrom).append("\n");

        if (deadline != null && !deadline.isEmpty())
            sb.append(en ? "- Deadline: " : "- Deadline: ").append(deadline).append("\n");
        return sb.toString();
    }

    // ========== SKELETON PROMPT ==========

    private String buildSkeletonPrompt(String inputBlock) {
        return String.format("""
                Si skúsený projektový manažér. Navrhni ŠTRUKTÚRU projektu pre dve metodológie — PRINCE2 a Scrum.
                NEGENERUJ detailné úlohy, riziká ani výstupy — iba návrh zoznamu fáz a šprintov s krátkym popisom.
                Vráť IBA platný JSON objekt. Žiadny text mimo JSON, žiadny Markdown.

                Vstupné údaje:
                %s
                Analyzuj typ projektu a navrhni realistickú štruktúru.
                Odporúčaný počet: 3–6 PRINCE2 fáz, 3–5 Scrum šprintov (maximum 10 v každej).

                Vráť JSON v tejto štruktúre:
                {
                  "prince2": [
                    { "name": "krátky názov fázy", "description": "1 veta – o čom je fáza" }
                  ],
                  "scrum": [
                    { "name": "krátky názov šprintu", "description": "1 veta – ciel šprintu" }
                  ]
                }

                Pravidlá:
                1. Fázy a šprinty musia logicky nadväzovať a pokrývať celý projekt od začiatku do konca.
                2. Názvy krátke a výstižné (2–5 slov), popisy maximálne 1 veta (do ~15 slov).
                3. Texty po slovensky.
                4. Žiadne placeholdery, žiadne "...".
                """, inputBlock);
    }

    private String buildSkeletonPromptEN(String inputBlock) {
        return String.format("""
                You are an experienced project manager. Propose the STRUCTURE of the project for two methodologies — PRINCE2 and Scrum.
                DO NOT generate detailed tasks, risks or outputs — only a list of phases and sprints with short descriptions.
                Return ONLY a valid JSON object. No text outside JSON, no Markdown.

                Input data:
                %s
                Analyze the project type and propose a realistic structure.
                Recommended counts: 3–6 PRINCE2 phases, 3–5 Scrum sprints (maximum 10 each).

                Return JSON in this structure:
                {
                  "prince2": [
                    { "name": "short phase name", "description": "1 sentence – what this phase covers" }
                  ],
                  "scrum": [
                    { "name": "short sprint name", "description": "1 sentence – sprint goal" }
                  ]
                }

                Rules:
                1. Phases and sprints must logically follow each other and cover the whole project from start to finish.
                2. Names short and concise (2–5 words), descriptions at most 1 sentence (~15 words).
                3. All texts in English.
                4. No placeholders, no "...".
                """, inputBlock);
    }

    // ========== PRINCE2 PHASE PROMPT (per-phase) ==========

    private String buildPrince2PhasePrompt(String inputBlock, ItemSpec phase, int phaseNum, int totalPhases, String[][] phaseDates) {
        String phaseName = phase.name() == null ? "" : phase.name();
        String phaseDesc = phase.description() == null ? "" : phase.description();
        return String.format("""
                Si certifikovaný PRINCE2 projektový manažér. Vráť IBA platný JSON objekt pre JEDNU fázu. Žiadny text mimo JSON, žiadny Markdown.

                Vstupné údaje:
                %s
                Toto je fáza %d z %d. Celkovo %d fáz pokrýva projekt od začiatku do konca.
                Názov TEJTO fázy (zadaný používateľom): %s
                Popis TEJTO fázy (zadaný používateľom): %s
                Dátumový rozsah TEJTO fázy: %s až %s

                DÔLEŽITÉ: Analyzuj popis projektu a vygeneruj obsah fázy, ktorý zodpovedá REÁLNEMU typu podnikania/projektu.
                Ak ide o softvérový projekt — úlohy a riziká o analýze, vývoji, testovaní, nasadení.
                Ak ide o fyzický podnik (kaviareň, obchod, reštauráciu) — úlohy o priestoroch, vybavení, dodávateľoch, personáli, marketingu.
                Ak ide o iný typ — prispôsob obsah reálnemu kontextu.

                Vráť JSON IBA pre túto fázu:
                {
                  "name": "%s",
                  "description": "podrobný opis práce v tejto fáze",
                  "dueDate": "%s",
                  "priority": "High|Medium|Low",
                  "labels": ["tag1","tag2"],
                  "component": "kategória práce relevantná pre tento projekt",
                  "originalEstimate": "2w",
                  "outputs": ["merateľný výstup 1", "výstup 2"],
                  "risks": ["riziko a mitigácia 1", "riziko 2"],
                  "tasks": [
                    {
                      "title": "názov úlohy (summary sub-tasku v Jira)",
                      "description": "čo presne treba urobiť",
                      "priority": "High|Medium|Low",
                      "dueDate": "YYYY-MM-DD",
                      "labels": ["tag1"],
                      "originalEstimate": "1d"
                    }
                  ]
                }

                Povinné pravidlá:
                1. Názov fázy (name) zachovaj presne podľa zadania používateľa.
                2. Dátumy v rozsahu %s — %s, formát YYYY-MM-DD.
                3. Min. 2 outputs, 2 risks, 3 tasks v tejto fáze.
                4. originalEstimate: Jira formát — iba celé čísla: "1w", "1w 2d", "3d", "4h". NIKDY nepoužívaj desatinné čísla ako "1.5w".
                5. component: vyber kategóriu relevantnú pre projekt (napr. IT: Backend, Frontend, DevOps; Podnik: Priestory, Marketing, Personál, Logistika, Financie, Prevádzka).
                6. Texty po slovensky, žiadne placeholdery, žiadne "...".
                7. Úlohy, riziká a výstupy musia byť KONKRÉTNE a REALISTICKÉ pre daný typ projektu.
                """,
            inputBlock,
            phaseNum, totalPhases, totalPhases,
            phaseName,
            phaseDesc,
            phaseDates[phaseNum - 1][0], phaseDates[phaseNum - 1][1],
            phaseName,
            phaseDates[phaseNum - 1][1],
            phaseDates[phaseNum - 1][0], phaseDates[phaseNum - 1][1]);
    }

    private String buildPrince2PhasePromptEN(String inputBlock, ItemSpec phase, int phaseNum, int totalPhases, String[][] phaseDates) {
        String phaseName = phase.name() == null ? "" : phase.name();
        String phaseDesc = phase.description() == null ? "" : phase.description();
        return String.format("""
                You are a certified PRINCE2 project manager. Return ONLY a valid JSON object for ONE phase. No text outside JSON, no Markdown.

                Input data:
                %s
                This is phase %d of %d. Total %d phases cover the project from start to finish.
                Name of THIS phase (provided by user): %s
                Description of THIS phase (provided by user): %s
                Date range of THIS phase: %s to %s

                IMPORTANT: Analyze the project description and generate phase content that matches the REAL type of business/project.
                If it is a software project — tasks and risks about analysis, development, testing, deployment.
                If it is a physical business (cafe, shop, restaurant) — tasks about premises, equipment, suppliers, staff, marketing.
                If it is another type — adapt content to the real context.

                Return JSON ONLY for this phase:
                {
                  "name": "%s",
                  "description": "detailed description of work in this phase",
                  "dueDate": "%s",
                  "priority": "High|Medium|Low",
                  "labels": ["tag1","tag2"],
                  "component": "work category relevant to this project",
                  "originalEstimate": "2w",
                  "outputs": ["measurable output 1", "output 2"],
                  "risks": ["risk and mitigation 1", "risk 2"],
                  "tasks": [
                    {
                      "title": "task name (Jira sub-task summary)",
                      "description": "what exactly needs to be done",
                      "priority": "High|Medium|Low",
                      "dueDate": "YYYY-MM-DD",
                      "labels": ["tag1"],
                      "originalEstimate": "1d"
                    }
                  ]
                }

                Mandatory rules:
                1. Keep the phase name (name field) exactly as provided by the user.
                2. Dates in range %s — %s, format YYYY-MM-DD.
                3. Min. 2 outputs, 2 risks, 3 tasks in this phase.
                4. originalEstimate: Jira format — whole numbers only: "1w", "1w 2d", "3d", "4h". NEVER use decimals like "1.5w".
                5. component: choose a category relevant to the project (e.g. IT: Backend, Frontend, DevOps; Business: Premises, Marketing, Staff, Logistics, Finance, Operations).
                6. All texts in English, no placeholders, no "...".
                7. Tasks, risks and outputs must be SPECIFIC and REALISTIC for this project type.
                """,
            inputBlock,
            phaseNum, totalPhases, totalPhases,
            phaseName,
            phaseDesc,
            phaseDates[phaseNum - 1][0], phaseDates[phaseNum - 1][1],
            phaseName,
            phaseDates[phaseNum - 1][1],
            phaseDates[phaseNum - 1][0], phaseDates[phaseNum - 1][1]);
    }

    // ========== SCRUM SPRINT PROMPT (per-sprint, user-customized) ==========

    private String buildScrumSprintPrompt(String inputBlock, ItemSpec sprint, int sprintNum, int totalSprints, String[][] sprintDates) {
        String sprintName = sprint.name() == null ? "" : sprint.name();
        String sprintDesc = sprint.description() == null ? "" : sprint.description();
        return String.format("""
                Si skúsený Scrum master. Vráť IBA platný JSON objekt pre JEDEN šprint. Žiadny text mimo JSON, žiadny Markdown.

                Vstupné údaje:
                %s
                Toto je šprint %d z %d. Celkovo %d šprintov pokrýva projekt od začiatku do konca.
                Názov TOHTO šprintu (zadaný používateľom): %s
                Popis TOHTO šprintu (zadaný používateľom): %s
                Dátumový rozsah TOHTO šprintu: %s až %s

                DÔLEŽITÉ: Analyzuj popis projektu a vytvor šprint, ktorý zodpovedá REÁLNEMU typu tohto podnikania/projektu.
                Ak ide o softvérový projekt — epiky a stories o vývoji, testovaní, nasadení.
                Ak ide o fyzický podnik (kaviareň, obchod) — epiky o priestoroch, vybavení, dodávateľoch, personáli, marketingu, otvorení.
                Ak ide o iný typ — prispôsob obsah reálnemu kontextu.

                Vráť JSON pre JEDEN šprint:
                {
                  "name": "%s",
                  "goal": "konkrétny cieľ šprintu",
                  "description": "stručný plán práce",
                  "dueDate": "%s",
                  "priority": "High|Medium|Low",
                  "labels": ["scrum","sprint%d"],
                  "risks": ["riziko a mitigácia 1", "riziko 2"],
                  "epics": [
                    {
                      "title": "názov epiku (summary v Jira)",
                      "description": "opis biznis hodnoty",
                      "priority": "High|Medium|Low",
                      "labels": ["epic","kategória"],
                      "component": "kategória relevantná pre projekt",
                      "stories": [
                        {
                          "title": "názov user story (summary v Jira)",
                          "description": "opis potreby používateľa alebo podnikateľa",
                          "storyPoints": 5,
                          "priority": "High|Medium|Low",
                          "dueDate": "YYYY-MM-DD",
                          "labels": ["feature"],
                          "originalEstimate": "3d",
                          "acceptanceCriteria": ["kritérium 1", "kritérium 2", "kritérium 3"],
                          "subTasks": [
                            {
                              "title": "názov sub-tasku",
                              "description": "čo treba urobiť",
                              "priority": "High|Medium|Low",
                              "originalEstimate": "4h"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }

                Pravidlá:
                1. Názov šprintu (name) zachovaj presne podľa zadania používateľa.
                2. Presne 2 epiky, každý s 2-3 user stories.
                3. Každá story: 3 acceptanceCriteria, 2 subTasks.
                4. storyPoints: Fibonacci (1, 2, 3, 5, 8, 13).
                5. originalEstimate: Jira formát — iba celé čísla: "1w", "1w 2d", "3d", "4h". NIKDY nepoužívaj desatinné čísla ako "1.5w".
                6. Dátumy v rozsahu %s — %s.
                7. component: relevantná kategória pre projekt (IT: Backend, Frontend; Podnik: Priestory, Personál, Marketing, Logistika, Financie).
                8. Texty po slovensky, kompletné — žiadne "..." alebo placeholdery.
                """,
            inputBlock,
            sprintNum, totalSprints, totalSprints,
            sprintName,
            sprintDesc,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1],
            sprintName,
            sprintDates[sprintNum - 1][1],
            sprintNum,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1]);
    }

    private String buildScrumSprintPromptEN(String inputBlock, ItemSpec sprint, int sprintNum, int totalSprints, String[][] sprintDates) {
        String sprintName = sprint.name() == null ? "" : sprint.name();
        String sprintDesc = sprint.description() == null ? "" : sprint.description();
        return String.format("""
                You are an experienced Scrum master. Return ONLY a valid JSON object for ONE sprint. No text outside JSON, no Markdown.

                Input data:
                %s
                This is sprint %d of %d. Total %d sprints cover the project from start to finish.
                Name of THIS sprint (provided by user): %s
                Description of THIS sprint (provided by user): %s
                Date range of THIS sprint: %s to %s

                IMPORTANT: Analyze the project description and create a sprint that matches the REAL type of this business/project.
                If it is a software project — epics and stories about development, testing, deployment.
                If it is a physical business (cafe, shop) — epics about premises, equipment, suppliers, staff, marketing, opening.
                If it is another type — adapt content to the real context.

                Return JSON for ONE sprint:
                {
                  "name": "%s",
                  "goal": "specific sprint goal",
                  "description": "brief work plan",
                  "dueDate": "%s",
                  "priority": "High|Medium|Low",
                  "labels": ["scrum","sprint%d"],
                  "risks": ["risk and mitigation 1", "risk 2"],
                  "epics": [
                    {
                      "title": "epic name (Jira summary)",
                      "description": "business value description",
                      "priority": "High|Medium|Low",
                      "labels": ["epic","category"],
                      "component": "category relevant to the project",
                      "stories": [
                        {
                          "title": "user story name (Jira summary)",
                          "description": "user or business need description",
                          "storyPoints": 5,
                          "priority": "High|Medium|Low",
                          "dueDate": "YYYY-MM-DD",
                          "labels": ["feature"],
                          "originalEstimate": "3d",
                          "acceptanceCriteria": ["criterion 1", "criterion 2", "criterion 3"],
                          "subTasks": [
                            {
                              "title": "sub-task name",
                              "description": "what needs to be done",
                              "priority": "High|Medium|Low",
                              "originalEstimate": "4h"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }

                Rules:
                1. Keep the sprint name (name field) exactly as provided by the user.
                2. Exactly 2 epics, each with 2-3 user stories.
                3. Each story: 3 acceptanceCriteria, 2 subTasks.
                4. storyPoints: Fibonacci (1, 2, 3, 5, 8, 13).
                5. originalEstimate: Jira format — whole numbers only: "1w", "1w 2d", "3d", "4h". NEVER use decimals like "1.5w".
                6. Dates in range %s — %s.
                7. component: relevant category for the project (IT: Backend, Frontend; Business: Premises, Staff, Marketing, Logistics, Finance).
                8. All texts in English, complete — no "..." or placeholders.
                """,
            inputBlock,
            sprintNum, totalSprints, totalSprints,
            sprintName,
            sprintDesc,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1],
            sprintName,
            sprintDates[sprintNum - 1][1],
            sprintNum,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1]);
    }

    // ========== JSON → XML CONVERSION ==========

    /** Combines multiple PRINCE2 phase JSON responses into one PRINCE2Project XML. */
    // package-private for testing
    String convertPrince2PhasesToXml(List<String> phaseJsons, String projectName, String projectDescription) {
        StringBuilder xml = new StringBuilder();
        xml.append("<PRINCE2Project>\n");
        xml.append("  <Name>").append(xmlEscape(projectName)).append("</Name>\n");
        xml.append("  <Description>").append(xmlEscape(projectDescription)).append("</Description>\n");
        xml.append("  <Stages>\n");

        for (int i = 0; i < phaseJsons.size(); i++) {
            try {
                String jsonBlock = extractJsonBlock(phaseJsons.get(i));
                JsonNode stage = objectMapper.readTree(jsonBlock);
                appendStageXml(xml, stage);
                log.info("✅ PRINCE2 Phase {} JSON → XML OK", i + 1);
            } catch (Exception e) {
                log.error("❌ PRINCE2 Phase {} JSON→XML chyba: {}", i + 1, e.getMessage());
                xml.append("    <Stage>\n");
                xml.append("      <Name>Faza ").append(i + 1).append(" (chyba generovania)</Name>\n");
                xml.append("      <Description>Chyba: ").append(xmlEscape(e.getMessage())).append("</Description>\n");
                xml.append("      <Tasks>\n      </Tasks>\n");
                xml.append("    </Stage>\n");
            }
        }

        xml.append("  </Stages>\n");
        xml.append("</PRINCE2Project>");

        log.info("✅ PRINCE2 combined XML ({} znakov)", xml.length());
        return xml.toString();
    }

    /** Legacy single-response PRINCE2 conversion, kept for tests. */
    // package-private for testing
    String convertPrince2JsonToXml(String jsonResponse) {
        try {
            String jsonBlock = extractJsonBlock(jsonResponse);
            JsonNode root = objectMapper.readTree(jsonBlock);

            StringBuilder xml = new StringBuilder();
            xml.append("<PRINCE2Project>\n");
            xml.append("  <Name>").append(esc(root, "name")).append("</Name>\n");
            xml.append("  <Description>").append(esc(root, "description")).append("</Description>\n");
            xml.append("  <Stages>\n");

            JsonNode stages = root.get("stages");
            if (stages != null && stages.isArray()) {
                for (JsonNode stage : stages) appendStageXml(xml, stage);
            }

            xml.append("  </Stages>\n");
            xml.append("</PRINCE2Project>");

            log.info("✅ PRINCE2 JSON → XML ({} znakov)", xml.length());
            return xml.toString();

        } catch (Exception e) {
            log.error("❌ PRINCE2 JSON→XML chyba: {}", e.getMessage(), e);
            log.warn("⚠️ Fallback: hľadám XML priamo");
            return extractXmlSection(jsonResponse, "PRINCE2Project");
        }
    }

    /** Appends a single PRINCE2 stage/phase JSON node as XML. */
    private void appendStageXml(StringBuilder xml, JsonNode stage) {
        xml.append("    <Stage>\n");
        xml.append("      <Name>").append(esc(stage, "name")).append("</Name>\n");
        xml.append("      <Description>").append(esc(stage, "description")).append("</Description>\n");
        xml.append("      <DueDate>").append(esc(stage, "dueDate")).append("</DueDate>\n");
        xml.append("      <Priority>").append(esc(stage, "priority")).append("</Priority>\n");
        xml.append("      <Labels>").append(joinArray(stage, "labels")).append("</Labels>\n");
        xml.append("      <Component>").append(esc(stage, "component")).append("</Component>\n");
        xml.append("      <OriginalEstimate>").append(esc(stage, "originalEstimate")).append("</OriginalEstimate>\n");

        xml.append("      <Outputs>\n");
        appendXmlArray(xml, stage, "outputs", "Output", "        ");
        xml.append("      </Outputs>\n");

        xml.append("      <Risks>\n");
        appendXmlArray(xml, stage, "risks", "Risk", "        ");
        xml.append("      </Risks>\n");

        xml.append("      <Tasks>\n");
        JsonNode tasks = stage.get("tasks");
        if (tasks != null && tasks.isArray()) {
            for (JsonNode t : tasks) {
                xml.append("        <Task>\n");
                xml.append("          <Title>").append(esc(t, "title")).append("</Title>\n");
                xml.append("          <Description>").append(esc(t, "description")).append("</Description>\n");
                xml.append("          <Priority>").append(esc(t, "priority")).append("</Priority>\n");
                xml.append("          <DueDate>").append(esc(t, "dueDate")).append("</DueDate>\n");
                xml.append("          <Labels>").append(joinArray(t, "labels")).append("</Labels>\n");
                xml.append("          <OriginalEstimate>").append(esc(t, "originalEstimate")).append("</OriginalEstimate>\n");
                xml.append("        </Task>\n");
            }
        }
        xml.append("      </Tasks>\n");
        xml.append("    </Stage>\n");
    }

    /** Combines N separate sprint JSON responses into one ScrumProject XML. */
    // package-private for testing
    String convertScrumSprintsToXml(List<String> sprintJsons, String projectName, String projectDescription) {
        StringBuilder xml = new StringBuilder();
        xml.append("<ScrumProject>\n");
        xml.append("  <Name>").append(xmlEscape(projectName)).append("</Name>\n");
        xml.append("  <Description>").append(xmlEscape(projectDescription)).append("</Description>\n");
        xml.append("  <Sprints>\n");

        for (int i = 0; i < sprintJsons.size(); i++) {
            try {
                String jsonBlock = extractJsonBlock(sprintJsons.get(i));
                JsonNode sprint = objectMapper.readTree(jsonBlock);
                appendSprintXml(xml, sprint);
                log.info("✅ Scrum Sprint {} JSON → XML OK", i + 1);
            } catch (Exception e) {
                log.error("❌ Scrum Sprint {} JSON→XML chyba: {}", i + 1, e.getMessage());
                xml.append("    <Sprint>\n");
                xml.append("      <Name>Šprint ").append(i + 1).append(" (chyba generovania)</Name>\n");
                xml.append("      <Goal>Chyba: ").append(xmlEscape(e.getMessage())).append("</Goal>\n");
                xml.append("      <Epics>\n      </Epics>\n");
                xml.append("    </Sprint>\n");
            }
        }

        xml.append("  </Sprints>\n");
        xml.append("</ScrumProject>");

        log.info("✅ Scrum combined XML ({} znakov)", xml.length());
        return xml.toString();
    }

    /** Legacy 3-sprint overload kept for tests. */
    String convertScrumSprintsToXml(String s1, String s2, String s3, String projectName, String projectDescription) {
        return convertScrumSprintsToXml(Arrays.asList(s1, s2, s3), projectName, projectDescription);
    }

    /** Appends a single sprint JSON node as XML. */
    private void appendSprintXml(StringBuilder xml, JsonNode sprint) {
        xml.append("    <Sprint>\n");
        xml.append("      <Name>").append(esc(sprint, "name")).append("</Name>\n");
        xml.append("      <Goal>").append(esc(sprint, "goal")).append("</Goal>\n");
        xml.append("      <Description>").append(esc(sprint, "description")).append("</Description>\n");
        xml.append("      <DueDate>").append(esc(sprint, "dueDate")).append("</DueDate>\n");
        xml.append("      <Priority>").append(esc(sprint, "priority")).append("</Priority>\n");
        xml.append("      <Labels>").append(joinArray(sprint, "labels")).append("</Labels>\n");

        xml.append("      <Risks>\n");
        appendXmlArray(xml, sprint, "risks", "Risk", "        ");
        xml.append("      </Risks>\n");

        xml.append("      <Epics>\n");
        JsonNode epics = sprint.get("epics");
        if (epics != null && epics.isArray()) {
            for (JsonNode epic : epics) {
                xml.append("        <Epic>\n");
                xml.append("          <Title>").append(esc(epic, "title")).append("</Title>\n");
                xml.append("          <Description>").append(esc(epic, "description")).append("</Description>\n");
                xml.append("          <Priority>").append(esc(epic, "priority")).append("</Priority>\n");
                xml.append("          <Labels>").append(joinArray(epic, "labels")).append("</Labels>\n");
                xml.append("          <Component>").append(esc(epic, "component")).append("</Component>\n");

                xml.append("          <Stories>\n");
                JsonNode stories = epic.get("stories");
                if (stories != null && stories.isArray()) {
                    for (JsonNode story : stories) {
                        xml.append("            <Story>\n");
                        xml.append("              <Title>").append(esc(story, "title")).append("</Title>\n");
                        xml.append("              <Description>").append(esc(story, "description")).append("</Description>\n");
                        xml.append("              <StoryPoints>").append(esc(story, "storyPoints")).append("</StoryPoints>\n");
                        xml.append("              <Priority>").append(esc(story, "priority")).append("</Priority>\n");
                        xml.append("              <DueDate>").append(esc(story, "dueDate")).append("</DueDate>\n");
                        xml.append("              <Labels>").append(joinArray(story, "labels")).append("</Labels>\n");
                        xml.append("              <OriginalEstimate>").append(esc(story, "originalEstimate")).append("</OriginalEstimate>\n");

                        xml.append("              <AcceptanceCriteria>\n");
                        appendXmlArray(xml, story, "acceptanceCriteria", "Criterion", "                ");
                        xml.append("              </AcceptanceCriteria>\n");

                        xml.append("              <SubTasks>\n");
                        JsonNode subTasks = story.get("subTasks");
                        if (subTasks != null && subTasks.isArray()) {
                            for (JsonNode st : subTasks) {
                                xml.append("                <Task>\n");
                                xml.append("                  <Title>").append(esc(st, "title")).append("</Title>\n");
                                xml.append("                  <Description>").append(esc(st, "description")).append("</Description>\n");
                                xml.append("                  <Priority>").append(esc(st, "priority")).append("</Priority>\n");
                                xml.append("                  <OriginalEstimate>").append(esc(st, "originalEstimate")).append("</OriginalEstimate>\n");
                                xml.append("                </Task>\n");
                            }
                        }
                        xml.append("              </SubTasks>\n");
                        xml.append("            </Story>\n");
                    }
                }
                xml.append("          </Stories>\n");
                xml.append("        </Epic>\n");
            }
        }
        xml.append("      </Epics>\n");
        xml.append("    </Sprint>\n");
    }

    // ========== HELPERS ==========

    // package-private for testing
    String extractJsonBlock(String response) {
        if (response == null || response.isBlank()) {
            throw new RuntimeException("AI vrátil prázdnu odpoveď.");
        }

        String trimmed = response.trim();

        if (trimmed.contains("```")) {
            Pattern p = Pattern.compile("```(?:json)?\\s*\\n?(\\{.*?})\\s*```", Pattern.DOTALL);
            Matcher m = p.matcher(trimmed);
            if (m.find()) return m.group(1).trim();
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);

        throw new RuntimeException("Nepodarilo sa nájsť JSON v odpovedi AI.");
    }

    private String esc(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return "";
        return xmlEscape(node.get(field).asText(""));
    }

    private String escVal(JsonNode node) {
        if (node == null || node.isNull()) return "";
        return xmlEscape(node.asText(""));
    }

    private String joinArray(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : node.get(field)) {
            if (sb.length() > 0) sb.append(",");
            sb.append(item.asText(""));
        }
        return xmlEscape(sb.toString());
    }

    private void appendXmlArray(StringBuilder xml, JsonNode parent, String jsonField, String xmlTag, String indent) {
        JsonNode arr = parent.get(jsonField);
        if (arr != null && arr.isArray()) {
            for (JsonNode item : arr) {
                xml.append(indent).append("<").append(xmlTag).append(">").append(escVal(item))
                   .append("</").append(xmlTag).append(">\n");
            }
        }
    }

    // package-private for testing
    String xmlEscape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String extractXmlSection(String content, String rootTag) {
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("AI vrátil prázdny obsah pre " + rootTag);
        }
        Pattern pattern = Pattern.compile(String.format("(?is)<%1$s\\b.*?</%1$s>", rootTag));
        Matcher matcher = pattern.matcher(content.trim());
        if (!matcher.find()) {
            throw new RuntimeException("Výstup neobsahuje tag <" + rootTag + "> ani platný JSON.");
        }
        return matcher.group().trim();
    }

    // ========== OPENAI API CALL (ASYNC) ==========

    private Mono<String> callOpenAiAsync(String prompt, String logFileName) {
        Map<String, Object> body = Map.of(
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 16000
        );

        return webClient.post()
            .uri("/openai/deployments/" + deploymentName + "/chat/completions?api-version=2025-01-01-preview")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
            .timeout(java.time.Duration.ofSeconds(120))
            .map(response -> {
                if (response == null) {
                    throw new RuntimeException("OpenAI vrátil prázdnu odpoveď.");
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new RuntimeException("OpenAI odpoveď neobsahuje choices.");
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message == null || !message.containsKey("content")) {
                    throw new RuntimeException("OpenAI odpoveď neobsahuje obsah.");
                }

                String content = ((String) message.get("content")).trim();

                logToFile(logFileName, "=== RESPONSE ===", content);
                logMetadata(logFileName, response);

                return content;
            })
            .doOnError(e -> {
                log.error("Chyba pri OpenAI volání: {}", e.getMessage());
                logToFile(logFileName, "=== ERROR ===", e.getMessage());
            });
    }

    // ========== LOGGING ==========

    private void logToFile(String fileName, String section, String content) {
        try {
            Path logDir = Paths.get(LOG_DIRECTORY);
            if (!Files.exists(logDir)) Files.createDirectories(logDir);

            Path logFile = logDir.resolve(fileName);
            try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
                writer.write("\n" + "=".repeat(80) + "\n");
                writer.write(section + "\n");
                writer.write("Time: " + LocalDateTime.now() + "\n");
                writer.write("=".repeat(80) + "\n");
                writer.write(content + "\n");
            }
        } catch (IOException e) {
            log.error("Failed to write log: {}", e.getMessage());
        }
    }

    @SuppressWarnings("rawtypes")
    private void logMetadata(String fileName, Map response) {
        try {
            StringBuilder metadata = new StringBuilder("\n=== METADATA ===\n");

            if (response.containsKey("usage")) {
                Map usage = (Map) response.get("usage");
                metadata.append("Tokens: prompt=").append(usage.get("prompt_tokens"))
                    .append(", completion=").append(usage.get("completion_tokens"))
                    .append(", total=").append(usage.get("total_tokens")).append("\n");
            }
            if (response.containsKey("model")) metadata.append("Model: ").append(response.get("model")).append("\n");
            if (response.containsKey("id")) metadata.append("Request ID: ").append(response.get("id")).append("\n");

            Path logFile = Paths.get(LOG_DIRECTORY).resolve(fileName);
            try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
                writer.write(metadata.toString());
            }
        } catch (IOException e) {
            log.error("Failed to write metadata: {}", e.getMessage());
        }
    }
}
