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

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    
    private final OpenAiService openAiService;
    private final ProjectRepository projectRepository;
    private final JiraService jiraService;
    
    // Защита от повторных вызовов экспорта
    private final Set<String> exportInProgress = ConcurrentHashMap.newKeySet();

    public ChatController(OpenAiService openAiService, ProjectRepository projectRepository, JiraService jiraService) {
        this.openAiService = openAiService;
        this.projectRepository = projectRepository;
        this.jiraService = jiraService;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");
        
        String xmlResponse = openAiService.generateProjectPlan(name, description);
        
        // Сохраняем проект в БД
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setXmlContent(xmlResponse);
        projectRepository.save(project);
        
        return ResponseEntity.ok(Map.of("xml", xmlResponse));
    }

    @GetMapping("/projects")
    public ResponseEntity<List<Project>> getAllProjects() {
        return ResponseEntity.ok(projectRepository.findAllByOrderByIdDesc());
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<Project> getProject(@PathVariable Long id) {
        return projectRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

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

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

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

