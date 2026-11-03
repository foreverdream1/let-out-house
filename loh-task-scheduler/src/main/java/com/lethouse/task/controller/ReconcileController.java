package com.lethouse.task.controller;

import com.lethouse.common.result.R;
import com.lethouse.task.mapper.UsageReconcileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维/联调用的手动触发接口（定时任务不必等 cron）。
 * 生产环境建议加权限控制或直接关闭。
 */
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class ReconcileController {

    private final UsageReconcileMapper usageReconcileMapper;

    @PostMapping("/reconcile")
    public R<Integer> trigger(@RequestParam(defaultValue = "7") int days,
                              @RequestParam(defaultValue = "500") int limit) {
        return R.ok(usageReconcileMapper.backfill(days, limit));
    }
}
