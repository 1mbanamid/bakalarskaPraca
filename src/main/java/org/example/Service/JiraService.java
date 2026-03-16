package org.example.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.LinkedHashMap;

@Service
public class JiraService {

    private static final Logger log = LoggerFactory.getLogger(JiraService.class);

    private final WebClient webClient;
    private final String projectKey;
    private final String siteUrl;
    private final String storyPointsField;

    /** Accumulates error messages during a single export run. */
    private final ThreadLocal<List<String>> exportErrors = ThreadLocal.withInitial(ArrayList::new);

    /** Set to true during an export when a 401 is detected — aborts further issue creation. */
    private final ThreadLocal<Boolean> abortExport = ThreadLocal.withInitial(() -> false);

    public JiraService(
        @Value("${jira.site-url}") String siteUrl,
        @Value("${jira.email}") String email,
        @Value("${jira.api-token}") String apiToken,
        @Value("${jira.project-key}") String projectKey,
        @Value("${jira.custom-fields.story-points:customfield_10016}") String storyPointsField
    ) {
        String auth = email + ":" + apiToken;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        this.webClient = WebClient.builder()
            .baseUrl(siteUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.projectKey = projectKey;
        this.siteUrl = siteUrl;
        this.storyPointsField = storyPointsField;

        log.info("JiraService initialized for site: {}, project: {}, storyPointsField: {}", siteUrl, projectKey, storyPointsField);
    }

    // ========== CONNECTION CHECK ==========

    /**
     * Verifies Jira connectivity and project access.
     * Returns a result map with keys: connected (bool), user (String), projectFound (bool), error (String).
     */
    public Map<String, Object> checkJiraConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 1. Check authentication via /rest/api/3/myself
            Map myself = webClient.get()
                .uri("/rest/api/3/myself")
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    cr -> cr.bodyToMono(String.class).flatMap(body -> reactor.core.publisher.Mono.error(
                        new RuntimeException("Auth failed: HTTP " + cr.statusCode() + " — " + body))))
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(10))
                .block();

            String displayName = myself != null ? String.valueOf(myself.getOrDefault("displayName", "unknown")) : "unknown";
            String email = myself != null ? String.valueOf(myself.getOrDefault("emailAddress", "unknown")) : "unknown";
            result.put("connected", true);
            result.put("user", displayName + " (" + email + ")");
            log.info("✅ Jira auth OK: {}", displayName);

            // 2. Check project access
            try {
                Map project = webClient.get()
                    .uri("/rest/api/3/project/" + projectKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        cr -> cr.bodyToMono(String.class).flatMap(body -> reactor.core.publisher.Mono.error(
                            new RuntimeException("Project check failed: HTTP " + cr.statusCode() + " — " + body))))
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block();

                result.put("projectFound", true);
                result.put("projectName", project != null ? project.getOrDefault("name", projectKey) : projectKey);
                log.info("✅ Jira project OK: {}", projectKey);
            } catch (Exception pe) {
                result.put("projectFound", false);
                result.put("projectError", "Project '" + projectKey + "' not found or no access: " + pe.getMessage());
                log.warn("⚠️ Jira project check failed: {}", pe.getMessage());
            }

        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", "Authentication failed: " + e.getMessage()
                + " — Check JIRA_EMAIL and JIRA_API_TOKEN in your .env file.");
            log.error("❌ Jira connection failed: {}", e.getMessage());
        }
        return result;
    }

    // ========== MAIN EXPORT ==========

    public Map<String, Object> exportToJira(String xmlContent, String methodology, String projectName) {
        exportErrors.get().clear();
        abortExport.set(false);
        try {
            log.info("🚀 ========== ZAČÍNAM EXPORT DO JIRA ==========");
            log.info("📋 Metodológia: {}, Projekt: {}, Jira key: {}", methodology, projectName, projectKey);

            // ---- Pre-flight auth check ----
            log.info("🔐 Overujem Jira autentifikáciu...");
            Map<String, Object> connCheck = checkJiraConnection();
            if (!Boolean.TRUE.equals(connCheck.get("connected"))) {
                String authError = (String) connCheck.getOrDefault("error",
                    "Jira authentication failed. Check JIRA_EMAIL and JIRA_API_TOKEN.");
                log.error("❌ Pre-flight auth check failed: {}", authError);
                Map<String, Object> fail = new HashMap<>();
                fail.put("success", false);
                fail.put("message", authError);
                fail.put("errors", List.of(authError));
                return fail;
            }
            if (!Boolean.TRUE.equals(connCheck.get("projectFound"))) {
                String projError = (String) connCheck.getOrDefault("projectError",
                    "Project '" + projectKey + "' not found or no CREATE_ISSUES permission.");
                log.error("❌ Pre-flight project check failed: {}", projError);
                Map<String, Object> fail = new HashMap<>();
                fail.put("success", false);
                fail.put("message", projError);
                fail.put("errors", List.of(projError));
                return fail;
            }
            log.info("✅ Pre-flight OK — user: {}, project: {}", connCheck.get("user"), connCheck.get("projectName"));

            String cleanXml = extractXml(xmlContent);
            Document doc = parseXmlDocument(cleanXml);

            List<Map<String, Object>> createdIssues;

            if ("PRINCE2".equals(methodology)) {
                log.info("🔵 Exportujem PRINCE2 úlohy do projektu {}...", projectKey);
                createdIssues = exportPRINCE2Tasks(doc, projectKey);
            } else if ("Scrum".equals(methodology)) {
                log.info("🟢 Exportujem Scrum úlohy do projektu {}...", projectKey);
                createdIssues = exportScrumTasks(doc, projectKey);
            } else {
                throw new IllegalArgumentException("Neznáma metodológia: " + methodology);
            }

            String projectUrl = siteUrl + "/browse/" + projectKey;
            List<String> errors = new ArrayList<>(exportErrors.get());

            log.info("🎉 ========== EXPORT DOKONČENÝ ==========");
            log.info("✅ Vytvorených úloh: {}, Chýb: {}", createdIssues.size(), errors.size());

            // FIX: success = true only when at least 1 issue was created
            boolean success = !createdIssues.isEmpty();

            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            result.put("issues", createdIssues);
            result.put("projectKey", projectKey);
            result.put("projectUrl", projectUrl);

            if (success && errors.isEmpty()) {
                result.put("message", "Úspešne exportovaných " + createdIssues.size() + " úloh do projektu '" + projectKey + "'");
            } else if (success) {
                result.put("message", "Exportovaných " + createdIssues.size() + " úloh, ale " + errors.size() + " zlyhalo");
                result.put("errors", errors);
            } else {
                String firstError = errors.isEmpty() ? "Žiadne úlohy neboli nájdené v XML" : errors.get(0);
                result.put("message", "Export zlyhal: " + firstError);
                result.put("errors", errors);
            }

            return result;

        } catch (Exception e) {
            log.error("❌ ========== CHYBA PRI EXPORTE ==========");
            log.error("❌ {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "Chyba pri exporte do Jira: " + e.getMessage());
            result.put("errors", List.of(e.getMessage()));
            return result;
        } finally {
            exportErrors.remove();
            abortExport.remove();
        }
    }

    // ========== XML EXTRACTION & PARSING ==========

    // package-private for testing
    String extractXml(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new RuntimeException("XML obsah je prázdny");
        }

        // Try XML_START markers
        if (xmlContent.contains("<XML_START>")) {
            int start = xmlContent.indexOf("<XML_START>") + "<XML_START>".length();
            int end = xmlContent.indexOf("</XML_END>");
            if (end > start) {
                return xmlContent.substring(start, end).trim();
            }
        }

        // Try <ProjectPlans> tag (case-insensitive)
        String lower = xmlContent.toLowerCase();
        int xmlStart = lower.indexOf("<projectplans>");
        if (xmlStart != -1) {
            int xmlEnd = lower.indexOf("</projectplans>", xmlStart);
            if (xmlEnd != -1) {
                return xmlContent.substring(xmlStart, xmlEnd + "</projectplans>".length()).trim();
            }
            return xmlContent.substring(xmlStart).trim();
        }

        return xmlContent;
    }

    private Document parseXmlDocument(String cleanXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(cleanXml.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();
            log.info("✅ XML sparsovaný, root: {}", doc.getDocumentElement().getNodeName());
            return doc;
        } catch (Exception e) {
            log.error("❌ XML parsing chyba: {}", e.getMessage());
            log.error("📄 XML (prvých 500 znakov): {}", cleanXml.substring(0, Math.min(500, cleanXml.length())));
            throw new RuntimeException("XML obsahuje chyby: " + e.getMessage(), e);
        }
    }

    // ========== PRINCE2 EXPORT ==========

    private List<Map<String, Object>> exportPRINCE2Tasks(Document doc, String projectKey) {
        List<Map<String, Object>> createdIssues = new ArrayList<>();

        NodeList prince2Stages = doc.getElementsByTagName("Stage");
        log.info("🔵 Našiel som {} PRINCE2 stage", prince2Stages.getLength());

        int stageCount = 0;
        int subTaskCount = 0;

        for (int i = 0; i < prince2Stages.getLength(); i++) {
            Element stage = (Element) prince2Stages.item(i);

            String summary = getDirectText(stage, "Name");
            if (summary.isEmpty()) summary = "PRINCE2 Stage " + (i + 1);

            String description = getDirectText(stage, "Description");
            String dueDate = getDirectText(stage, "DueDate");
            String priority = getDirectText(stage, "Priority");
            String labels = getDirectText(stage, "Labels");
            String component = getDirectText(stage, "Component");
            String originalEstimate = getDirectText(stage, "OriginalEstimate");

            // Build description
            StringBuilder fullDesc = new StringBuilder();
            fullDesc.append("📋 PRINCE2 Stage\n\n");
            if (!description.isEmpty()) fullDesc.append(description).append("\n\n");
            appendIfNotEmpty(fullDesc, "Due Date", dueDate);
            fullDesc.append("\n");

            appendListSection(fullDesc, stage, "Outputs", "Output", "📦 Výstupy:");
            appendListSection(fullDesc, stage, "Risks", "Risk", "⚠️ Riziká:");

            // Collect tasks
            NodeList taskNodes = stage.getElementsByTagName("Task");
            List<Element> tasks = filterDirectChildren(taskNodes, "Tasks");
            if (!tasks.isEmpty()) {
                fullDesc.append("🛠️ Úlohy (budú vytvorené ako sub-task):\n");
                for (Element t : tasks) {
                    String title = getDirectText(t, "Title");
                    fullDesc.append("  • ").append(title.isEmpty() ? "Úloha" : title).append("\n");
                }
                fullDesc.append("\n");
            }

            log.info("📤 Exportujem PRINCE2 stage: {}", summary);

            Map<String, String> stageExtra = buildExtra(component, originalEstimate, null);

            Map<String, Object> issue = createJiraIssue(summary, fullDesc.toString(), "Task", projectKey,
                priority, labels, dueDate, null, stageExtra);

            if (issue != null && issue.containsKey("key")) {
                createdIssues.add(issue);
                stageCount++;
                String parentKey = issue.get("key").toString();
                log.info("✅ Stage vytvorený: {}", parentKey);

                // Create sub-tasks
                for (int j = 0; j < tasks.size(); j++) {
                    Element taskEl = tasks.get(j);
                    String taskTitle = getDirectText(taskEl, "Title");
                    if (taskTitle.isEmpty()) taskTitle = summary + " - úloha " + (j + 1);

                    StringBuilder subDesc = new StringBuilder();
                    subDesc.append("🔧 Úloha v rámci stage ").append(summary).append("\n\n");
                    String taskDescription = getDirectText(taskEl, "Description");
                    if (!taskDescription.isEmpty()) subDesc.append(taskDescription).append("\n\n");

                    String taskEstimate = getDirectText(taskEl, "OriginalEstimate");
                    Map<String, String> taskExtra = buildExtra(component, taskEstimate, null);

                    Map<String, Object> subTask = createJiraIssue(taskTitle, subDesc.toString(), "Subtask",
                        projectKey, getDirectText(taskEl, "Priority"), getDirectText(taskEl, "Labels"),
                        getDirectText(taskEl, "DueDate"), parentKey, taskExtra);
                    if (subTask != null && subTask.containsKey("key")) {
                        createdIssues.add(subTask);
                        subTaskCount++;
                        log.info("   ↳ Sub-task: {}", subTask.get("key"));
                    }
                }
            }
        }

        log.info("📊 PRINCE2: {} stage, {} sub-taskov (spolu {})", stageCount, subTaskCount, createdIssues.size());
        return createdIssues;
    }

    // ========== SCRUM EXPORT ==========

    private List<Map<String, Object>> exportScrumTasks(Document doc, String projectKey) {
        List<Map<String, Object>> createdIssues = new ArrayList<>();

        Element scrumProject = (Element) doc.getElementsByTagName("ScrumProject").item(0);
        if (scrumProject == null) {
            log.warn("⚠️ ScrumProject tag nenájdený v XML");
            exportErrors.get().add("ScrumProject tag nenájdený v XML");
            return createdIssues;
        }

        NodeList sprintNodes = scrumProject.getElementsByTagName("Sprint");
        List<Element> sprints = filterDirectChildren(sprintNodes, "Sprints");
        log.info("🟢 Našiel som {} Sprintov", sprints.size());

        int sprintCount = 0, epicCount = 0, storyCount = 0, subTaskCount = 0;

        for (int i = 0; i < sprints.size(); i++) {
            Element sprint = sprints.get(i);

            String name = getDirectText(sprint, "Name");
            if (name.isEmpty()) name = "Sprint " + (i + 1);

            String goal = getDirectText(sprint, "Goal");
            String description = getDirectText(sprint, "Description");
            String dueDate = getDirectText(sprint, "DueDate");
            String priority = getDirectText(sprint, "Priority");
            String labels = getDirectText(sprint, "Labels");

            // Build sprint description
            StringBuilder sprintDesc = new StringBuilder();
            sprintDesc.append("🏃 Scrum Sprint\n\n");
            if (!description.isEmpty()) sprintDesc.append(description).append("\n\n");
            appendIfNotEmpty(sprintDesc, "Goal", goal);
            appendIfNotEmpty(sprintDesc, "Due Date", dueDate);
            sprintDesc.append("\n");

            // Sprint risks
            appendListSection(sprintDesc, sprint, "Risks", "Risk", "⚠️ Riziká:");

            // List epics in description
            List<Element> epics = filterDirectChildren(sprint.getElementsByTagName("Epic"), "Epics");
            if (!epics.isEmpty()) {
                sprintDesc.append("Epics v sprinte:\n");
                for (Element ep : epics) {
                    String t = getDirectText(ep, "Title");
                    sprintDesc.append("  • ").append(t.isEmpty() ? "Epic" : t).append("\n");
                }
                sprintDesc.append("\n");
            }

            log.info("📤 Exportujem Sprint: {}", name);
            Map<String, Object> sprintIssue = createJiraIssue(name, sprintDesc.toString(), "Task",
                projectKey, priority, labels, dueDate, null, null);

            String sprintKey = null;
            if (sprintIssue != null && sprintIssue.containsKey("key")) {
                createdIssues.add(sprintIssue);
                sprintCount++;
                sprintKey = sprintIssue.get("key").toString();
                log.info("✅ Sprint: {}", sprintKey);
            }

            // Process epics
            for (int j = 0; j < epics.size(); j++) {
                Element epic = epics.get(j);

                String epicTitle = getDirectText(epic, "Title");
                if (epicTitle.isEmpty()) epicTitle = name + " - Epic " + (j + 1);
                String epicComponent = getDirectText(epic, "Component");

                StringBuilder epicDesc = new StringBuilder();
                epicDesc.append("🚀 Epic v sprinte ").append(name).append("\n\n");
                String epicDescription = getDirectText(epic, "Description");
                if (!epicDescription.isEmpty()) epicDesc.append(epicDescription).append("\n\n");
                if (sprintKey != null) epicDesc.append("Sprint: ").append(sprintKey).append("\n");
                epicDesc.append("\n");

                // List stories in epic description
                List<Element> stories = filterDirectChildren(epic.getElementsByTagName("Story"), "Stories");
                if (!stories.isEmpty()) {
                    epicDesc.append("User Stories:\n");
                    for (Element st : stories) {
                        String t = getDirectText(st, "Title");
                        epicDesc.append("  • ").append(t.isEmpty() ? "Story" : t).append("\n");
                    }
                    epicDesc.append("\n");
                }

                Map<String, String> epicExtra = buildExtra(epicComponent, null, null);

                Map<String, Object> epicIssue = createJiraIssue(epicTitle, epicDesc.toString(), "Task",
                    projectKey, getDirectText(epic, "Priority"), getDirectText(epic, "Labels"),
                    dueDate, null, epicExtra);

                String epicKey = null;
                if (epicIssue != null && epicIssue.containsKey("key")) {
                    createdIssues.add(epicIssue);
                    epicCount++;
                    epicKey = epicIssue.get("key").toString();
                    log.info("   ✅ Epic: {}", epicKey);
                }

                // Process stories
                for (int k = 0; k < stories.size(); k++) {
                    Element story = stories.get(k);

                    String storyTitle = getDirectText(story, "Title");
                    if (storyTitle.isEmpty()) storyTitle = epicTitle + " - Story " + (k + 1);

                    String storyPoints = getDirectText(story, "StoryPoints");
                    String storyDueDate = getDirectText(story, "DueDate");
                    if (storyDueDate.isEmpty()) storyDueDate = dueDate;
                    String storyEstimate = getDirectText(story, "OriginalEstimate");

                    StringBuilder storyDesc = new StringBuilder();
                    storyDesc.append("🧩 User Story v epic ").append(epicTitle).append("\n\n");
                    String storyDescription = getDirectText(story, "Description");
                    if (!storyDescription.isEmpty()) storyDesc.append(storyDescription).append("\n\n");
                    if (!storyPoints.isEmpty()) storyDesc.append("Story Points: ").append(storyPoints).append("\n");
                    if (epicKey != null) storyDesc.append("Epic: ").append(epicKey).append("\n");
                    storyDesc.append("\n");

                    // Acceptance criteria
                    appendListSection(storyDesc, story, "AcceptanceCriteria", "Criterion", "✅ Acceptance Criteria:");

                    // List sub-tasks in description
                    List<Element> subTasks = filterDirectChildren(story.getElementsByTagName("Task"), "SubTasks");
                    if (!subTasks.isEmpty()) {
                        storyDesc.append("🛠️ Sub-tasky:\n");
                        for (Element st : subTasks) {
                            String t = getDirectText(st, "Title");
                            storyDesc.append("  • ").append(t.isEmpty() ? "Sub-task" : t).append("\n");
                        }
                        storyDesc.append("\n");
                    }

                    Map<String, String> storyExtra = buildExtra(epicComponent, storyEstimate, storyPoints);

                    Map<String, Object> storyIssue = createJiraIssue(storyTitle, storyDesc.toString(), "Task",
                        projectKey, getDirectText(story, "Priority"), getDirectText(story, "Labels"),
                        storyDueDate, null, storyExtra);

                    String storyKey = null;
                    if (storyIssue != null && storyIssue.containsKey("key")) {
                        createdIssues.add(storyIssue);
                        storyCount++;
                        storyKey = storyIssue.get("key").toString();
                        log.info("      ✅ Story: {}", storyKey);
                    }

                    if (storyKey == null) continue;

                    // Create sub-tasks
                    for (int st = 0; st < subTasks.size(); st++) {
                        Element subEl = subTasks.get(st);
                        String taskTitle = getDirectText(subEl, "Title");
                        if (taskTitle.isEmpty()) taskTitle = storyTitle + " - sub-task " + (st + 1);

                        StringBuilder subDesc = new StringBuilder();
                        subDesc.append("🔧 Sub-task pre ").append(storyTitle).append("\n\n");
                        String taskDesc = getDirectText(subEl, "Description");
                        if (!taskDesc.isEmpty()) subDesc.append(taskDesc).append("\n\n");

                        String taskEstimate = getDirectText(subEl, "OriginalEstimate");
                        Map<String, String> subExtra = buildExtra(epicComponent, taskEstimate, null);

                        Map<String, Object> subTask = createJiraIssue(taskTitle, subDesc.toString(), "Subtask",
                            projectKey, getDirectText(subEl, "Priority"), null, storyDueDate, storyKey, subExtra);
                        if (subTask != null && subTask.containsKey("key")) {
                            createdIssues.add(subTask);
                            subTaskCount++;
                            log.info("         ↳ Sub-task: {}", subTask.get("key"));
                        }
                    }
                }
            }
        }

        log.info("📊 Scrum: {} sprintov, {} epikov, {} stories, {} sub-taskov (spolu {})",
            sprintCount, epicCount, storyCount, subTaskCount, createdIssues.size());
        return createdIssues;
    }

    // ========== CREATE JIRA ISSUE ==========

    /**
     * Creates a single Jira issue. Returns the created issue map or null on failure.
     * If creation fails due to component/priority/timetracking, retries without them.
     * Errors are collected in exportErrors for user feedback.
     */
    private Map<String, Object> createJiraIssue(String summary, String description, String issueType,
                                                String projectKey, String priority, String labels,
                                                String dueDate, String parentKey, Map<String, String> extraFields) {
        log.info("📝 Vytváram: '{}' [{}] v {}", summary, issueType, projectKey);

        Map<String, Object> descriptionAdf = convertToADF(description);

        // Sanitize labels — replace spaces with hyphens (Jira best practice)
        List<String> labelList = new ArrayList<>();
        if (labels != null && !labels.isBlank()) {
            labelList = Arrays.stream(labels.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("\\s+", "-"))
                .toList();
        }

        // First attempt: with all fields
        Map<String, Object> fields = buildJiraFields(summary, descriptionAdf, issueType, projectKey,
            priority, labelList, dueDate, parentKey, extraFields, true);
        Map<String, Object> result = sendJiraCreate(fields, summary);
        if (result != null) return result;

        // Retry: without component, priority, timetracking (most common causes of 400)
        log.warn("⚠️ Retry bez component/priority/timetracking pre '{}'", summary);
        Map<String, Object> retryFields = buildJiraFields(summary, descriptionAdf, issueType, projectKey,
            null, labelList, dueDate, parentKey, extraFields, false);
        result = sendJiraCreate(retryFields, summary);
        if (result != null) {
            log.info("✅ Retry úspešný pre '{}'", summary);
            return result;
        }

        return null;
    }

    /** Builds the Jira fields map. When includeOptional=false, skips component/priority/timetracking. */
    private Map<String, Object> buildJiraFields(String summary, Map<String, Object> descriptionAdf,
                                                 String issueType, String projectKey,
                                                 String priority, List<String> labels,
                                                 String dueDate, String parentKey,
                                                 Map<String, String> extraFields, boolean includeOptional) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("summary", summary);
        fields.put("description", descriptionAdf);
        fields.put("issuetype", Map.of("name", issueType));

        if (parentKey != null && !parentKey.isBlank()) {
            fields.put("parent", Map.of("key", parentKey.trim()));
        }

        if (includeOptional && priority != null && !priority.isBlank()) {
            fields.put("priority", Map.of("name", priority.trim()));
        }

        if (labels != null && !labels.isEmpty()) {
            fields.put("labels", labels);
        }

        if (dueDate != null && !dueDate.isBlank()) {
            fields.put("duedate", dueDate.trim());
        }

        if (extraFields != null) {
            String sp = extraFields.get("storyPoints");
            if (sp != null && !sp.isBlank()) {
                try {
                    fields.put(storyPointsField, Double.parseDouble(sp.trim()));
                } catch (NumberFormatException e) {
                    log.warn("   Story points '{}' nie je číslo", sp);
                }
            }

            if (includeOptional) {
                String component = extraFields.get("component");
                if (component != null && !component.isBlank()) {
                    fields.put("components", List.of(Map.of("name", component.trim())));
                }

                String estimate = extraFields.get("originalEstimate");
                if (estimate != null && !estimate.isBlank()) {
                    fields.put("timetracking", Map.of("originalEstimate", estimate.trim()));
                }
            }
        }
        return fields;
    }

    /** Sends POST /rest/api/3/issue. Returns result map or null on failure. */
    private Map<String, Object> sendJiraCreate(Map<String, Object> fields, String summary) {
        // Abort entire export if a 401 was already detected
        if (Boolean.TRUE.equals(abortExport.get())) {
            log.warn("⏭ Preskakujem '{}' — export bol prerušený kvôli 401", summary);
            return null;
        }
        try {
            Map<String, Object> body = Map.of("fields", fields);

            Map response = webClient.post()
                .uri("/rest/api/3/issue")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> clientResponse.bodyToMono(String.class)
                        .flatMap(errorBody -> {
                            String errMsg = String.format("HTTP %s — %s", clientResponse.statusCode(), errorBody);
                            log.error("❌ Jira API chyba pre '{}': {}", summary, errMsg);
                            return reactor.core.publisher.Mono.error(
                                new RuntimeException("Jira API: " + errMsg));
                        }))
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(30))
                .block();

            if (response != null && response.get("key") != null) {
                log.info("✅ Vytvorená: {} → {}/browse/{}", response.get("key"), siteUrl, response.get("key"));
                return Map.of(
                    "key", response.get("key"),
                    "id", response.get("id"),
                    "self", response.get("self")
                );
            }
            return null;
        } catch (WebClientResponseException e) {
            String errMsg = String.format("HTTP %s — %s", e.getStatusCode(), e.getResponseBodyAsString());
            log.error("❌ Jira chyba pre '{}': {}", summary, errMsg);
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                abortExport.set(true);
                String msg = "Jira autentifikácia zlyhala (HTTP " + e.getStatusCode().value() + "). "
                    + "Skontroluj JIRA_EMAIL a JIRA_API_TOKEN v .env súbore. "
                    + "Odpoveď: " + e.getResponseBodyAsString();
                exportErrors.get().add(msg);
            } else {
                exportErrors.get().add("[" + summary + "] " + errMsg);
            }
            return null;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("❌ Chyba pre '{}': {}", summary, errMsg);
            // Detect 401 in wrapped RuntimeException (from onStatus handler)
            if (errMsg != null && (errMsg.contains("401") || errMsg.contains("UNAUTHORIZED") || errMsg.contains("403"))) {
                abortExport.set(true);
                String msg = "Jira autentifikácia zlyhala. Skontroluj JIRA_EMAIL a JIRA_API_TOKEN v .env súbore. "
                    + "Detail: " + errMsg;
                exportErrors.get().add(msg);
                log.error("🔐 401/403 detegovaný — export prerušený");
            } else {
                List<String> errors = exportErrors.get();
                if (errors.isEmpty() || !errors.get(errors.size() - 1).contains(summary)) {
                    errors.add("[" + summary + "] " + errMsg);
                }
            }
            return null;
        }
    }

    // ========== ADF CONVERSION ==========

    // package-private for testing
    Map<String, Object> convertToADF(String text) {
        if (text == null || text.isBlank()) {
            return Map.of("type", "doc", "version", 1,
                "content", List.of(Map.of("type", "paragraph",
                    "content", List.of(Map.of("type", "text", "text", " ")))));
        }

        String[] lines = text.split("\n");
        List<Map<String, Object>> content = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            content.add(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", line.trim()))
            ));
        }

        if (content.isEmpty()) {
            content.add(Map.of("type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", text))));
        }

        return Map.of("type", "doc", "version", 1, "content", content);
    }

    // ========== XML HELPERS ==========

    /**
     * FIX: Gets text content of a DIRECT child element only (not nested descendants).
     * Old version used getElementsByTagName which searches all descendants recursively.
     */
    // package-private for testing
    String getDirectText(Element parent, String tagName) {
        if (parent == null) return "";
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    /** Filters elements to only include direct children of the given parent tag. */
    // package-private for testing
    List<Element> filterDirectChildren(NodeList nodeList, String parentTag) {
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Element el = (Element) nodeList.item(i);
            Node parent = el.getParentNode();
            if (parent instanceof Element parentEl && parentTag.equalsIgnoreCase(parentEl.getTagName())) {
                result.add(el);
            }
        }
        return result;
    }

    /** Builds extra fields map, skipping null/empty values. */
    // package-private for testing
    Map<String, String> buildExtra(String component, String originalEstimate, String storyPoints) {
        Map<String, String> extra = new HashMap<>();
        if (component != null && !component.isBlank()) extra.put("component", component);
        if (originalEstimate != null && !originalEstimate.isBlank()) extra.put("originalEstimate", originalEstimate);
        if (storyPoints != null && !storyPoints.isBlank()) extra.put("storyPoints", storyPoints);
        return extra.isEmpty() ? null : extra;
    }

    /** Appends a "key: value\n" line if value is not empty. */
    private void appendIfNotEmpty(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    /** Appends a section with bullet-list items from XML. */
    private void appendListSection(StringBuilder sb, Element parent, String wrapperTag, String itemTag, String header) {
        NodeList wrappers = parent.getElementsByTagName(wrapperTag);
        if (wrappers.getLength() == 0) return;
        Element wrapper = (Element) wrappers.item(0);
        NodeList items = wrapper.getElementsByTagName(itemTag);
        if (items.getLength() == 0) return;
        sb.append(header).append("\n");
        for (int i = 0; i < items.getLength(); i++) {
            String text = items.item(i).getTextContent().trim();
            if (!text.isEmpty()) sb.append("  • ").append(text).append("\n");
        }
        sb.append("\n");
    }
}
