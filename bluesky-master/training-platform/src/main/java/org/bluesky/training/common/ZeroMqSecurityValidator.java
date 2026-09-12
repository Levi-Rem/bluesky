package org.bluesky.training.common;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * P20：ZeroMQ 安全 fail-fast 校验（详细设计 2.2 §14.9/§25.2）。
 * TCP 非 loopback 且未配置 CurveZMQ 时启动失败。
 */
public class ZeroMqSecurityValidator {

    private static final Set<String> LOOPBACK_HOSTS = new HashSet<>(Arrays.asList(
            "localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0"));

    /** 校验 endpoint：非 loopback TCP 必须 CurveZMQ，否则抛 IllegalStateException（fail-fast）。 */
    public void validateLoopbackOrCurve(String endpoint, boolean curveEnabled) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalStateException("ZeroMQ endpoint 不能为空");
        }
        String trimmed = endpoint.trim();
        if (!trimmed.startsWith("tcp://")) {
            // ipc/inproc 限本机
            return;
        }
        String host = hostOf(trimmed);
        if (LOOPBACK_HOSTS.contains(host.toLowerCase())) {
            return;
        }
        if (!curveEnabled) {
            throw new IllegalStateException(String.format(
                    "ZeroMQ endpoint %s 为非 loopback TCP 且未配置 CurveZMQ；"
                            + "跨主机必须启用 CurveZMQ，否则只允许绑定 loopback（详细设计 14.9）", trimmed));
        }
    }

    /** 密钥文件校验：启用 Curve 时必须存在且非空。 */
    public void validateKeyFiles(String secretKeyPath, String publicKeyPath, boolean curveEnabled) {
        if (!curveEnabled) {
            return;
        }
        for (String path : new String[]{secretKeyPath, publicKeyPath}) {
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalStateException("启用 CurveZMQ 时必须提供密钥文件路径");
            }
            java.io.File keyFile = new java.io.File(path.trim());
            if (!keyFile.isFile() || keyFile.length() == 0) {
                throw new IllegalStateException("CurveZMQ 密钥文件缺失或为空: " + path);
            }
        }
    }

    private static String hostOf(String tcpEndpoint) {
        try {
            URI uri = URI.create(tcpEndpoint);
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ZeroMQ endpoint 非法: " + tcpEndpoint);
        }
    }
}
