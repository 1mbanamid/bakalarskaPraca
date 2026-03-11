package org.example.Controller;

import org.example.Model.Project;
import org.example.Repository.ProjectRepository;
import org.example.Service.OpenAiService;
import org.example.Service.JiraService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for handling project generation, retrieval, and Jira export operations.
 * 
 * <p>This controller provides endpoints for:
 * <ul>
 *   <li>Generating project plans using OpenAI</li>
 *   <li>Managing projects (CRUD operations)</li>
 *   <li>Exporting projects to Jira</li>
 * </ul>
 * </p>
 * 
 * @author AI Project Planner Team
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    
    private final OpenAiService openAiService;
    private final ProjectRepository projectRepository;
    private final JiraService jiraService;
    
    /**
     * Set to track ongoing export operations to prevent duplicate exports.
     * Key format: "{projectId}-{methodology}"
     */
    private final Set<String> exportInProgress = ConcurrentHashMap.newKeySet();

    /**
     * Constructs a new ChatController with the required services.
     * 
     * @param openAiService the service for generating project plans
     * @param projectRepository the repository for managing projects
     * @param jiraService the service for exporting to Jira
     */
    public ChatController(OpenAiService openAiService, ProjectRepository projectRepository, JiraService jiraService) {
        this.openAiService = openAiService;
        this.projectRepository = projectRepository;
        this.jiraService = jiraService;
    }

    /**
     * Gets a recommendation for which methodology to use for the project.
     * 
     * @param request a map containing "name" and "description" keys
     * @return a response containing the recommended methodology ("scrum" or "prince2") and detailed reasoning
     */
    @PostMapping("/recommend")
    public ResponseEntity<Map<String, String>> recommend(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");
        
        if (name == null || description == null || name.trim().isEmpty() || description.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name and description are required"));
        }
        
        Map<String, String> recommendation = openAiService.getMethodologyRecommendation(name, description);
        return ResponseEntity.ok(recommendation);
    }

    /**
     * Generates a project plan using OpenAI and saves it to the database.
     * 
     * <p>The request should contain:
     * <ul>
     *   <li>"name": the project name</li>
     *   <li>"description": the project description</li>
     *   <li>"methodology": "scrum" or "prince2" (optional, defaults to both if not provided)</li>
     * </ul>
     * </p>
     * 
     * @param request a map containing "name", "description", and optionally "methodology" keys
     * @return a response containing the generated XML project plan
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");
        String methodology = request.get("methodology"); // "scrum" or "prince2" or null for both
        
        if (name == null || description == null || name.trim().isEmpty() || description.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name and description are required"));
        }
        
        String xmlResponse;
        
        if (methodology != null && (methodology.equalsIgnoreCase("scrum") || methodology.equalsIgnoreCase("prince2"))) {
            // Generate single methodology using multi-step approach
            String singleMethodologyXml = openAiService.generateProjectPlan(name, description, methodology.toLowerCase());
            
            // Wrap in ProjectPlans for consistency with frontend expectations
            xmlResponse = "<ProjectPlans>\n" + singleMethodologyXml + "\n</ProjectPlans>";
        } else {
            // Legacy: generate both methodologies
            xmlResponse = openAiService.generateProjectPlan(name, description);
        }
        
        // Сохраняем проект в БД
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setXmlContent(xmlResponse);
        projectRepository.save(project);
        
        return ResponseEntity.ok(Map.of("xml", xmlResponse));
    }

    /**
     * Retrieves all projects from the database, ordered by ID descending (newest first).
     * 
     * @return a list of all projects
     */
    @GetMapping("/projects")
    public ResponseEntity<List<Project>> getAllProjects() {
        return ResponseEntity.ok(projectRepository.findAllByOrderByIdDesc());
    }

    /**
     * Retrieves a specific project by its ID.
     * 
     * @param id the project ID
     * @return the project if found, or 404 Not Found if not found
     */
    @GetMapping("/projects/{id}")
    public ResponseEntity<Project> getProject(@PathVariable Long id) {
        return projectRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Updates an existing project with new data.
     * 
     * @param id the project ID to update
     * @param project the project data to update
     * @return the updated project if found, or 404 Not Found if not found
     */
    @PutMapping("/projects/{id}")
    public ResponseEntity<Project> updateProject(@PathVariable Long id, @RequestBody Project project) {
        return projectRepository.findById(id)
            .map(existing -> {
                existing.setName(project.getName());
                existing.setDescription(project.getDescription());
                existing.setXmlContent(project.getXmlContent());
                return ResponseEntity.ok(projectRepository.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Deletes a project by its ID.
     * 
     * @param id the project ID to delete
     * @return 200 OK if successful
     */
    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Exports a project plan to Jira for the specified methodology.
     * 
     * <p>The request should contain:
     * <ul>
     *   <li>"methodology": either "PRINCE2" or "Scrum"</li>
     *   <li>"projectName": optional project name override for Jira</li>
     * </ul>
     * </p>
     * 
     * <p>This method prevents duplicate exports by tracking ongoing operations.
     * If an export is already in progress for the same project and methodology,
     * it returns an error message.</p>
     * 
     * @param id the project ID to export
     * @param request a map containing "methodology" and optionally "projectName"
     * @return a response containing export results (success, message, issues, projectUrl)
     */
    @PostMapping("/projects/{id}/export-to-jira")
    public ResponseEntity<Map<String, Object>> exportToJira(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        
        String methodology = request.get("methodology"); // "PRINCE2" or "Scrum"
        String projectName = request.get("projectName"); // название проекта для Jira
        
        String exportKey = id + "-" + methodology;
        
        log.info("📤 Požiadavka na export do Jira: projectId={}, methodology={}, projectName={}", 
            id, methodology, projectName);
        
        // Проверяем, не идет ли уже экспорт для этого проекта и методологии
        if (exportInProgress.contains(exportKey)) {
            log.warn("⚠️ Export už prebieha pre projectId={}, methodology={}", id, methodology);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Export už prebieha, počkajte prosím..."
            ));
        }
        
        // Помечаем, что экспорт начался
        exportInProgress.add(exportKey);
        
        try {
            return projectRepository.findById(id)
                .map(project -> {
                    try {
                        log.info("🔍 Nájdený projekt: id={}, name={}", id, project.getName());
                        
                        // Используем переданное название или название из проекта
                        String nameToUse = (projectName != null && !projectName.isEmpty()) 
                            ? projectName 
                            : project.getName();
                        
                        log.info("📋 Používam názov projektu: {}", nameToUse);
                        
                        Map<String, Object> result = jiraService.exportToJira(
                            project.getXmlContent(), 
                            methodology, 
                            nameToUse
                        );
                        
                        log.info("✅ Export dokončený: projectId={}, methodology={}, success={}", 
                            id, methodology, result.get("success"));
                        
                        return ResponseEntity.ok(result);
                    } finally {
                        // Убираем из Set после завершения
                        exportInProgress.remove(exportKey);
                        log.info("🔓 Export ukončený: projectId={}, methodology={}", id, methodology);
                    }
                })
                .orElseGet(() -> {
                    log.error("❌ Projekt nebol nájdený: id={}", id);
                    exportInProgress.remove(exportKey);
                    return ResponseEntity.notFound().build();
                });
        } catch (Exception e) {
            log.error("❌ Chyba pri exporte do Jira: projectId={}, methodology={}, error={}", 
                id, methodology, e.getMessage(), e);
            exportInProgress.remove(exportKey);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Chyba pri exporte: " + e.getMessage()
            ));
        }
    }
}

