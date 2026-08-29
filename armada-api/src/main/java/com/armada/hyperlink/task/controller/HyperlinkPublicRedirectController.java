package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.service.HyperlinkPublicClickService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 无认证公网短链入口。 */
@RestController
@RequestMapping("/api/public/hl")
public class HyperlinkPublicRedirectController {
    private final HyperlinkPublicClickService clickService;

    public HyperlinkPublicRedirectController(HyperlinkPublicClickService clickService) {
        this.clickService = clickService;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode,
            HttpServletRequest request) {
        var outcome = clickService.visit(shortCode, request);
        if (outcome.status() == HyperlinkPublicClickService.RedirectOutcome.Status.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (outcome.status() == HyperlinkPublicClickService.RedirectOutcome.Status.GONE) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(outcome.targetUrl()))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
