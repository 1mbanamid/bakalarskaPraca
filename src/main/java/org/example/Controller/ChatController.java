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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@Tag(name = "AI Project Planner API", description = "REST API for generating AI-powered project plans (PRINCE2 & Scrum) and exporting them to Jira")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OpenAiService openAiService;
    private final ProjectRepository projectRepository;
    private final JiraService jiraService;

    private final Set<String> exportInProgress = ConcurrentHashMap.newKeySet();

    public ChatController(OpenAiService openAiService, ProjectRepository projectRepository, JiraService jiraService) {
        this.openAiService = openAiService;
        this.projectRepository = projectRepository;
        this.jiraService = jiraService;
    }

    @Operation(summary = "Generate project plan", description = "Generates PRINCE2 and Scrum project plans using Azure OpenAI. Returns XML containing both methodologies.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan generated successfully"),
        @ApiResponse(responseCode = "400", description = "Missing required fields (name or description)"),
        @ApiResponse(responseCode = "500", description = "AI generation error")
    })
    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nazov projektu je povinny"));
        }
        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Popis projektu je povinny"));
        }

        String startDate = request.get("startDate");
        String deadline = request.get("deadline");
        String lang = request.get("lang");
        if (lang == null || lang.isBlank()) lang = "sk";

        try {
            String xmlResponse = openAiService.generateProjectPlan(name, description, startDate, deadline, lang);

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
