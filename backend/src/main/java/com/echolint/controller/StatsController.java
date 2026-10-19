package com.echolint.controller;

import com.echolint.service.StatsService;
import com.echolint.util.TimeRanges;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计：工作台总览 + 报表统计（登录即可访问）。
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/overview")
    public StatsService.Overview overview() {
        return statsService.overview();
    }

    /**
     * 报表统计：from/to 接受 yyyy-MM-dd 或 ISO 日期时间，均可空（空 = 全部时间）。
     */
    @GetMapping("/report")
    public StatsService.Report report(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        return statsService.report(TimeRanges.parseRange(from, to));
    }
}
