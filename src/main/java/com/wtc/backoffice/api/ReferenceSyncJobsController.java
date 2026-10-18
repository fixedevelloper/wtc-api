package com.wtc.backoffice.api;

import com.wtc.backoffice.application.ReferenceSyncJobs;
import com.wtc.backoffice.application.ReferenceSyncJobs.Job;
import com.wtc.backoffice.application.ReferenceSyncJobs.Overview;
import com.wtc.shared.security.JwtSubject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Import Wace en arrière-plan avec suivi d'avancement (permission {@code REFERENCE_WRITE}). */
@RestController
@RequestMapping("/api/admin/reference")
class ReferenceSyncJobsController {

    private final ReferenceSyncJobs jobs;

    ReferenceSyncJobsController(ReferenceSyncJobs jobs) {
        this.jobs = jobs;
    }

    record StartRequest(@Pattern(regexp = "^[A-Za-z]{2}$") String countryCode) {}

    @GetMapping("/sync-overview")
    Overview overview() {
        return jobs.overview();
    }

    @GetMapping("/sync-jobs")
    List<Job> recent() {
        return jobs.recent();
    }

    @GetMapping("/sync-jobs/{jobId}")
    Job get(@PathVariable String jobId) {
        return jobs.get(jobId);
    }

    @PostMapping("/sync-jobs")
    ResponseEntity<Job> start(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) StartRequest body) {
        Job job = jobs.start(JwtSubject.userId(jwt), body == null ? null : body.countryCode());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }
}
