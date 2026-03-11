package org.example.Repository;

import org.example.Model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository interface for managing Project entities.
 * 
 * <p>Provides CRUD operations and custom query methods for Project entities.
 * Extends JpaRepository to inherit standard database operations.</p>
 * 
 * @author AI Project Planner Team
 * @version 1.0
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    
    /**
     * Finds all projects ordered by ID in descending order (newest first).
     * 
     * @return a list of all projects, ordered by ID descending
     */
    List<Project> findAllByOrderByIdDesc();
}

