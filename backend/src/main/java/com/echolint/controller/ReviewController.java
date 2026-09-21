package com.echolint.controller;

import com.echolint.dto.ReviewRequest;
import com.echolint.entity.Recording;
import com.echolint.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 人工复检
 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/{id}")
    public Recording review(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return reviewService.review(id, request);
    }
}