package org.bluesky.training.adapter;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * P04：SUB 状态接收与缺口修复（详细设计 10.1.2）。
 * state PUB/SUB 允许丢帧，以序号缺口触发 control 通道的 STATE_SNAPSHOT_GET 修复。
 */
public class AdapterStateSubscriber implements AutoCloseable {

    /** 收到缺口时回调：参数为 engineInstanceId。 */
    public interface RepairCallback {
        void requestRepair(String engineInstanceId);
    }

    private final ProtocolSequenceTracker tracker;
    private final String exerciseGroupId;
    private final String engineInstanceId;
    private final ZContext context = new ZContext();
    private final ProtocolEnvelopeCodec codec = new ProtocolEnvelopeCodec();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    public AdapterStateSubscriber(String exerciseGroupId, String engineInstanceId) {
        this.exerciseGroupId = exerciseGroupId;
        this.engineInstanceId = engineInstanceId;
        this.tracker = new ProtocolSequenceTracker(engineInstanceId);
    }

    public synchronized void subscribe(String endpoint,
                                       Consumer<Map<String, Object>> onFrame,
                                       RepairCallback onGap) {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("订阅线程已启动");
        }
        ZMQ.Socket socket = context.createSocket(SocketType.SUB);
        socket.setLinger(0);
        socket.subscribe(new byte[0]);
        socket.connect(endpoint);
        worker = new Thread(() -> {
            while (running.get()) {
                try {
                    byte[] frame = socket.recv();
                    if (frame == null) {
                        continue;
                    }
                    handleFrameBytes(frame, onFrame, onGap);
                } catch (Exception e) {
                    if (running.get()) {
                        // 单帧解析失败不终止订阅线程
                    }
                }
            }
            socket.close();
        }, "adapter-state-subscriber");
        worker.setDaemon(true);
        worker.start();
    }

    /** 单帧处理：重复忽略；缺口标记 OUT_OF_SYNC 并触发一次修复。 */
    ProtocolSequenceTracker.AcceptResult handleFrame(Map<String, Object> envelope,
                                                     RepairCallback onGap) {
        if (!"EVENT".equals(envelope.get("messageKind"))) {
            throw new AdapterProtocolException("UNEXPECTED_MESSAGE_KIND", "状态通道仅接受 EVENT");
        }
        if (!exerciseGroupId.equals(envelope.get("exerciseGroupId"))) {
            throw new AdapterProtocolException("EXERCISE_GROUP_MISMATCH", "状态帧训练组不匹配");
        }
        if (!engineInstanceId.equals(envelope.get("engineInstanceId"))) {
            throw new AdapterProtocolException("ENGINE_INSTANCE_MISMATCH", "迟到实例状态帧已拒绝");
        }
        ProtocolSequenceTracker.AcceptResult result =
                tracker.acceptInbound(((Number) envelope.get("sequence")).longValue());
        if (result == ProtocolSequenceTracker.AcceptResult.GAP) {
            onGap.requestRepair(tracker.engineInstanceId());
        }
        return result;
    }

    public boolean isOutOfSync() {
        return tracker.isOutOfSync();
    }

    private void handleFrameBytes(byte[] frame,
                                  Consumer<Map<String, Object>> onFrame,
                                  RepairCallback onGap) {
        Map<String, Object> envelope = codec.decode(frame);
        ProtocolSequenceTracker.AcceptResult result = handleFrame(envelope, onGap);
        if (result == ProtocolSequenceTracker.AcceptResult.IN_ORDER) {
            onFrame.accept(envelope);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        context.close();
    }
}
