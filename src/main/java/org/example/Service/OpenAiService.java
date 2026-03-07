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

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
                                      String startDate, String deadline, String budget, String teamSize) {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        log.info("Generujem plán pre projekt: {} (startDate={}, deadline={}, budget={}, teamSize={})",
            projectName, startDate, deadline, budget, teamSize);

        // --- PRINCE2 ---
        String princePrompt = buildPrince2Prompt(projectName, projectDescription, startDate, deadline, budget, teamSize);
        String princeLog = String.format("request_%s_prince2.log", timestamp);
        String princeJsonResponse = callOpenAi(princePrompt, princeLog);
        String princeXml = convertPrince2JsonToXml(princeJsonResponse);

        // --- Scrum ---
        String scrumPrompt = buildScrumPrompt(projectName, projectDescription, startDate, deadline, budget, teamSize);
        String scrumLog = String.format("request_%s_scrum.log", timestamp);
        String scrumJsonResponse = callOpenAi(scrumPrompt, scrumLog);
        String scrumXml = convertScrumJsonToXml(scrumJsonResponse);

        String combinedXml = "<ProjectPlans>\n" + princeXml + "\n\n" + scrumXml + "\n</ProjectPlans>";

        logToFile(String.format("request_%s_combined.log", timestamp), "=== COMBINED_XML ===", combinedXml);

        return combinedXml;
    }

    // ========== PRINCE2 PROMPT (JSON) ==========

    private String buildPrince2Prompt(String projectName, String projectDescription,
                                      String startDate, String deadline, String budget, String teamSize) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dateFrom = (startDate != null && !startDate.isEmpty()) ? startDate : today;

        StringBuilder inputBlock = new StringBuilder();
        inputBlock.append("- Názov projektu: ").append(projectName).append("\n");
        inputBlock.append("- Popis projektu: ").append(projectDescription).append("\n");
        inputBlock.append("- Dátum začiatku: ").append(dateFrom).append("\n");
        if (deadline != null && !deadline.isEmpty()) inputBlock.append("- Deadline: ").append(deadline).append("\n");
        if (budget != null && !budget.isEmpty()) inputBlock.append("- Rozpočet: ").append(budget).append(" EUR\n");
        if (teamSize != null && !teamSize.isEmpty()) inputBlock.append("- Veľkosť tímu: ").append(teamSize).append(" ľudí\n");

        return String.format("""
                Si certifikovaný PRINCE2 projektový manažér. Vráť IBA platný JSON objekt. Žiadny text mimo JSON, žiadny Markdown.

                Vstupné údaje:
                %s
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
                      "component": "Backend|Frontend|Infrastructure|Testing|DevOps",
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

                Polia sa prenášajú do Jira:
                - name → summary | description → description (ADF) | dueDate → duedate
                - priority → priority | labels → labels | component → components
                - originalEstimate → timetracking.originalEstimate
                - outputs, risks → súčasť description (textovo)
                - tasks → sub-tasky s rodičovskou úlohou

                Povinné pravidlá:
                1. Presne 4 fázy (stages) logicky nadväzujúce.
                2. Dátumy musia byť >= %s a vo formáte YYYY-MM-DD. Rozplánuj realisticky v rámci zadaného časového rámca.
                3. Každá fáza: min. 2 outputs, 2 risks, 3 tasks.
                4. originalEstimate: Jira formát — "1w 2d", "3d", "4h" atď. Odrážaj rozpočet a veľkosť tímu.
                5. component: vyber jednu z kategórií — Backend, Frontend, Infrastructure, Testing, DevOps, Design, Documentation.
                6. Texty po slovensky, žiadne placeholdery.
                7. Labels: relevantné pre daný kontext (prince2, planning, analysis, development, testing...).
                """, inputBlock.toString(), dateFrom);
    }

    // ========== SCRUM PROMPT (JSON) ==========

    private String buildScrumPrompt(String projectName, String projectDescription,
                                    String startDate, String deadline, String budget, String teamSize) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dateFrom = (startDate != null && !startDate.isEmpty()) ? startDate : today;

        StringBuilder inputBlock = new StringBuilder();
        inputBlock.append("- Názov projektu: ").append(projectName).append("\n");
        inputBlock.append("- Popis projektu: ").append(projectDescription).append("\n");
        inputBlock.append("- Dátum začiatku: ").append(dateFrom).append("\n");
        if (deadline != null && !deadline.isEmpty()) inputBlock.append("- Deadline: ").append(deadline).append("\n");
        if (budget != null && !budget.isEmpty()) inputBlock.append("- Rozpočet: ").append(budget).append(" EUR\n");
        if (teamSize != null && !teamSize.isEmpty()) inputBlock.append("- Veľkosť tímu: ").append(teamSize).append(" ľudí\n");

        return String.format("""
                Si skúsený Scrum master. Vráť IBA platný JSON objekt. Žiadny text mimo JSON, žiadny Markdown.

                Vstupné údaje:
                %s
                Vráť JSON v tejto štruktúre:
                {
                  "name": "stručný názov projektu",
                  "description": "popis produktu a cieľa",
                  "sprints": [
                    {
                      "name": "názov šprintu (summary v Jira)",
                      "goal": "konkrétny cieľ šprintu",
                      "description": "stručný plán práce",
                      "dueDate": "YYYY-MM-DD",
                      "priority": "High|Medium|Low",
                      "labels": ["scrum","sprint1"],
                      "risks": ["riziko a mitigácia"],
                      "epics": [
                        {
                          "title": "názov epiku (summary v Jira)",
                          "description": "opis biznis hodnoty",
                          "priority": "High|Medium|Low",
                          "labels": ["epic","backend"],
                          "component": "Backend|Frontend|...",
                          "stories": [
                            {
                              "title": "názov user story (summary v Jira)",
                              "description": "opis potreby používateľa",
                              "storyPoints": 5,
                              "priority": "High|Medium|Low",
                              "dueDate": "YYYY-MM-DD",
                              "labels": ["feature"],
                              "originalEstimate": "3d",
                              "acceptanceCriteria": ["kritérium 1", "kritérium 2", "kritérium 3"],
                              "subTasks": [
                                {
                                  "title": "názov sub-tasku (summary v Jira)",
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
                  ]
                }

                Polia sa prenášajú do Jira:
                - name/title → summary | description → description (ADF)
                - dueDate → duedate | priority → priority | labels → labels
                - component → components | storyPoints → custom field (story points)
                - originalEstimate → timetracking.originalEstimate
                - goal, risks, acceptanceCriteria → súčasť description
                - subTasks → sub-tasky s rodičovskou úlohou

                Povinné pravidlá:
                1. Presne 3 šprinty, logicky nadväzujúce.
                2. Každý šprint: min. 2 epiky, každý epic: 2-3 user stories.
                3. Každá story: min. 3 acceptanceCriteria, 2 subTasks.
                4. storyPoints: Fibonacci (1, 2, 3, 5, 8, 13). Celkovo realistické pre rozpočet a tím.
                5. originalEstimate: Jira formát — "1w", "3d", "4h". Odráža story points.
                6. component: Backend, Frontend, Infrastructure, Testing, DevOps, Design, Documentation.
                7. Dátumy >= %s. Rozplánuj realisticky.
                8. Texty po slovensky, žiadne placeholdery.
                """, inputBlock.toString(), dateFrom);
    }

    // ========== JSON → XML CONVERSION ==========

    private String convertPrince2JsonToXml(String jsonResponse) {
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

                    // Outputs
                    xml.append("      <Outputs>\n");
                    JsonNode outputs = stage.get("outputs");
                    if (outputs != null && outputs.isArray()) {
                        for (JsonNode o : outputs) xml.append("        <Output>").append(escVal(o)).append("</Output>\n");
                    }
                    xml.append("      </Outputs>\n");

                    // Risks
                    xml.append("      <Risks>\n");
                    JsonNode risks = stage.get("risks");
                    if (risks != null && risks.isArray()) {
                        for (JsonNode r : risks) xml.append("        <Risk>").append(escVal(r)).append("</Risk>\n");
                    }
                    xml.append("      </Risks>\n");

                    // Tasks
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

            log.info("✅ PRINCE2 JSON skonvertovaný na XML ({} znakov)", xml.length());
            return xml.toString();

        } catch (Exception e) {
            log.error("❌ Chyba pri konverzii PRINCE2 JSON na XML: {}", e.getMessage(), e);
            log.warn("⚠️ Skúšam fallback — hľadám XML priamo v odpovedi");
            return extractXmlSection(jsonResponse, "PRINCE2Project");
        }
    }

    private String convertScrumJsonToXml(String jsonResponse) {
        try {
            String jsonBlock = extractJsonBlock(jsonResponse);
            JsonNode root = objectMapper.readTree(jsonBlock);

            StringBuilder xml = new StringBuilder();
            xml.append("<ScrumProject>\n");
            xml.append("  <Name>").append(esc(root, "name")).append("</Name>\n");
            xml.append("  <Description>").append(esc(root, "description")).append("</Description>\n");
            xml.append("  <Sprints>\n");

            JsonNode sprints = root.get("sprints");
            if (sprints != null && sprints.isArray()) {
                for (JsonNode sprint : sprints) {
                    xml.append("    <Sprint>\n");
                    xml.append("      <Name>").append(esc(sprint, "name")).append("</Name>\n");
                    xml.append("      <Goal>").append(esc(sprint, "goal")).append("</Goal>\n");
                    xml.append("      <Description>").append(esc(sprint, "description")).append("</Description>\n");
                    xml.append("      <DueDate>").append(esc(sprint, "dueDate")).append("</DueDate>\n");
                    xml.append("      <Priority>").append(esc(sprint, "priority")).append("</Priority>\n");
                    xml.append("      <Labels>").append(joinArray(sprint, "labels")).append("</Labels>\n");

                    // Risks
                    xml.append("      <Risks>\n");
                    JsonNode risks = sprint.get("risks");
                    if (risks != null && risks.isArray()) {
                        for (JsonNode r : risks) xml.append("        <Risk>").append(escVal(r)).append("</Risk>\n");
                    }
                    xml.append("      </Risks>\n");

                    // Epics
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

                            // Stories
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

                                    // AcceptanceCriteria
                                    xml.append("              <AcceptanceCriteria>\n");
                                    JsonNode criteria = story.get("acceptanceCriteria");
                                    if (criteria != null && criteria.isArray()) {
                                        for (JsonNode c : criteria) xml.append("                <Criterion>").append(escVal(c)).append("</Criterion>\n");
                                    }
                                    xml.append("              </AcceptanceCriteria>\n");

                                    // SubTasks
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
            }

            xml.append("  </Sprints>\n");
            xml.append("</ScrumProject>");

            log.info("✅ Scrum JSON skonvertovaný na XML ({} znakov)", xml.length());
            return xml.toString();

        } catch (Exception e) {
            log.error("❌ Chyba pri konverzii Scrum JSON na XML: {}", e.getMessage(), e);
            log.warn("⚠️ Skúšam fallback — hľadám XML priamo v odpovedi");
            return extractXmlSection(jsonResponse, "ScrumProject");
        }
    }

    // ========== HELPERS ==========

    /** Extracts JSON block from AI response (strips markdown fences, leading text, etc.) */
    private String extractJsonBlock(String response) {
        if (response == null || response.isBlank()) {
            throw new RuntimeException("AI vrátil prázdnu odpoveď.");
        }

        String trimmed = response.trim();

        // Strip markdown code fences
        if (trimmed.contains("```")) {
            Pattern p = Pattern.compile("```(?:json)?\\s*\\n?(\\{.*?})\\s*```", Pattern.DOTALL);
            Matcher m = p.matcher(trimmed);
            if (m.find()) {
                return m.group(1).trim();
            }
        }

        // Find first { ... last }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        throw new RuntimeException("Nepodarilo sa nájsť JSON v odpovedi AI.");
    }

    /** XML-escape and get a field value */
    private String esc(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return "";
        return xmlEscape(node.get(field).asText(""));
    }

    /** XML-escape a value node (for arrays of strings) */
    private String escVal(JsonNode node) {
        if (node == null || node.isNull()) return "";
        return xmlEscape(node.asText(""));
    }

    /** Join array field as comma-separated string */
    private String joinArray(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : node.get(field)) {
            if (sb.length() > 0) sb.append(",");
            sb.append(item.asText(""));
        }
        return xmlEscape(sb.toString());
    }

    private String xmlEscape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Fallback: extract XML section if AI returned XML instead of JSON */
    private String extractXmlSection(String content, String rootTag) {
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("AI vrátil prázdny obsah pre " + rootTag);
        }
        Pattern pattern = Pattern.compile(String.format("(?is)<%1$s\\b.*?</%1$s>", rootTag));
        Matcher matcher = pattern.matcher(content.trim());
        if (!matcher.find()) {
            throw new RuntimeException("Výstup neobsahuje očakávaný tag <" + rootTag + "> ani platný JSON.");
        }
        return matcher.group().trim();
    }

    // ========== OPENAI API CALL ==========

    private String callOpenAi(String prompt, String logFileName) {
        logToFile(logFileName, "=== REQUEST ===", prompt);

        Map<String, Object> body = Map.of(
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 8000
        );

        try {
            Map<String, Object> response = webClient.post()
                .uri("/openai/deployments/" + deploymentName + "/chat/completions?api-version=2025-01-01-preview")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(java.time.Duration.ofSeconds(120))
                .block();

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
                throw new RuntimeException("OpenAI odpoveď neobsahuje výsledný obsah.");
            }

            String content = ((String) message.get("content")).trim();

            logToFile(logFileName, "=== RESPONSE ===", content);
            logMetadata(logFileName, response);

            return content;
        } catch (Exception e) {
            log.error("Chyba pri požiadavke na Azure OpenAI: {}", e.getMessage(), e);
            logToFile(logFileName, "=== ERROR ===", e.getMessage() + "\n" + e.getClass().getName());
            throw new RuntimeException("Chyba pri komunikácii s OpenAI: " + e.getMessage(), e);
        }
    }

    // ========== LOGGING ==========

    private void logToFile(String fileName, String section, String content) {
        try {
            Path logDir = Paths.get(LOG_DIRECTORY);
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }

            Path logFile = logDir.resolve(fileName);
            try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
                writer.write("\n" + "=".repeat(80) + "\n");
                writer.write(section + "\n");
                writer.write("Time: " + LocalDateTime.now() + "\n");
                writer.write("=".repeat(80) + "\n");
                writer.write(content + "\n");
            }

            log.info("Logged to file: {}", logFile.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write log file: {}", e.getMessage());
        }
    }

    @SuppressWarnings("rawtypes")
    private void logMetadata(String fileName, Map response) {
        try {
            StringBuilder metadata = new StringBuilder();
            metadata.append("\n=== METADATA ===\n");

            if (response.containsKey("usage")) {
                Map usage = (Map) response.get("usage");
                metadata.append("Tokens used:\n");
                metadata.append("  - Prompt tokens: ").append(usage.get("prompt_tokens")).append("\n");
                metadata.append("  - Completion tokens: ").append(usage.get("completion_tokens")).append("\n");
                metadata.append("  - Total tokens: ").append(usage.get("total_tokens")).append("\n");
            }

            if (response.containsKey("model")) {
                metadata.append("Model: ").append(response.get("model")).append("\n");
            }

            if (response.containsKey("id")) {
                metadata.append("Request ID: ").append(response.get("id")).append("\n");
            }

            Path logFile = Paths.get(LOG_DIRECTORY).resolve(fileName);
            try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
                writer.write(metadata.toString());
                writer.write("\n" + "=".repeat(80) + "\n");
            }
        } catch (IOException e) {
            log.error("Failed to write metadata: {}", e.getMessage());
        }
    }
}
