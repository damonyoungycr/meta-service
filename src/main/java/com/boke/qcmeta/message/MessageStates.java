package com.boke.qcmeta.message;

import java.util.Locale;
import java.util.Set;

public final class MessageStates {
    private MessageStates() { }
    public static String merge(String current, String observed) {
        String incoming = observed == null ? "" : observed.toUpperCase(Locale.ROOT);
        // 多设备可能先失败后送达。送达/已读是事实；迟到的 sent 不能覆盖失败。
        if ("READ".equals(current) || "READ".equals(incoming)) return "READ";
        if ("DELIVERED".equals(current) || "DELIVERED".equals(incoming)) return "DELIVERED";
        if ("FAILED".equals(incoming)) return "FAILED";
        if ("FAILED".equals(current)) return current;
        if ("SENT".equals(incoming)) return "SENT";
        if ("ACCEPTED".equals(incoming) && Set.of("PENDING", "SUBMITTING", "UNKNOWN", "RETRY_WAIT").contains(current)) return "ACCEPTED";
        return current;
    }
}
