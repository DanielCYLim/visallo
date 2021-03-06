package org.visallo.web.routes.vertex;

import com.google.inject.Inject;
import com.v5analytics.webster.HandlerChain;
import org.vertexium.Authorizations;
import org.vertexium.Graph;
import org.vertexium.Vertex;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloAccessDeniedException;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.model.workspace.WorkspaceRepository;
import org.visallo.core.user.User;
import org.visallo.core.util.ClientApiConverter;
import org.visallo.web.BaseRequestHandler;
import org.visallo.web.clientapi.model.ClientApiVertexMultipleResponse;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.HashSet;

import static org.vertexium.util.IterableUtils.toIterable;

public class VertexMultiple extends BaseRequestHandler {
    private final Graph graph;

    @Inject
    public VertexMultiple(
            final Graph graph,
            final UserRepository userRepository,
            final WorkspaceRepository workspaceRepository,
            final Configuration configuration) {
        super(userRepository, workspaceRepository, configuration);
        this.graph = graph;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        HashSet<String> vertexStringIds = new HashSet<>(Arrays.asList(getRequiredParameterArray(request, "vertexIds[]")));
        boolean fallbackToPublic = getOptionalParameterBoolean(request, "fallbackToPublic", false);
        User user = getUser(request);
        GetAuthorizationsResult getAuthorizationsResult = getAuthorizations(request, fallbackToPublic, user);
        String workspaceId = getWorkspaceId(request);

        Iterable<String> vertexIds = toIterable(vertexStringIds.toArray(new String[vertexStringIds.size()]));
        Iterable<Vertex> graphVertices = graph.getVertices(vertexIds, ClientApiConverter.SEARCH_FETCH_HINTS, getAuthorizationsResult.authorizations);
        ClientApiVertexMultipleResponse result = new ClientApiVertexMultipleResponse();
        result.setRequiredFallback(getAuthorizationsResult.requiredFallback);
        for (Vertex v : graphVertices) {
            result.getVertices().add(ClientApiConverter.toClientApiVertex(v, workspaceId, getAuthorizationsResult.authorizations));
        }

        respondWithClientApiObject(response, result);
    }

    private GetAuthorizationsResult getAuthorizations(HttpServletRequest request, boolean fallbackToPublic, User user) {
        GetAuthorizationsResult result = new GetAuthorizationsResult();
        result.requiredFallback = false;
        try {
            result.authorizations = getAuthorizations(request, user);
        } catch (VisalloAccessDeniedException ex) {
            if (fallbackToPublic) {
                result.authorizations = getUserRepository().getAuthorizations(user);
                result.requiredFallback = true;
            } else {
                throw ex;
            }
        }
        return result;
    }

    private String getWorkspaceId(HttpServletRequest request) {
        String workspaceId;
        try {
            workspaceId = getActiveWorkspaceId(request);
        } catch (VisalloException ex) {
            workspaceId = null;
        }
        return workspaceId;
    }

    private static class GetAuthorizationsResult {
        public Authorizations authorizations;
        public boolean requiredFallback;
    }
}
