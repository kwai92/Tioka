package com.tioka.system.application.egress;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.EgressGuard;
import com.tioka.system.config.EgressProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DefaultEgressGuard implements EgressGuard {
    private final EgressProperties props;
    private final List<Cidr> denyCidrs;
    private final List<Cidr> privateCidrs = List.of(
            Cidr.parse("10.0.0.0/8"),
            Cidr.parse("172.16.0.0/12"),
            Cidr.parse("192.168.0.0/16"),
            Cidr.parse("127.0.0.0/8"),
            Cidr.parse("::1/128"),
            Cidr.parse("fc00::/7"),
            Cidr.parse("fe80::/10")
    );

    public DefaultEgressGuard(EgressProperties props,
                              @Value("${tioka.airgap.enabled:false}") boolean legacyAirgap) {
        if (legacyAirgap && props.getMode() == EgressProperties.Mode.OPEN
                && (props.getAllowHosts() == null || props.getAllowHosts().isEmpty())) {
            props.setMode(EgressProperties.Mode.ALLOW_LIST);
            props.setAllowHosts(new ArrayList<>(List.of("localhost", "127.0.0.1")));
        }
        this.props = props;
        List<Cidr> deny = new ArrayList<>();
        for (String c : props.getDenyCidrs() == null ? List.<String>of() : props.getDenyCidrs()) {
            deny.add(Cidr.parse(c));
        }
        this.denyCidrs = List.copyOf(deny);
    }

    @Override
    public void assertAllowed(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "url 为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "url 无法解析");
        }
        String scheme = lower(uri.getScheme());
        if (scheme == null || !Set.of("http", "https").contains(scheme)) {
            deny("scheme", rawUrl);
        }
        String hostRaw = uri.getHost();
        if (hostRaw == null || hostRaw.isBlank()) {
            deny("host", rawUrl);
        }
        String host = IDN.toASCII(lower(hostRaw));
        if (props.getMode() == EgressProperties.Mode.OPEN) {
            return;
        }
        if (matchesAllowList(host)) {
            if (props.getMode() == EgressProperties.Mode.STRICT) {
                checkResolved(host);
            }
            return;
        }
        deny("not-allowed", rawUrl);
    }

    public boolean isRestrictive() {
        return props.getMode() != EgressProperties.Mode.OPEN;
    }

    boolean matchesAllowList(String host) {
        List<String> allow = props.getAllowHosts();
        if (allow == null || allow.isEmpty()) {
            return false;
        }
        for (String pattern : allow) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String p = lower(pattern.trim());
            if (p.startsWith("*.")) {
                String suffix = p.substring(1); // ".example.com"
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private void checkResolved(String host) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            deny("dns", host);
            return;
        }
        for (InetAddress addr : addrs) {
            byte[] bytes = addr.getAddress();
            for (Cidr c : denyCidrs) {
                if (c.contains(bytes)) {
                    deny("deny-cidr", host);
                }
            }
            if (!props.isAllowPrivate()) {
                for (Cidr c : privateCidrs) {
                    if (c.contains(bytes)) {
                        deny("private", host);
                    }
                }
            }
        }
    }

    private static void deny(String reason, String rawUrl) {
        throw new BizException(ErrorCode.EGRESS_DENIED, "出网拒绝(" + reason + "): " + rawUrl);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    static final class Cidr {
        private final byte[] network;
        private final int prefix;

        private Cidr(byte[] network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }

        static Cidr parse(String raw) {
            String[] parts = raw.split("/");
            try {
                byte[] addr = InetAddress.getByName(parts[0]).getAddress();
                int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : addr.length * 8;
                return new Cidr(addr, prefix);
            } catch (Exception e) {
                throw new IllegalArgumentException("bad cidr: " + raw, e);
            }
        }

        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            int rem = prefix % 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (rem == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rem);
            return (addr[full] & mask) == (network[full] & mask);
        }
    }
}
