package org.saiku.web.rest.resources;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.saiku.repository.IRepositoryObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

public interface ISaikuRepository {

    /**
     * Get Saved Queries.
     * @return A list of SavedQuery Objects.
     */
    @GetMapping(produces = "application/json")
    List<IRepositoryObject> getRepository(
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "type", required = false) String type);

    /**
     * Load a resource.
     */
    @GetMapping(path = "/resource", produces = "text/plain")
    ResponseEntity<?> getResource(@RequestParam(name = "file", required = false) String file);

    /**
     * Save a resource.
     */
    @PostMapping("/resource")
    ResponseEntity<?> saveResource(
            @RequestParam(name = "file") String file, @RequestParam(name = "content") String content);

    /**
     * Delete a resource.
     */
    @DeleteMapping("/resource")
    ResponseEntity<?> deleteResource(@RequestParam(name = "file", required = false) String file);
}
