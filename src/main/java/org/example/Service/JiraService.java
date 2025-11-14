package org.example.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class JiraService {
    
    private static final Logger log = LoggerFactory.getLogger(JiraService.class);
    
    private final WebClient webClient;
    private final String projectKey;
    private final String siteUrl;
    private final String email;
    
    public JiraService(
        @Value("${jira.site-url}") String siteUrl,
        @Value("${jira.email}") String email,
        @Value("${jira.api-token}") String apiToken,
        @Value("${jira.project-key}") String projectKey
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
        this.email = email;
        
        log.info("JiraService initialized for site: {}, email: {}", siteUrl, email);
    }
    
    public Map<String, Object> exportToJira(String xmlContent, String methodology, String projectName) {
        try {
            log.info("🚀 ========== ZAČÍNAM EXPORT DO JIRA ==========");
            log.info("📋 Metodológia: {}, Názov projektu: {}", methodology, projectName);
            log.info("📄 Dĺžka XML obsahu: {} znakov", xmlContent != null ? xmlContent.length() : 0);
            
            // Извлекаем XML из маркеров или из текста
            String cleanXml = xmlContent;
            if (xmlContent != null && xmlContent.contains("<XML_START>")) {
                log.info("🔍 Nájdené XML_START markery, extrahujem XML...");
                int start = xmlContent.indexOf("<XML_START>") + "<XML_START>".length();
                int end = xmlContent.indexOf("</XML_END>");
                if (end > start) {
                    cleanXml = xmlContent.substring(start, end).trim();
                    log.info("✅ XML extrahovaný, dĺžka: {} znakov", cleanXml.length());
                } else {
                    log.warn("⚠️ XML_END marker nenájdený, používam celý obsah");
                }
            } else {
                // Пытаемся найти XML тег <ProjectPlans> в тексте
                int xmlStart = xmlContent.indexOf("<ProjectPlans>");
                if (xmlStart == -1) {
                    // Пробуем без учета регистра
                    String lowerContent = xmlContent.toLowerCase();
                    int lowerStart = lowerContent.indexOf("<projectplans>");
                    if (lowerStart != -1) {
                        xmlStart = lowerStart;
                    }
                }

                if (xmlStart != -1) {
                    // Ищем закрывающий тег
                    int xmlEnd = xmlContent.indexOf("</ProjectPlans>", xmlStart);
                    if (xmlEnd == -1) {
                        // Пробуем без учета регистра
                        String lowerContent = xmlContent.toLowerCase();
                        int lowerEnd = lowerContent.indexOf("</projectplans>", xmlStart);
                        if (lowerEnd != -1) {
                            xmlEnd = lowerEnd;
                        }
                    }

                    if (xmlEnd != -1) {
                        cleanXml = xmlContent.substring(xmlStart, xmlEnd + "</ProjectPlans>".length()).trim();
                        log.info("✅ XML nájdený v texte (od pozície {} do {}), dĺžka: {} znakov",
                            xmlStart, xmlEnd, cleanXml.length());
                    } else {
                        log.warn("⚠️ Zatvárajúci XML tag nenájdený, používam obsah od pozície {}", xmlStart);
                        cleanXml = xmlContent.substring(xmlStart).trim();
                    }
                } else {
                    log.info("📄 Žiadne XML_START markery ani <ProjectPlans> tag, používam celý obsah");
                }
            }
            
            log.info("🔍 Parsujem XML dokument...");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc;

            try {
                doc = builder.parse(new ByteArrayInputStream(cleanXml.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();
            log.info("✅ XML úspešne sparsovaný, root element: {}", doc.getDocumentElement().getNodeName());
            } catch (Exception e) {
                log.error("❌ Chyba pri parsovaní XML: {}", e.getMessage());
                log.error("📄 XML obsah (prvých 1000 znakov): {}",
                    cleanXml.length() > 1000 ? cleanXml.substring(0, 1000) + "..." : cleanXml);

                // Попытка найти последний корректный закрывающий тег </ProjectPlans>
                int lastProjectPlans = cleanXml.lastIndexOf("</ProjectPlans>");
                if (lastProjectPlans == -1) {
                    String lowerXml = cleanXml.toLowerCase();
                    lastProjectPlans = lowerXml.lastIndexOf("</projectplans>");
                }

                if (lastProjectPlans > 0) {
                    String truncatedXml = cleanXml.substring(0, lastProjectPlans + "</ProjectPlans>".length());
                    log.warn("⚠️ Skúšam použiť XML obrezaný do posledného </ProjectPlans> tagu");
                    try {
                        doc = builder.parse(new ByteArrayInputStream(truncatedXml.getBytes(StandardCharsets.UTF_8)));
                        doc.getDocumentElement().normalize();
                        log.info("✅ XML úspešne sparsovaný po obrezaní, root element: {}", doc.getDocumentElement().getNodeName());
                        cleanXml = truncatedXml; // Обновляем для логирования
                    } catch (Exception e2) {
                        log.error("❌ Chyba pri parsovaní aj obrezaného XML: {}", e2.getMessage());
                        throw new RuntimeException("XML dokument obsahuje chyby a nepodarilo sa ho opraviť. " +
                            "OpenAI mohol generovať neúplný XML. Skúste vytvoriť nový projekt. " +
                            "Pôvodná chyba: " + e.getMessage(), e);
                    }
                } else {
                    throw new RuntimeException("XML dokument obsahuje chyby a neobsahuje korektný </ProjectPlans> tag. " +
                        "OpenAI mohol generovať neúplný XML. Skúste vytvoriť nový projekt. " +
                        "Pôvodná chyba: " + e.getMessage(), e);
                }
            }

            // Используем фиксированный ключ проекта "AIP"
            String fixedProjectKey = "AIP";
            log.info("🔵 Používam existujúci projekt v Jira s kľúčom: {}", fixedProjectKey);
            
            List<Map<String, Object>> createdIssues = new ArrayList<>();
            
            if ("PRINCE2".equals(methodology)) {
                log.info("🔵 Exportujem PRINCE2 úlohy do projektu {}...", fixedProjectKey);
                createdIssues = exportPRINCE2Tasks(doc, fixedProjectKey);
            } else if ("Scrum".equals(methodology)) {
                log.info("🟢 Exportujem Scrum úlohy do projektu {}...", fixedProjectKey);
                createdIssues = exportScrumTasks(doc, fixedProjectKey);
            } else {
                log.error("❌ Neznáma metodológia: {}", methodology);
                throw new IllegalArgumentException("Unknown methodology: " + methodology);
            }
            
            String projectUrl = siteUrl + "/browse/" + fixedProjectKey;
            log.info("🎉 ========== EXPORT DO JIRA DOKONČENÝ ==========");
            log.info("✅ Použitý projekt: {}", fixedProjectKey);
            log.info("✅ Vytvorených úloh: {}", createdIssues.size());
            log.info("🔗 URL projektu: {}", projectUrl);
            
            return Map.of(
                "success", true,
                "message", "Úspešne exportovaných " + createdIssues.size() + " úloh do projektu '" + fixedProjectKey + "' v Jira",
                "issues", createdIssues,
                "projectKey", fixedProjectKey,
                "projectUrl", projectUrl
            );
            
        } catch (Exception e) {
            log.error("❌ ========== CHYBA PRI EXPORTE DO JIRA ==========");
            log.error("❌ Chyba: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return Map.of(
                "success", false,
                "message", "Chyba pri exporte do Jira: " + e.getMessage()
            );
        }
    }
    
    private List<Map<String, Object>> exportPRINCE2Tasks(Document doc, String projectKey) {
        List<Map<String, Object>> createdIssues = new ArrayList<>();
        
        NodeList prince2Stages = doc.getElementsByTagName("Stage");
        log.info("🔵 Našiel som {} PRINCE2 stage pre export do projektu {}", prince2Stages.getLength(), projectKey);

        int stageCount = 0;
        int subTaskCount = 0;
        
        for (int i = 0; i < prince2Stages.getLength(); i++) {
            Element stage = (Element) prince2Stages.item(i);
            
            String summary = getTextContent(stage, "Name");
            if (summary.isEmpty()) {
                summary = "PRINCE2 Stage " + (i + 1);
            }
            String stageId = getTextContent(stage, "StageID");
            String description = getTextContent(stage, "Description");
            String startDate = getTextContent(stage, "StartDate");
            String endDate = getTextContent(stage, "EndDate");
            String dueDate = getTextContent(stage, "DueDate");
            String budget = getTextContent(stage, "Budget");
            String priority = getTextContent(stage, "Priority");
            String manager = getTextContent(stage, "Manager");
            String assignee = getTextContent(stage, "Assignee");
            String labels = getTextContent(stage, "Labels");
            
            StringBuilder fullDescription = new StringBuilder();
            fullDescription.append("📋 PRINCE2 Stage\n\n");
            if (!description.isEmpty()) {
                fullDescription.append(description).append("\n\n");
            }
            if (!stageId.isEmpty()) {
                fullDescription.append("Stage ID: ").append(stageId).append("\n");
            }
            if (!manager.isEmpty()) {
                fullDescription.append("Manager: ").append(manager).append("\n");
            }
            if (!assignee.isEmpty()) {
                fullDescription.append("Navrhovaný assignee: ").append(assignee).append("\n");
            }
            if (!startDate.isEmpty()) {
                fullDescription.append("Start Date: ").append(startDate).append("\n");
            }
            if (!endDate.isEmpty()) {
                fullDescription.append("End Date: ").append(endDate).append("\n");
            }
            if (!dueDate.isEmpty()) {
                fullDescription.append("Due Date: ").append(dueDate).append("\n");
            }
            if (!budget.isEmpty()) {
                fullDescription.append("Budget: ").append(budget).append(" EUR\n");
            }
            fullDescription.append("\n");

            NodeList outputs = stage.getElementsByTagName("Output");
            if (outputs.getLength() > 0) {
                fullDescription.append("📦 Výstupy:\n");
                for (int j = 0; j < outputs.getLength(); j++) {
                    String output = outputs.item(j).getTextContent().trim();
                    if (!output.isEmpty()) {
                        fullDescription.append("  • ").append(output).append("\n");
                    }
                }
                fullDescription.append("\n");
            }

            NodeList risks = stage.getElementsByTagName("Risk");
            if (risks.getLength() > 0) {
                fullDescription.append("⚠️ Riziká:\n");
                for (int j = 0; j < risks.getLength(); j++) {
                    String risk = risks.item(j).getTextContent().trim();
                    if (!risk.isEmpty()) {
                        fullDescription.append("  • ").append(risk).append("\n");
                    }
                }
                fullDescription.append("\n");
            }

            NodeList taskNodes = stage.getElementsByTagName("Task");
            if (taskNodes.getLength() > 0) {
                fullDescription.append("🛠️ Úlohy (budú vytvorené ako sub-task):\n");
                for (int j = 0; j < taskNodes.getLength(); j++) {
                    Element stageTask = (Element) taskNodes.item(j);
                    String taskTitle = getTextContent(stageTask, "Title");
                    if (taskTitle.isEmpty()) {
                        taskTitle = "Úloha " + (j + 1);
                    }
                    fullDescription.append("  • ").append(taskTitle).append("\n");
                }
                fullDescription.append("\n");
            }
            
            log.info("📤 Exportujem PRINCE2 stage: {} (ID: {}) do projektu {}", summary, stageId, projectKey);
            log.debug("   Priority: {}, Assignee: {}, Labels: {}, DueDate: {}", priority, assignee, labels, dueDate);
            
            Map<String, Object> issue = createJiraIssue(summary, fullDescription.toString(), "Task", projectKey,
                priority, assignee, labels, dueDate);
            if (issue != null && issue.containsKey("key")) {
                createdIssues.add(issue);
                stageCount++;
                String parentKey = issue.get("key").toString();
                log.info("✅ Vytvorený PRINCE2 stage: {}", parentKey);

                for (int j = 0; j < taskNodes.getLength(); j++) {
                    Element taskElement = (Element) taskNodes.item(j);
                    String taskTitle = getTextContent(taskElement, "Title");
                    if (taskTitle.isEmpty()) {
                        taskTitle = summary + " - úloha " + (j + 1);
                    }
                    String taskDescription = getTextContent(taskElement, "Description");
                    String taskPriority = getTextContent(taskElement, "Priority");
                    String taskDueDate = getTextContent(taskElement, "DueDate");
                    String taskAssignee = getTextContent(taskElement, "Assignee");
                    if (taskAssignee.isEmpty()) {
                        taskAssignee = assignee;
                    }
                    String taskLabels = getTextContent(taskElement, "Labels");

                    StringBuilder subTaskDescription = new StringBuilder();
                    subTaskDescription.append("🔧 Úloha v rámci stage ").append(summary).append("\n\n");
                    if (!taskDescription.isEmpty()) {
                        subTaskDescription.append(taskDescription).append("\n\n");
                    }
                    if (!stageId.isEmpty()) {
                        subTaskDescription.append("Stage ID: ").append(stageId).append("\n");
                    }
                    if (!taskAssignee.isEmpty()) {
                        subTaskDescription.append("Navrhovaný assignee: ").append(taskAssignee).append("\n");
                    }

                    Map<String, Object> subTask = createJiraIssue(taskTitle, subTaskDescription.toString(), "Sub-task",
                        projectKey, taskPriority, taskAssignee, taskLabels, taskDueDate, parentKey);
                    if (subTask != null && subTask.containsKey("key")) {
                        createdIssues.add(subTask);
                        subTaskCount++;
                        log.info("   ↳ Vytvorený sub-task: {}", subTask.get("key"));
                    } else {
                        log.warn("   ⚠️ Nepodarilo sa vytvoriť sub-task: {}", taskTitle);
                    }
                }
            } else {
                log.warn("⚠️ Nepodarilo sa vytvoriť PRINCE2 stage: {}", summary);
            }
        }
        
        log.info("📊 Exportovaných {} PRINCE2 stage a {} sub-taskov (spolu {} úloh)",
            stageCount, subTaskCount, createdIssues.size());
        return createdIssues;
    }
    
    private List<Map<String, Object>> exportScrumTasks(Document doc, String projectKey) {
        List<Map<String, Object>> createdIssues = new ArrayList<>();
        
        Element scrumProject = (Element) doc.getElementsByTagName("ScrumProject").item(0);
        if (scrumProject == null) {
            log.warn("⚠️ ScrumProject tag nebol nájdený v XML.");
            return createdIssues;
        }

        NodeList sprintNodes = scrumProject.getElementsByTagName("Sprint");
        log.info("🟢 Našiel som {} Sprintov pre export do projektu {}", sprintNodes.getLength(), projectKey);

        int sprintCount = 0;
        int epicCount = 0;
        int storyCount = 0;
        int subTaskCount = 0;

        for (int i = 0; i < sprintNodes.getLength(); i++) {
            Element sprint = (Element) sprintNodes.item(i);
            if (!isDirectChild(sprint, "Sprints")) {
                continue;
            }
            
            String name = getTextContent(sprint, "Name");
            if (name.isEmpty()) {
                name = "Sprint " + (i + 1);
            }
            String goal = getTextContent(sprint, "Goal");
            String description = getTextContent(sprint, "Description");
            String startDate = getTextContent(sprint, "StartDate");
            String endDate = getTextContent(sprint, "EndDate");
            String priority = getTextContent(sprint, "Priority");
            String assignee = getTextContent(sprint, "Assignee");
            String labels = getTextContent(sprint, "Labels");
            
            StringBuilder sprintDescription = new StringBuilder();
            sprintDescription.append("🏃 Scrum Sprint\n\n");
            if (!description.isEmpty()) {
                sprintDescription.append(description).append("\n\n");
            }
            if (!goal.isEmpty()) {
                sprintDescription.append("Goal: ").append(goal).append("\n");
            }
            if (!startDate.isEmpty()) {
                sprintDescription.append("Start Date: ").append(startDate).append("\n");
            }
            if (!endDate.isEmpty()) {
                sprintDescription.append("End Date: ").append(endDate).append("\n");
            }
            if (!assignee.isEmpty()) {
                sprintDescription.append("Scrum Master: ").append(assignee).append("\n");
            }
            sprintDescription.append("\n");

            NodeList sprintEpics = sprint.getElementsByTagName("Epic");
            if (sprintEpics.getLength() > 0) {
                sprintDescription.append("Epics v sprinte:\n");
                for (int j = 0; j < sprintEpics.getLength(); j++) {
                    Element epic = (Element) sprintEpics.item(j);
                    if (!isDirectChild(epic, "Epics")) {
                        continue;
                    }
                    String epicTitle = getTextContent(epic, "Title");
                    if (epicTitle.isEmpty()) {
                        epicTitle = "Epic " + (j + 1);
                    }
                    sprintDescription.append("  • ").append(epicTitle).append("\n");
                }
                sprintDescription.append("\n");
            }

            log.info("📤 Exportujem Sprint: {} do projektu {}", name, projectKey);
            Map<String, Object> sprintIssue = createJiraIssue(name, sprintDescription.toString(), "Task", projectKey,
                priority, assignee, labels, endDate);
            String sprintKey = null;
            if (sprintIssue != null && sprintIssue.containsKey("key")) {
                createdIssues.add(sprintIssue);
                sprintCount++;
                sprintKey = sprintIssue.get("key").toString();
                log.info("✅ Vytvorený Sprint: {}", sprintKey);
            } else {
                log.warn("⚠️ Nepodarilo sa vytvoriť Sprint: {}", name);
            }

            NodeList epicNodes = sprint.getElementsByTagName("Epic");
            for (int j = 0; j < epicNodes.getLength(); j++) {
                Element epic = (Element) epicNodes.item(j);
                if (!isDirectChild(epic, "Epics")) {
                    continue;
                }

                String epicTitle = getTextContent(epic, "Title");
                if (epicTitle.isEmpty()) {
                    epicTitle = (name + " - Epic " + (j + 1)).trim();
                }
                String epicDescription = getTextContent(epic, "Description");
                String epicPriority = getTextContent(epic, "Priority");
                String epicAssignee = getTextContent(epic, "Assignee");
                String epicLabels = getTextContent(epic, "Labels");

                StringBuilder epicDescBuilder = new StringBuilder();
                epicDescBuilder.append("🚀 Epic v sprinte ").append(name).append("\n\n");
                if (!epicDescription.isEmpty()) {
                    epicDescBuilder.append(epicDescription).append("\n\n");
                }
                if (!epicAssignee.isEmpty()) {
                    epicDescBuilder.append("Owner: ").append(epicAssignee).append("\n");
                }
                if (sprintKey != null) {
                    epicDescBuilder.append("Sprint Jira key: ").append(sprintKey).append("\n");
                }
                epicDescBuilder.append("\n");

                NodeList storyNodesForSummary = epic.getElementsByTagName("Story");
                if (storyNodesForSummary.getLength() > 0) {
                    epicDescBuilder.append("User Stories:\n");
                    for (int k = 0; k < storyNodesForSummary.getLength(); k++) {
                        Element story = (Element) storyNodesForSummary.item(k);
                        if (!isDirectChild(story, "Stories")) {
                            continue;
                        }
                        String storyTitle = getTextContent(story, "Title");
                        if (storyTitle.isEmpty()) {
                            storyTitle = "User Story " + (k + 1);
                        }
                        epicDescBuilder.append("  • ").append(storyTitle).append("\n");
                    }
                    epicDescBuilder.append("\n");
                }

                Map<String, Object> epicIssue = createJiraIssue(epicTitle, epicDescBuilder.toString(), "Task",
                    projectKey, epicPriority, epicAssignee, epicLabels, endDate);
                String epicKey = null;
                if (epicIssue != null && epicIssue.containsKey("key")) {
                    createdIssues.add(epicIssue);
                    epicCount++;
                    epicKey = epicIssue.get("key").toString();
                    log.info("   ✅ Vytvorený Epic: {}", epicKey);
                } else {
                    log.warn("   ⚠️ Nepodarilo sa vytvoriť Epic: {}", epicTitle);
                }

                NodeList storyNodes = epic.getElementsByTagName("Story");
                for (int k = 0; k < storyNodes.getLength(); k++) {
                    Element story = (Element) storyNodes.item(k);
                    if (!isDirectChild(story, "Stories")) {
                        continue;
                    }

                    String storyTitle = getTextContent(story, "Title");
                    if (storyTitle.isEmpty()) {
                        storyTitle = epicTitle + " - Story " + (k + 1);
                    }
                    String storyDescription = getTextContent(story, "Description");
                    String storyPoints = getTextContent(story, "StoryPoints");
                    String storyPriority = getTextContent(story, "Priority");
                    String storyDueDate = getTextContent(story, "DueDate");
                    if (storyDueDate.isEmpty()) {
                        storyDueDate = endDate;
                    }
                    String storyAssignee = getTextContent(story, "Assignee");
                    String storyLabels = getTextContent(story, "Labels");

                    StringBuilder storyDescBuilder = new StringBuilder();
                    storyDescBuilder.append("🧩 User Story v epic ").append(epicTitle).append("\n\n");
                    if (!storyDescription.isEmpty()) {
                        storyDescBuilder.append(storyDescription).append("\n\n");
                    }
                    if (!storyPoints.isEmpty()) {
                        storyDescBuilder.append("Story Points: ").append(storyPoints).append("\n");
                    }
                    if (epicKey != null) {
                        storyDescBuilder.append("Epic Jira key: ").append(epicKey).append("\n");
                    }
                    storyDescBuilder.append("\n");

                    NodeList criteria = story.getElementsByTagName("Criterion");
                    if (criteria.getLength() > 0) {
                        storyDescBuilder.append("✅ Acceptance Criteria:\n");
                        for (int c = 0; c < criteria.getLength(); c++) {
                            String criterion = criteria.item(c).getTextContent().trim();
                            if (!criterion.isEmpty()) {
                                storyDescBuilder.append("  • ").append(criterion).append("\n");
                            }
                        }
                        storyDescBuilder.append("\n");
                    }

                    NodeList storySubTasks = story.getElementsByTagName("Task");
                    if (storySubTasks.getLength() > 0) {
                        storyDescBuilder.append("🛠️ Sub-tasky (budú vytvorené):\n");
                        for (int st = 0; st < storySubTasks.getLength(); st++) {
                            Element sub = (Element) storySubTasks.item(st);
                            if (!isDirectChild(sub, "SubTasks")) {
                                continue;
                            }
                            String subTitle = getTextContent(sub, "Title");
                            if (subTitle.isEmpty()) {
                                subTitle = "Sub-task " + (st + 1);
                            }
                            storyDescBuilder.append("  • ").append(subTitle).append("\n");
                        }
                        storyDescBuilder.append("\n");
                    }

                    Map<String, Object> storyIssue = createJiraIssue(storyTitle, storyDescBuilder.toString(), "Task",
                        projectKey, storyPriority, storyAssignee, storyLabels, storyDueDate);
                    String storyKey = null;
            if (storyIssue != null && storyIssue.containsKey("key")) {
                createdIssues.add(storyIssue);
                        storyCount++;
                        storyKey = storyIssue.get("key").toString();
                        log.info("      ✅ Vytvorená User Story: {}", storyKey);
                    } else {
                        log.warn("      ⚠️ Nepodarilo sa vytvoriť User Story: {}", storyTitle);
                    }

                    if (storyKey == null) {
                        continue;
                    }

                    for (int st = 0; st < storySubTasks.getLength(); st++) {
                        Element subTaskElement = (Element) storySubTasks.item(st);
                        if (!isDirectChild(subTaskElement, "SubTasks")) {
                            continue;
                        }

                        String taskTitle = getTextContent(subTaskElement, "Title");
                        if (taskTitle.isEmpty()) {
                            taskTitle = storyTitle + " - sub-task " + (st + 1);
                        }
                        String taskDescription = getTextContent(subTaskElement, "Description");
                        String taskPriority = getTextContent(subTaskElement, "Priority");
                        String taskAssignee = getTextContent(subTaskElement, "Assignee");
                        if (taskAssignee.isEmpty()) {
                            taskAssignee = storyAssignee;
                        }

                        StringBuilder subTaskDescription = new StringBuilder();
                        subTaskDescription.append("🔧 Sub-task pre user story ").append(storyTitle).append("\n\n");
                        if (!taskDescription.isEmpty()) {
                            subTaskDescription.append(taskDescription).append("\n\n");
                        }
                        if (!taskAssignee.isEmpty()) {
                            subTaskDescription.append("Navrhovaný assignee: ").append(taskAssignee).append("\n");
                        }

                        Map<String, Object> subTask = createJiraIssue(taskTitle, subTaskDescription.toString(), "Sub-task",
                            projectKey, taskPriority, taskAssignee, null, storyDueDate, storyKey);
                        if (subTask != null && subTask.containsKey("key")) {
                            createdIssues.add(subTask);
                            subTaskCount++;
                            log.info("         ↳ Vytvorený sub-task: {}", subTask.get("key"));
            } else {
                            log.warn("         ⚠️ Nepodarilo sa vytvoriť sub-task: {}", taskTitle);
                        }
                    }
                }
            }
        }
        
        log.info("📊 Export výsledok: {} sprintov, {} epikov, {} user stories a {} sub-taskov (spolu {} úloh)",
            sprintCount, epicCount, storyCount, subTaskCount, createdIssues.size());
        return createdIssues;
    }
    
    private boolean isDirectChild(Element element, String parentTag) {
        if (element == null || parentTag == null) {
            return false;
        }
        org.w3c.dom.Node parent = element.getParentNode();
        return parent instanceof Element parentElement && parentTag.equalsIgnoreCase(parentElement.getTagName());
    }
    
    private Map<String, Object> createJiraIssue(String summary, String description, String issueType, String projectKey,
                                               String priority, String assignee, String labels, String dueDate) {
        return createJiraIssue(summary, description, issueType, projectKey, priority, assignee, labels, dueDate, null);
    }

    private Map<String, Object> createJiraIssue(String summary, String description, String issueType, String projectKey,
                                               String priority, String assignee, String labels, String dueDate, String parentKey) {
        try {
            log.info("📝 Vytváram Jira úlohu: summary='{}', type='{}', project='{}'", summary, issueType, projectKey);
            log.debug("   Priority: '{}', Assignee: '{}', Labels: '{}', DueDate: '{}'", priority, assignee, labels, dueDate);
            
            // Конвертируем description в ADF формат для Jira
            Map<String, Object> descriptionAdf = convertToADF(description);
            
            Map<String, Object> fields = new HashMap<>();
            fields.put("project", Map.of("key", projectKey));
            fields.put("summary", summary);
            fields.put("description", descriptionAdf);
            fields.put("issuetype", Map.of("name", issueType));
            
            if (parentKey != null && !parentKey.trim().isEmpty()) {
                fields.put("parent", Map.of("key", parentKey.trim()));
            }
            
            // Priority убрано - в бесплатной версии Jira Cloud может быть недоступно
            // Если нужно установить priority, это можно сделать через UI после создания задачи
            // if (priority != null && !priority.trim().isEmpty()) { ... }

            // Assignee убран - в бесплатной версии Jira Cloud может требовать специальный accountId
            // Если нужно назначить исполнителя, это можно сделать через UI после создания задачи
            // if (assignee != null && !assignee.trim().isEmpty()) { ... }
            
            // Добавляем Labels если указаны
            if (labels != null && !labels.trim().isEmpty()) {
                try {
                    String[] labelArray = labels.split(",");
                    List<String> labelList = new ArrayList<>();
                    for (String label : labelArray) {
                        String trimmed = label.trim();
                        if (!trimmed.isEmpty()) {
                            labelList.add(trimmed);
                        }
                    }
                    if (!labelList.isEmpty()) {
                        fields.put("labels", labelList);
                        log.debug("   Nastavené labels: {}", labelList);
                    }
                } catch (Exception e) {
                    log.warn("   Nepodarilo sa nastaviť labels '{}': {}", labels, e.getMessage());
                }
            }
            
            // Добавляем DueDate если указан (формат: YYYY-MM-DD)
            if (dueDate != null && !dueDate.trim().isEmpty()) {
                try {
                    fields.put("duedate", dueDate.trim());
                    log.debug("   Nastavený dueDate: {}", dueDate);
                } catch (Exception e) {
                    log.warn("   Nepodarilo sa nastaviť dueDate '{}': {}", dueDate, e.getMessage());
                }
            }
            
            Map<String, Object> body = Map.of("fields", fields);
            
            log.debug("📤 Odosielam požiadavku na vytvorenie úlohy: {}", body);
            
            Map response = webClient.post()
                .uri("/rest/api/3/issue")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), 
                    clientResponse -> {
                        // Логируем ошибку без block() - используем doOnNext для неблокирующего логирования
                        clientResponse.bodyToMono(String.class)
                            .doOnNext(errorBody -> {
                                log.error("❌ Jira API chyba pri vytváraní úlohy '{}': HTTP {} - {}", 
                                    summary, clientResponse.statusCode(), errorBody);
                            })
                            .subscribe(); // Subscribe вместо block() для неблокирующего чтения
                        return clientResponse.createException();
                    })
                .bodyToMono(Map.class)
                .doOnError(error -> {
                    log.error("❌ Chyba pri vytváraní Jira úlohy '{}': {} - {}", 
                        summary, error.getClass().getSimpleName(), error.getMessage());
                })
                .timeout(java.time.Duration.ofSeconds(30))
                .block();
            
            if (response != null) {
                log.info("✅ Úspešne vytvorená Jira úloha: {} - URL: {}/browse/{}", 
                    response.get("key"), siteUrl, response.get("key"));
                return Map.of(
                    "key", response.get("key"),
                    "id", response.get("id"),
                    "self", response.get("self")
                );
            }
            
            log.error("❌ Odpoveď od Jira API je null pre úlohu '{}'", summary);
            return null;
            
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("❌ Chyba pri vytváraní Jira úlohy '{}' (type: {}, project: {}): HTTP {} - {}", 
                summary, issueType, projectKey, e.getStatusCode(), e.getResponseBodyAsString(), e);
            return null;
        } catch (Exception e) {
            log.error("❌ Neočakávaná chyba pri vytváraní Jira úlohy '{}': {} - {}", 
                summary, e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Конвертирует текстовое описание в формат Atlassian Document Format (ADF) для Jira.
     * Все задачи создаются через /rest/api/3/issue и добавляются в существующий проект "AIPLAN".
     * Создание новых проектов через API не поддерживается в бесплатной версии Jira Cloud.
     */
    private Map<String, Object> convertToADF(String text) {
        // Конвертируем текст в Atlassian Document Format (ADF)
        String[] lines = text.split("\n");
        List<Map<String, Object>> content = new ArrayList<>();
        
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            content.add(Map.of(
                "type", "paragraph",
                "content", List.of(
                    Map.of("type", "text", "text", line.trim())
                )
            ));
        }
        
        return Map.of(
            "type", "doc",
            "version", 1,
            "content", content.isEmpty() ? List.of(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", text))
            )) : content
        );
    }
    
    private String getTextContent(Element parent, String tagName) {
        try {
            NodeList nodeList = parent.getElementsByTagName(tagName);
            if (nodeList.getLength() > 0) {
                return nodeList.item(0).getTextContent().trim();
            }
        } catch (Exception e) {
            log.warn("Could not get text content for tag: {}", tagName);
        }
        return "";
    }
}

