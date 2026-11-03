package com.example.kyc.controller;

import com.example.kyc.dto.CreateStatementRequest;
import com.example.kyc.dto.StatementJobResponse;
import com.example.kyc.service.StatementJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/statements")
public class StatementController {
    private final StatementJobService service;
    public StatementController(StatementJobService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StatementJobResponse create(@RequestHeader("X-User-Id") Long userId,
                                       @Valid @RequestBody CreateStatementRequest request) {
        return service.request(userId, request);
    }

    @GetMapping("/{jobId}")
    public StatementJobResponse get(@PathVariable String jobId,
                                    @RequestHeader("X-User-Id") Long userId) {
        return service.get(jobId, userId);
    }
}
