package com.tioka.system.application.audit;

import com.tioka.system.config.AuditProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 可信代理下才解析 {@code X-Forwarded-For}（OBS-01）。
 * {@code tioka.audit.trusted-proxies} 为空时永不信任 XFF。
 */
@Component
public class ClientIpResolver {
    private final AuditProperties props;

    public ClientIpResolver(AuditProperties props) {
        this.props = props;
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrustedProxy(remote)) {
            return remote;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return remote;
        }
        String first = xff.split(",")[0].trim();
        return first.isEmpty() ? remote : first;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        List<String> trusted = props.getTrustedProxies();
        if (trusted == null || trusted.isEmpty() || remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        for (String entry : trusted) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String rule = entry.trim();
            if (rule.contains("/")) {
                if (cidrContains(rule, remoteAddr)) {
                    return true;
                }
            } else if (rule.equalsIgnoreCase(remoteAddr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cidrContains(String cidr, String ip) {
        try {
            String[] parts = cidr.split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            InetAddress address = InetAddress.getByName(ip);
            int prefix = Integer.parseInt(parts[1]);
            byte[] net = network.getAddress();
            byte[] addr = address.getAddress();
            if (net.length != addr.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (net[i] != addr[i]) {
                    return false;
                }
            }
            if (remBits == 0) {
                return true;
            }
            int mask = (~0) << (8 - remBits);
            return (net[fullBytes] & mask) == (addr[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }
}
