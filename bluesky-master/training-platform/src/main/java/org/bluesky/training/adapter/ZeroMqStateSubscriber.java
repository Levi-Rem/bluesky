package org.bluesky.training.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ZeroMqStateSubscriber implements SmartLifecycle, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZeroMqStateSubscriber.class);
    private static final String TOPIC = "state.GROUP-DEFAULT";

    private final String stateEndpoint;
    private final AdapterStateProjector projector;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ZContext context;
    private volatile Thread thread;

    public ZeroMqStateSubscriber(
            @Value("${bluesky.adapter.state-endpoint}") String stateEndpoint,
            AdapterStateProjector projector,
            @Value("${bluesky.adapter.state-subscription-enabled:true}") boolean enabled) {
        this.stateEndpoint = stateEndpoint;
        this.projector = projector;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        context = new ZContext();
        thread = new Thread(this::receiveLoop, "bluesky-state-subscriber");
        thread.setDaemon(true);
        thread.start();
    }

    private void receiveLoop() {
        ZMQ.Socket socket = context.createSocket(SocketType.SUB);
        socket.setLinger(0);
        socket.setReceiveTimeOut(250);
        socket.subscribe(TOPIC.getBytes(StandardCharsets.UTF_8));
        socket.connect(stateEndpoint);
        try {
            while (running.get()) {
                byte[] topic;
                try {
                    topic = socket.recv(0);
                } catch (ZMQException exception) {
                    if (!running.get()) break;
                    throw exception;
                }
                if (topic == null) continue;
                byte[] body = socket.recv(0);
                if (body == null) continue;
                try {
                    projector.acceptJson(new String(body, StandardCharsets.UTF_8));
                } catch (RuntimeException exception) {
                    LOGGER.warn("忽略单个 BlueSky Adapter 状态帧，后续帧继续处理", exception);
                }
            }
        } finally {
            socket.close();
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        ZContext current = context;
        if (current != null) current.close();
        Thread currentThread = thread;
        if (currentThread != null) {
            try {
                currentThread.join(1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public void close() {
        stop();
    }
}
