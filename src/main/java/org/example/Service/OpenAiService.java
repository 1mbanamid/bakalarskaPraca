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

    public String generateProjectPlan(String projectName, String projectDescription,
                                      String startDate, String deadline, String lang) {
        if (lang == null || lang.isBlank()) lang = "sk";
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        log.info("Generating plan for: {} (startDate={}, deadline={}, lang={})",
            projectName, startDate, deadline, lang);

        String inputBlock = buildInputBlock(projectName, projectDescription, startDate, deadline, lang);
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dateFrom = (startDate != null && !startDate.isEmpty()) ? startDate : today;

        // Calculate sprint date ranges
        String[][] sprintDates = calculateSprintDates(dateFrom, deadline);

        // --- Build prompts: 1 PRINCE2 + 3 separate Scrum sprints ---
        String princePrompt = "en".equals(lang)
            ? buildPrince2PromptEN(inputBlock, dateFrom)
            : buildPrince2Prompt(inputBlock, dateFrom);
        String sprint1Prompt = "en".equals(lang)
            ? buildScrumSprintPromptEN(inputBlock, 1, sprintDates)
            : buildScrumSprintPrompt(inputBlock, 1, sprintDates);
        String sprint2Prompt = "en".equals(lang)
            ? buildScrumSprintPromptEN(inputBlock, 2, sprintDates)
            : buildScrumSprintPrompt(inputBlock, 2, sprintDates);
        String sprint3Prompt = "en".equals(lang)
            ? buildScrumSprintPromptEN(inputBlock, 3, sprintDates)
            : buildScrumSprintPrompt(inputBlock, 3, sprintDates);

        String princeLog = String.format("request_%s_prince2.log", timestamp);
        String sprint1Log = String.format("request_%s_scrum_sprint1.log", timestamp);
        String sprint2Log = String.format("request_%s_scrum_sprint2.log", timestamp);
        String sprint3Log = String.format("request_%s_scrum_sprint3.log", timestamp);

        logToFile(princeLog, "=== REQUEST ===", princePrompt);
        logToFile(sprint1Log, "=== REQUEST ===", sprint1Prompt);
        logToFile(sprint2Log, "=== REQUEST ===", sprint2Prompt);
        logToFile(sprint3Log, "=== REQUEST ===", sprint3Prompt);

        // --- 4 PARALLEL API calls via Mono.zip ---
        Mono<String> princeMono = callOpenAiAsync(princePrompt, princeLog);
        Mono<String> sprint1Mono = callOpenAiAsync(sprint1Prompt, sprint1Log);
        Mono<String> sprint2Mono = callOpenAiAsync(sprint2Prompt, sprint2Log);
        Mono<String> sprint3Mono = callOpenAiAsync(sprint3Prompt, sprint3Log);

        log.info("🚀 Odosielam 4 paralelné požiadavky na AI (PRINCE2 + 3 Scrum šprinty)...");

        var results = Mono.zip(princeMono, sprint1Mono, sprint2Mono, sprint3Mono)
            .timeout(java.time.Duration.ofSeconds(180))
            .block();

        if (results == null) {
            throw new RuntimeException("OpenAI nevrátil odpoveď.");
        }

        String princeJsonResponse = results.getT1();
        String sprint1Json = results.getT2();
        String sprint2Json = results.getT3();
        String sprint3Json = results.getT4();

        String princeXml = convertPrince2JsonToXml(princeJsonResponse);
        String scrumXml = convertScrumSprintsToXml(sprint1Json, sprint2Json, sprint3Json, projectName, projectDescription);

        String combinedXml = "<ProjectPlans>\n" + princeXml + "\n\n" + scrumXml + "\n</ProjectPlans>";

        logToFile(String.format("request_%s_combined.log", timestamp), "=== COMBINED_XML ===", combinedXml);

        return combinedXml;
    }

    /** Splits the project timeline into 3 equal sprint periods. Returns [3][2] array of [start, end] dates. */
    // package-private for testing
    String[][] calculateSprintDates(String dateFrom, String deadline) {
        LocalDate start = LocalDate.parse(dateFrom);
        LocalDate end;
        if (deadline != null && !deadline.isEmpty()) {
            end = LocalDate.parse(deadline);
        } else {
            end = start.plusMonths(6);
        }

        long totalDays = ChronoUnit.DAYS.between(start, end);
        long sprintDays = Math.max(totalDays / 3, 7);

        return new String[][] {
            { start.toString(), start.plusDays(sprintDays).toString() },
            { start.plusDays(sprintDays).toString(), start.plusDays(sprintDays * 2).toString() },
            { start.plusDays(sprintDays * 2).toString(), end.toString() }
        };
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

    // ========== PRINCE2 PROMPT ==========

    private String buildPrince2Prompt(String inputBlock, String dateFrom) {
        return String.format("""
                Si certifikovaný PRINCE2 projektový manažér. Vráť IBA platný JSON objekt. Žiadny text mimo JSON, žiadny Markdown.

                Vstupné údaje:
                %s
                DÔLEŽITÉ: Analyzuj popis projektu a vytvor plán, ktorý zodpovedá REÁLNEMU typu tohto podnikania/projektu.
                Ak ide o softvérový projekt — použi fázy analýzy, vývoja, testovania a nasadenia.
                Ak ide o fyzický podnik (kaviareň, obchod, reštauráciu) — použi fázy prípravy priestorov, nákupu vybavenia, náboru zamestnancov, marketingu a otvorenia.
                Ak ide o iný typ projektu — prispôsob fázy podľa reálneho kontextu.

                Vráť JSON v tejto štruktúre:
                {
                  "name": "stručný názov projektu",
                  "description": "hlavný cieľ a prínos projektu",
                  "stages": [
                    {
                      "name": "názov fázy (summary v Jira)",
                      "description": "podrobný opis práce vo fáze",
                      "dueDate": "YYYY-MM-DD",
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
                  ]
                }

                Povinné pravidlá:
                1. Presne 4 fázy (stages) logicky nadväzujúce a realistické pre daný typ projektu.
                2. Dátumy >= %s, formát YYYY-MM-DD. Rozplánuj realisticky v rámci časového rámca.
                3. Každá fáza: min. 2 outputs, 2 risks, 3 tasks.
                4. originalEstimate: Jira formát — iba celé čísla: "1w", "1w 2d", "3d", "4h". NIKDY nepoužívaj desatinné čísla ako "1.5w".
                5. component: vyber kategóriu relevantnú pre projekt (napr. pre IT: Backend, Frontend, DevOps; pre podnik: Priestory, Marketing, Personál, Logistika, Financie, Prevádzka).
                6. Texty po slovensky, žiadne placeholdery.
                7. Labels: relevantné pre kontext projektu.
                8. Úlohy, riziká a výstupy musia byť KONKRÉTNE a REALISTICKÉ pre daný typ projektu — nie generické.

                DÔLEŽITÉ: Vygeneruj KOMPLETNÝ obsah pre VŠETKY 4 fázy. NIKDY nepoužívaj "..." ani iné skratky.
                """, inputBlock, dateFrom);
    }

    // ========== SCRUM SPRINT PROMPT (per-sprint) ==========

    private String buildScrumSprintPrompt(String inputBlock, int sprintNum, String[][] sprintDates) {
        return String.format("""
                Si skúsený Scrum master. Vráť IBA platný JSON objekt pre JEDEN šprint. Žiadny text mimo JSON, žiadny Markdown.

                Vstupné údaje:
                %s
                DÔLEŽITÉ: Analyzuj popis projektu a vytvor šprint, ktorý zodpovedá REÁLNEMU typu tohto podnikania/projektu.
                Ak ide o softvérový projekt — epiky a stories by mali byť o vývoji, testovaní, nasadení.
                Ak ide o fyzický podnik (kaviareň, obchod) — epiky by mali byť o priestoroch, vybavení, dodávateľoch, personáli, marketingu, otvorení.
                Ak ide o iný typ — prispôsob obsah podľa reálneho kontextu.

                Toto je šprint %d z 3. Celkovo 3 šprinty pokrývajú celý projekt od začiatku do konca.
                - Šprint 1: Prvá tretina projektu (príprava, základy, plánovanie)
                - Šprint 2: Stredná fáza (hlavná realizácia, kľúčové aktivity)
                - Šprint 3: Záverečná fáza (dokončenie, testovanie/kontrola, spustenie)
                Dátumový rozsah TOHTO šprintu: %s až %s

                Vráť JSON pre JEDEN šprint:
                {
                  "name": "názov šprintu (summary v Jira)",
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
                1. Presne 2 epiky, každý s 2-3 user stories.
                2. Každá story: 3 acceptanceCriteria, 2 subTasks.
                3. storyPoints: Fibonacci (1, 2, 3, 5, 8, 13).
                4. originalEstimate: Jira formát — iba celé čísla: "1w", "1w 2d", "3d", "4h". NIKDY nepoužívaj desatinné čísla ako "1.5w".
                5. Dátumy v rozsahu %s — %s.
                6. component: relevantná kategória pre projekt (napr. IT: Backend, Frontend; Podnik: Priestory, Personál, Marketing, Logistika, Financie).
                7. Úlohy a stories musia byť KONKRÉTNE a REALISTICKÉ pre daný typ projektu.
                8. Texty po slovensky, kompletné — žiadne "..." alebo placeholdery.
                """,
            inputBlock, sprintNum,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1],
            sprintDates[sprintNum - 1][1], sprintNum,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1]);
    }

    // ========== ENGLISH PROMPTS ==========

    private String buildPrince2PromptEN(String inputBlock, String dateFrom) {
        return String.format("""
                You are a certified PRINCE2 project manager. Return ONLY a valid JSON object. No text outside JSON, no Markdown.

                Input data:
                %s
                IMPORTANT: Analyze the project description and create a plan that matches the REAL type of this business/project.
                If it is a software project — use phases of analysis, development, testing and deployment.
                If it is a physical business (cafe, shop, restaurant) — use phases of premises preparation, equipment purchase, staff recruitment, marketing and opening.
                If it is another type of project — adapt phases to the real context.

                Return JSON in this structure:
                {
                  "name": "concise project name",
                  "description": "main goal and benefit of the project",
                  "stages": [
                    {
                      "name": "stage name (Jira summary)",
                      "description": "detailed description of work in this stage",
                      "dueDate": "YYYY-MM-DD",
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
                  ]
                }

                Mandatory rules:
                1. Exactly 4 stages logically sequential and realistic for this project type.
                2. Dates >= %s, format YYYY-MM-DD. Plan realistically within the timeframe.
                3. Each stage: min. 2 outputs, 2 risks, 3 tasks.
                4. originalEstimate: Jira format — whole numbers only: "1w", "1w 2d", "3d", "4h". NEVER use decimals like "1.5w".
                5. component: choose a category relevant to the project (e.g. IT: Backend, Frontend, DevOps; Business: Premises, Marketing, Staff, Logistics, Finance, Operations).
                6. All texts in English, no placeholders.
                7. Labels: relevant to project context.
                8. Tasks, risks and outputs must be SPECIFIC and REALISTIC for this project type — not generic.

                IMPORTANT: Generate COMPLETE content for ALL 4 stages. NEVER use "..." or other abbreviations.
                """, inputBlock, dateFrom);
    }

    private String buildScrumSprintPromptEN(String inputBlock, int sprintNum, String[][] sprintDates) {
        return String.format("""
                You are an experienced Scrum master. Return ONLY a valid JSON object for ONE sprint. No text outside JSON, no Markdown.

                Input data:
                %s
                IMPORTANT: Analyze the project description and create a sprint that matches the REAL type of this business/project.
                If it is a software project — epics and stories should be about development, testing, deployment.
                If it is a physical business (cafe, shop) — epics should be about premises, equipment, suppliers, staff, marketing, opening.
                If it is another type — adapt content to the real context.

                This is sprint %d of 3. Total 3 sprints cover the entire project from start to finish.
                - Sprint 1: First third of the project (preparation, foundations, planning)
                - Sprint 2: Middle phase (main implementation, key activities)
                - Sprint 3: Final phase (completion, testing/verification, launch)
                Date range of THIS sprint: %s to %s

                Return JSON for ONE sprint:
                {
                  "name": "sprint name (Jira summary)",
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
                1. Exactly 2 epics, each with 2-3 user stories.
                2. Each story: 3 acceptanceCriteria, 2 subTasks.
                3. storyPoints: Fibonacci (1, 2, 3, 5, 8, 13).
                4. originalEstimate: Jira format — whole numbers only: "1w", "1w 2d", "3d", "4h". NEVER use decimals like "1.5w".
                5. Dates in range %s — %s.
                6. component: relevant category for the project (e.g. IT: Backend, Frontend; Business: Premises, Staff, Marketing, Logistics, Finance).
                7. Tasks and stories must be SPECIFIC and REALISTIC for this project type.
                8. All texts in English, complete — no "..." or placeholders.
                """,
            inputBlock, sprintNum,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1],
            sprintDates[sprintNum - 1][1], sprintNum,
            sprintDates[sprintNum - 1][0], sprintDates[sprintNum - 1][1]);
    }

    // ========== JSON → XML CONVERSION ==========

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
                for (JsonNode stage : stages) {
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

    /** Combines 3 separate sprint JSON responses into one ScrumProject XML. */
    // package-private for testing
    String convertScrumSprintsToXml(String sprint1Json, String sprint2Json, String sprint3Json,
                                            String projectName, String projectDescription) {
        StringBuilder xml = new StringBuilder();
        xml.append("<ScrumProject>\n");
        xml.append("  <Name>").append(xmlEscape(projectName)).append("</Name>\n");
        xml.append("  <Description>").append(xmlEscape(projectDescription)).append("</Description>\n");
        xml.append("  <Sprints>\n");

        String[] sprintJsons = { sprint1Json, sprint2Json, sprint3Json };
        for (int i = 0; i < sprintJsons.length; i++) {
            try {
                String jsonBlock = extractJsonBlock(sprintJsons[i]);
                JsonNode sprint = objectMapper.readTree(jsonBlock);
                appendSprintXml(xml, sprint);
                log.info("✅ Scrum Sprint {} JSON → XML OK", i + 1);
            } catch (Exception e) {
                log.error("❌ Scrum Sprint {} JSON→XML chyba: {}", i + 1, e.getMessage());
                // Add empty sprint placeholder so the structure remains valid
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
