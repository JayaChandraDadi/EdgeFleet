package io.edgefleet.controller.api;

import io.edgefleet.controller.config.ConfigService;
import io.edgefleet.controller.device.InventoryService;
import io.edgefleet.controller.rollout.DeploymentService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InventoryService.InvalidInventoryException.class)
    ResponseEntity<Map<String, Object>> inventory(InventoryService.InvalidInventoryException e) {
        return ResponseEntity.unprocessableContent().body(Map.of("error", "inventory rejected", "problems", e.problems()));
    }

    @ExceptionHandler(ConfigService.ConfigException.class)
    ResponseEntity<Map<String, Object>> config(ConfigService.ConfigException e) {
        return ResponseEntity.unprocessableContent().body(Map.of("error", "config invalid", "problems", e.problems()));
    }

    @ExceptionHandler(DeploymentService.PreflightFailedException.class)
    ResponseEntity<Map<String, Object>> preflight(DeploymentService.PreflightFailedException e) {
        return ResponseEntity.unprocessableContent().body(Map.of(
                "error", e.getMessage() + "; no deployment was created",
                "failures", e.failures()));
    }

    @ExceptionHandler(DeploymentService.ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(DeploymentService.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
