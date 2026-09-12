package org.bluesky.training.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.net.*;
import java.io.IOException;
import java.util.*;

/** Starts a dedicated native adapter before publishing the group's HELLO action. */
@Component
public class ManagedEngineProcesses {
    @Value("${bluesky.adapter.process.enabled:false}") private boolean enabled;
    @Value("${bluesky.adapter.process.python:python}") private String python;
    @Value("${bluesky.adapter.process.home:..}") private String home;
    @Value("${bluesky.adapter.process.workdir:./data/engines}") private String workdir;
    private final Map<String, Process> processes = new HashMap<>();

    public synchronized String[] start(String group, String instance, String control, String state) {
        if (!enabled) return new String[]{control, state};
        try {
            Path directory = Paths.get(workdir).toAbsolutePath().resolve(instance);
            Files.createDirectories(directory);
            // Reserve both candidates simultaneously, avoiding reuse of the same ephemeral port.
            int controlPort, statePort;
            try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 ServerSocket second = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                controlPort = first.getLocalPort(); statePort = second.getLocalPort();
            }
            control = "tcp://127.0.0.1:" + controlPort;
            state = "tcp://127.0.0.1:" + statePort;
            Process process = new ProcessBuilder(python, "-u", "-m",
                    "bluesky.plugins.training_adapter.runner_v2", "--exercise-group-id", group,
                    "--engine-instance-id", instance, "--control-endpoint", control,
                    "--state-endpoint", state, "--workdir", directory.toString())
                    .directory(Paths.get(home).toAbsolutePath().toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("adapter.log").toFile()).start();
            processes.put(instance, process);
            long deadline = System.currentTimeMillis() + 60000;
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", controlPort), 100);
                    return new String[]{control, state};
                } catch (IOException notReady) { Thread.sleep(100); }
            }
            process.destroy(); processes.remove(instance);
            throw new IllegalStateException("原生引擎启动失败，参见 " + directory.resolve("adapter.log"));
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("无法启动原生引擎", failure);
        }
    }

    public synchronized void abandon(String instance) {
        Process process = processes.remove(instance);
        if (process != null) process.destroy();
    }

    public synchronized boolean hasStopped(String instance) {
        if (!enabled || instance == null) return false;
        Process process=processes.get(instance);
        if (process != null) return !process.isAlive();
        return instance.equals(receipt(instance,"stopped.json","engineInstanceId"));
    }

    public String processIdentifier(String instance) { return receipt(instance,"process.json","pid"); }

    private String receipt(String instance,String file,String field) {
        try {
            com.fasterxml.jackson.databind.JsonNode node=new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Paths.get(workdir).toAbsolutePath().resolve(instance).resolve(file).toFile());
            return instance.equals(node.path("engineInstanceId").asText())?node.path(field).asText(null):null;
        } catch(IOException unavailable) { return null; }
    }
}
