package com.Ox08.serious.experiments.webhooka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory хранилище входящих сообщений.
 * Сообщения хранятся до момента вызова внутреннего API со стороны ERP.
 *
 * @since 1.0
 * @author 0x08
 */
@Service
public class MessagesStore {
    private static final Logger LOG = LoggerFactory.getLogger("WEBHOOKA");
    @Value("${app.webhooka.maxMessagesNum}")
    private int maxMessagesLimit;
    private final Map<UUID, InputMessage> messages = new ConcurrentHashMap<>();
    /**
     * Добавление сообщения
     * @param msg
     *         данные - JSON для EDNA и текст для АТС Мегафон
     * @return
     *         true  сообщение добавлено
     *         false - не добавлено
     */
    public boolean addMessage(String msg) {
        if (messages.size() > maxMessagesLimit) {
            LOG.warn("message limit exceeded, ignoring message: {}", msg);
            return false;
        }
        messages.put(UUID.randomUUID(), new InputMessage(msg));
        if (LOG.isDebugEnabled())
            LOG.debug("message added: {}",  msg);

        return true;
    }
    public List<InputMessage> getPendingMessages() {
        if (messages.isEmpty())
            return Collections.emptyList();

        final List<InputMessage> out = new ArrayList<>();
        final Set<UUID> keys= Collections.unmodifiableSet(messages.keySet());

        for (UUID k : keys)
            out.add(messages.remove(k));

        if (LOG.isDebugEnabled())
            LOG.debug("got {} pending messages", out.size());

        return out;
    }

    /**
     * DTO для хранения временных сообщений
     * @param messageBody
     *              данные
     */
    public record InputMessage(String messageBody) {}
}
