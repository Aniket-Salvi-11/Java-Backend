package com.closemore.backend.controller;

import com.closemore.backend.dto.EventLogResponse;
import com.closemore.backend.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The audit log reader. Admin only, most recent 500 entries, never paginated - see AdminService. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    /** GET /api/v1/admin/events-log */
    @GetMapping("/events-log")
    public List<EventLogResponse> eventsLog() {
        return adminService.recentEvents();
    }
}
