package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for AI Project Planner.
 * 
 * <p>This Spring Boot application provides REST API endpoints for generating project plans
 * using Azure OpenAI and exporting them to Jira. It supports both PRINCE2 and Scrum methodologies.</p>
 * 
 * @author AI Project Planner Team
 * @version 1.0
 */
@SpringBootApplication
public class AIPlannerApplication {
    
    /**
     * Main entry point for the application.
     * 
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(AIPlannerApplication.class, args);
    }
}
