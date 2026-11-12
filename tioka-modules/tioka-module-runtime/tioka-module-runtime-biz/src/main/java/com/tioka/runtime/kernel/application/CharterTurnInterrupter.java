package com.tioka.runtime.kernel.application;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.governance.api.charter.CharterTurnInterruptPort;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TRU-03（M3-05 §4 / ADR-032）：授权书暂停后取消运行中 Turn（与定案 6 同一检查点——
 * 下一步开始前取消）。复用 {@link TurnSignalService#interrupt}，取消信号经 Redis
 * SignalBus 跨实例生效；MQ 消费者 / 进程内监听在任意实例触发均可。
 */
@Service
public class CharterTurnInterrupter implements CharterTurnInterruptPort {
    private static final Logger log = LoggerFactory.getLogger(CharterTurnInterrupter.class);

    private final RuntimeThreadRepository threads;
    private final TurnRepository turns;
    private final TurnSignalService signals;
    private final PrincipalHolder principals;

    public CharterTurnInterrupter(RuntimeThreadRepository threads,
                                  TurnRepository turns,
                                  TurnSignalService signals,
                                  PrincipalHolder principals) {
        this.threads = threads;
        this.turns = turns;
        this.signals = signals;
        this.principals = principals;
    }

    @Override
    public int interruptActiveTurns(List<String> agentVersionIds, String reason) {
        if (agentVersionIds == null || agentVersionIds.isEmpty()) {
            return 0;
        }
        // 跨空间扫描：以 SYSTEM 绕过 workspace 行级过滤（与 PrincipalTurnRevoker 同口径）
        return principals.runAs(SystemPrincipals.system(), () -> {
            int n = 0;
            for (String versionId : agentVersionIds) {
                for (RuntimeThreadDO thread : threads.listByAgentVersionId(versionId)) {
                    var active = turns.findActive(thread.getId());
                    if (active.isEmpty()) {
                        continue;
                    }
                    try {
                        signals.interrupt(active.get().getId(), reason);
                        n++;
                    } catch (RuntimeException e) {
                        log.warn("charter suspend cancel failed turnId={}: {}",
                                active.get().getId(), e.toString());
                    }
                }
            }
            return n;
        });
    }
}
