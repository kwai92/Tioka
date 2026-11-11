package com.tioka.infra.application.notify;

import com.tioka.framework.label.DataLabel;
import com.tioka.governance.api.label.SinkKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NTF 渠道 → 去向种类映射（ADR-040）离线单测。 */
class NtfSinkKindsTest {

    @Test
    void feishuMapsToIm() {
        assertEquals(SinkKind.IM, NtfSinkKinds.kindOf("feishu"));
        assertEquals(SinkKind.IM, NtfSinkKinds.kindOf("FEISHU"));
    }

    @Test
    void emailMapsToMail() {
        assertEquals(SinkKind.MAIL, NtfSinkKinds.kindOf("email"));
        assertEquals(SinkKind.MAIL, NtfSinkKinds.kindOf("EMAIL"));
    }

    @Test
    void unknownAndBlankDefaultToIm() {
        assertEquals(SinkKind.IM, NtfSinkKinds.kindOf("noop"));
        assertEquals(SinkKind.IM, NtfSinkKinds.kindOf("sms"));
        assertEquals(SinkKind.IM, NtfSinkKinds.kindOf(null));
        assertEquals(SinkKind.IM, NtfSinkKinds.kindOf("  "));
    }

    @Test
    void labelSemanticsMatchSinkGuardDefaults() {
        // DefaultSinkGuard 缺省 MAIL=INTERNAL / IM=CONFIDENTIAL（DENY）：
        // INTERNAL 通知两个渠道都可放行；CONFIDENTIAL 通知发 MAIL 被拒、发 IM 放行
        assertTrue(DataLabel.INTERNAL.atMost(DataLabel.INTERNAL));
        assertTrue(DataLabel.CONFIDENTIAL.atMost(DataLabel.CONFIDENTIAL));
        assertFalse(DataLabel.CONFIDENTIAL.atMost(DataLabel.INTERNAL));
    }
}
