package com.omnichannel.support.service;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.Task;

public final class CustomerRequestIds {

    private static final String CONVERSATION_PREFIX = "conversation_";
    private static final String JTBD_PREFIX = "jtbd_";
    private static final String TICKET_PREFIX = "task_";

    private CustomerRequestIds() {}

    public static String forConversation(Conversation conversation) {
        return CONVERSATION_PREFIX + conversation.getPublicId();
    }

    public static String forJtbd(CustomerJtbd customerJtbd) {
        return JTBD_PREFIX + customerJtbd.getPublicId();
    }

    public static String forTask(Task task) {
        return TICKET_PREFIX + task.getTaskNumber();
    }

    public static boolean isConversationRequest(String requestId) {
        return requestId != null && requestId.startsWith(CONVERSATION_PREFIX);
    }

    public static boolean isJtbdRequest(String requestId) {
        return requestId != null && requestId.startsWith(JTBD_PREFIX);
    }

    public static boolean isTaskRequest(String requestId) {
        return requestId != null && requestId.startsWith(TICKET_PREFIX);
    }

    public static String extractReference(String requestId) {
        if (isConversationRequest(requestId)) {
            return requestId.substring(CONVERSATION_PREFIX.length());
        }
        if (isJtbdRequest(requestId)) {
            return requestId.substring(JTBD_PREFIX.length());
        }
        if (isTaskRequest(requestId)) {
            return requestId.substring(TICKET_PREFIX.length());
        }
        return requestId;
    }
}
