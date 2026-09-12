package org.bluesky.training.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.persistence.IdempotencyRecordMapper;
import org.bluesky.training.persistence.IdempotencyRecordRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * P01：幂等执行（详细设计 9.1）。
 * 首次执行把插入、业务动作和完成写入放在同一事务；
 * 同作用域同摘要重放持久化响应，异摘要返回 409 IDEMPOTENCY_KEY_REUSED。
 */
@Service
public class IdempotencyService {

    private static final long REPLAY_WAIT_MILLIS = 5000;
    private static final long POLL_INTERVAL_MILLIS = 50;

    private final IdempotencyRecordMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate replayTransactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IdempotencyService(IdempotencyRecordMapper mapper, TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.replayTransactionTemplate = new TransactionTemplate(
                transactionTemplate.getTransactionManager());
        this.replayTransactionTemplate.setPropagationBehavior(
                TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public <T> IdempotentResult execute(IdempotencyCommand command, Supplier<T> action) {
        try {
            return transactionTemplate.execute(status -> firstExecution(command, action));
        } catch (ScopeAlreadyExistsException exists) {
            return replay(command);
        }
    }

    private <T> IdempotentResult firstExecution(IdempotencyCommand command, Supplier<T> action) {
        try {
            mapper.insert(toRow(command));
        } catch (DuplicateKeyException e) {
            throw new ScopeAlreadyExistsException(e);
        }
        T body = action.get();
        String responseBody = serialize(body);
        mapper.complete(command.scope(), command.firstHttpStatus(), responseBody);
        return new IdempotentResult(false, command.firstHttpStatus(), responseBody);
    }

    private IdempotentResult replay(IdempotencyCommand command) {
        long deadline = System.currentTimeMillis() + REPLAY_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            IdempotencyRecordRow row = replayTransactionTemplate.execute(
                    status -> mapper.find(command.scope()));
            if (row == null) {
                // 首个执行者已回滚或尚未提交；短暂等待后重试
                sleepQuietly();
                continue;
            }
            if (!command.requestDigest().equals(row.getRequestDigest())) {
                throw new V2DomainException("IDEMPOTENCY_KEY_REUSED", 409,
                        "同一幂等键已用于不同请求", Collections.singletonList("Idempotency-Key"));
            }
            if ("COMPLETED".equals(row.getState())) {
                return new IdempotentResult(true,
                        row.getHttpStatus() == null ? 200 : row.getHttpStatus(),
                        row.getResponseBody());
            }
            sleepQuietly();
        }
        throw new V2DomainException("IDEMPOTENCY_KEY_REUSED", 409,
                "同键并发请求仍在执行，请稍后重试",
                Collections.singletonList("Idempotency-Key"));
    }

    private IdempotencyRecordRow toRow(IdempotencyCommand command) {
        IdempotencyRecordRow row = new IdempotencyRecordRow();
        row.setScope(command.scope());
        row.setIdempotencyKey(command.idempotencyKey());
        row.setCallerId(command.caller() == null ? null : command.caller().callerId());
        row.setRequestMethod(command.requestMethod());
        row.setCanonicalPath(command.canonicalPath());
        row.setRequestDigest(command.requestDigest());
        row.setState("RUNNING");
        row.setHttpStatus(command.firstHttpStatus());
        row.setExpiresAt(Timestamp.from(Instant.now().plusSeconds(
                TimeUnit.HOURS.toSeconds(Math.max(1, command.expiresInHours())))));
        return row;
    }

    private String serialize(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String) {
            return (String) body;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应体序列化失败", e);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("幂等重放等待被中断", e);
        }
    }

    /** 内部信号：scope 已存在，转入重放/冲突分支。 */
    private static final class ScopeAlreadyExistsException extends RuntimeException {
        ScopeAlreadyExistsException(Throwable cause) {
            super(cause);
        }
    }
}
