package org.visallo.core.model.longRunningProcess;

import org.visallo.core.user.User;
import org.json.JSONObject;
import org.vertexium.Authorizations;

import java.util.List;

public abstract class LongRunningProcessRepository {
    public static final String VISIBILITY_STRING = "longRunningProcess";

    public abstract String enqueue(JSONObject longRunningProcessQueueItem, User user, Authorizations authorizations);

    public void beginWork(JSONObject longRunningProcessQueueItem) {
    }

    public abstract void ack(JSONObject longRunningProcessQueueItem);

    public abstract void nak(JSONObject longRunningProcessQueueItem, Throwable ex);

    public abstract List<JSONObject> getLongRunningProcesses(User user);

    public abstract JSONObject findById(String longRunningProcessId, User user);

    public abstract void cancel(String longRunningProcessId, User user);

    public abstract void reportProgress(JSONObject longRunningProcessQueueItem, double progressPercent, String message);

    public abstract void delete(String longRunningProcessId, User authUser);
}
