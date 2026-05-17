package org.example.Controller;

import org.example.Model.Project;
import org.example.Repository.ProjectRepository;
import org.example.Service.OpenAiService;
import org.example.Service.JiraService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@Tag(name = "AI Project Planner API", description = "REST API for generating AI-powered project plans (PRINCE2 & Scrum) and exporting them to Jira")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OpenAiService openAiService;
    private final ProjectRepository projectRepository;
    private final JiraService jiraService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Set<String> exportInProgress = ConcurrentHashMap.newKeySet();

    public ChatController(OpenAiService openAiService, ProjectRepository projectRepository, JiraService jiraService) {
        this.openAiService = openAiService;
        this.projectRepository = projectRepository;
        this.jiraService = jiraService;
    }

    @Operation(summary = "Generate project skeleton",
        description = "Lightweight AI call that proposes a structure (PRINCE2 phases + Scrum sprints) with short descriptions. "
            + "User can edit/add/remove items and disable one methodology before calling /generate.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Skeleton returned as JSON {prince2:[...], scrum:[...]}"),
        @ApiResponse(responseCode = "400", description = "Missing required fields"),
        @ApiResponse(responseCode = "500", description = "AI generation error")
    })
    @PostMapping("/skeleton")
    public ResponseEntity<Map<String, Object>> generateSkeleton(@RequestBody Map<String, Object> request) {
        String name = asString(request.get("name"));
        String description = asString(request.get("description"));

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nazov projektu je povinny"));
        }
        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Popis projektu je povinny"));
        }

        String startDate = asString(request.get("startDate"));
        String deadline = asString(request.get("deadline"));
        String lang = asString(request.get("lang"));
        if (lang == null || lang.isBlank()) lang = "sk";

        try {
            String json = openAiService.generateSkeleton(name, description, startDate, deadline, lang);
            JsonNode parsed = objectMapper.readTree(json);

            Map<String, Object> response = new HashMap<>();
            response.put("prince2", parsed.has("prince2") ? parsed.get("prince2") : new ArrayList<>());
            response.put("scrum", parsed.has("scrum") ? parsed.get("scrum") : new ArrayList<>());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Chyba pri generovani skeletu: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Chyba pri generovani skeletu: " + e.getMessage()
            ));
        }
    }

    @Operation(summary = "Generate project plan",
        description = "Generates PRINCE2 and Scrum project plans in parallel (one AI call per phase/sprint). "
            + "Accepts user-customized phases (PRINCE2) and sprints (Scrum) — each list 1–10 items. "
            + "Pass null or empty list to disable that methodology. At least one methodology must be active.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan generated successfully"),
        @ApiResponse(responseCode = "400", description = "Missing required fields or invalid phases/sprints"),
        @ApiResponse(responseCode = "500", description = "AI generation error")
    })
    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(@RequestBody Map<String, Object> request) {
        String name = asString(request.get("name"));
        String description = asString(request.get("description"));

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nazov projektu je povinny"));
        }
        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Popis projektu je povinny"));
        }

        String startDate = asString(request.get("startDate"));
        String deadline = asString(request.get("deadline"));
        String lang = asString(request.get("lang"));
        if (lang == null || lang.isBlank()) lang = "sk";

        List<OpenAiService.ItemSpec> phases = parseSpecs(request.get("phases"));
        List<OpenAiService.ItemSpec> sprints = parseSpecs(request.get("sprints"));

        if ((phases == null || phases.isEmpty()) && (sprints == null || sprints.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Musi byt vybrana aspon jedna metodologia (PRINCE2 alebo Scrum) s aspon 1 fazou/sprintom."
            ));
        }
        if (phases != null && phases.size() > 10) {
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 10 faz pre PRINCE2."));
        }
        if (sprints != null && sprints.size() > 10) {
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 10 sprintov pre Scrum."));
        }

        try {
            String xmlResponse = openAiService.generateProjectPlan(
                name, description, startDate, deadline, lang, phases, sprints);

            Project project = new Project();
            project.setName(name);
            project.setDescription(description);
            project.setXmlContent(xmlResponse);
            project.setStartDate(startDate);
            project.setDeadline(deadline);
            project.setLanguage(lang);
            projectRepository.save(project);

            return ResponseEntity.ok(Map.of("xml", xmlResponse));

        } catch (Exception e) {
            log.error("Chyba pri generovani planu: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Chyba pri generovani planu: " + e.getMessage()
            ));
        }
    }

    // ---------- helpers ----------

    private static String asString(Object o) {
        if (o == null) return null;
        return o instanceof String s ? s : o.toString();
    }

    /** Parses an incoming JSON list of {name, description} into ItemSpec list. Null-safe. */
    @SuppressWarnings("unchecked")
    private List<OpenAiService.ItemSpec> parseSpecs(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List)) return null;
        List<Object> list = (List<Object>) raw;
        List<OpenAiService.ItemSpec> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            String n = asString(m.get("name"));
            String d = asString(m.get("description"));
            if (n == null || n.isBlank()) continue;
            out.add(new OpenAiService.ItemSpec(n.trim(), d == null ? "" : d.trim()));
        }
        return out;
    }

    @Operation(summary = "Get all projects", description = "Returns all saved projects ordered by ID descending (newest first)")
    @ApiResponse(responseCode = "200", description = "List of projects")
    @GetMapping("/projects")
    public ResponseEntity<List<Project>> getAllProjects() {
        return ResponseEntity.ok(projectRepository.findAllByOrderByIdDesc());
    }

    @Operation(summary = "Get project by ID", description = "Returns a single project by its ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Project found"),
        @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @GetMapping("/projects/{id}")
    public ResponseEntity<Project> getProject(@Parameter(description = "Project ID") @PathVariable Long id) {
        return projectRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update project", description = "Updates project fields (name, description, xmlContent, dates)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Project updated"),
        @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @PutMapping("/projects/{id}")
    public ResponseEntity<Project> updateProject(@Parameter(description = "Project ID") @PathVariable Long id,
                                                  @RequestBody Map<String, String> request) {
        return projectRepository.findById(id)
            .map(existing -> {
                if (request.containsKey("name")) existing.setName(request.get("name"));
                if (request.containsKey("description")) existing.setDescription(request.get("description"));
                if (request.containsKey("xmlContent")) existing.setXmlContent(request.get("xmlContent"));
                if (request.containsKey("startDate")) existing.setStartDate(request.get("startDate"));
                if (request.containsKey("deadline")) existing.setDeadline(request.get("deadline"));
                return ResponseEntity.ok(projectRepository.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete project", description = "Permanently deletes a project by its ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Project deleted"),
        @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> deleteProject(@Parameter(description = "Project ID") @PathVariable Long id) {
        if (!projectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        projectRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Test Jira connection", description = "Verifies Jira API credentials and project access without creating any issues. Returns connected/user/projectFound status.")
    @ApiResponse(responseCode = "200", description = "Connection test result (check 'connected' field)")
    @GetMapping("/jira/test")
    public ResponseEntity<Map<String, Object>> testJiraConnection() {
        log.info("Testing Jira connection...");
        Map<String, Object> result = jiraService.checkJiraConnection();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Export to Jira", description = "Exports a project plan to Jira Cloud. Creates issues hierarchy based on methodology (PRINCE2 stages/tasks or Scrum sprints/epics/stories/subtasks)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Export result (check 'success' field)"),
        @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @PostMapping("/projects/{id}/export-to-jira")
    public ResponseEntity<Map<String, Object>> exportToJira(
            @Parameter(description = "Project ID") @PathVariable Long id,
            @RequestBody Map<String, String> request) {

        String methodology = request.get("methodology");
        String projectName = request.get("projectName");

        String exportKey = id + "-" + methodology;

        log.info("Export do Jira: projectId={}, methodology={}", id, methodology);

        if (exportInProgress.contains(exportKey)) {
            log.warn("Export uz prebieha: {}", exportKey);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "Export uz prebieha, pockajte...");
            return ResponseEntity.ok(result);
        }

        exportInProgress.add(exportKey);

        try {
            return projectRepository.findById(id)
                .map(project -> {
                    try {
                        String nameToUse = (projectName != null && !projectName.isEmpty())
                            ? projectName : project.getName();

                        Map<String, Object> result = jiraService.exportToJira(
                            project.getXmlContent(), methodology, nameToUse);

                        return ResponseEntity.ok(result);
                    } finally {
                        exportInProgress.remove(exportKey);
                    }
                })
                .orElseGet(() -> {
                    exportInProgress.remove(exportKey);
                    return ResponseEntity.notFound().build();
                });
        } catch (Exception e) {
            log.error("Export chyba: {}", e.getMessage(), e);
            exportInProgress.remove(exportKey);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "Chyba: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }
}
