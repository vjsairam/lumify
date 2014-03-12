package com.altamiracorp.lumify.web.routes.entity;

import com.altamiracorp.bigtable.model.ModelSession;
import com.altamiracorp.lumify.core.model.audit.AuditAction;
import com.altamiracorp.lumify.core.model.audit.AuditRepository;
import com.altamiracorp.lumify.core.model.ontology.LabelName;
import com.altamiracorp.lumify.core.model.ontology.OntologyRepository;
import com.altamiracorp.lumify.core.model.termMention.TermMentionModel;
import com.altamiracorp.lumify.core.model.termMention.TermMentionRepository;
import com.altamiracorp.lumify.core.model.termMention.TermMentionRowKey;
import com.altamiracorp.lumify.core.model.textHighlighting.TermMentionOffsetItem;
import com.altamiracorp.lumify.core.model.user.UserRepository;
import com.altamiracorp.lumify.core.security.VisibilityTranslator;
import com.altamiracorp.lumify.core.user.User;
import com.altamiracorp.lumify.core.util.LumifyLogger;
import com.altamiracorp.lumify.core.util.LumifyLoggerFactory;
import com.altamiracorp.lumify.web.BaseRequestHandler;
import com.altamiracorp.miniweb.HandlerChain;
import com.altamiracorp.securegraph.*;
import com.google.inject.Inject;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Iterator;

public class UnresolveTermEntity extends BaseRequestHandler {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(UnresolveTermEntity.class);
    private final TermMentionRepository termMentionRepository;
    private final Graph graph;
    private final AuditRepository auditRepository;
    private final UserRepository userRepository;
    private final ModelSession modelSession;
    private final VisibilityTranslator visibilityTranslator;
    private final OntologyRepository ontologyRepository;

    @Inject
    public UnresolveTermEntity(
            final TermMentionRepository termMentionRepository,
            final Graph graph,
            final AuditRepository auditRepository,
            final UserRepository userRepository,
            final ModelSession modelSession,
            final VisibilityTranslator visibilityTranslator,
            final OntologyRepository ontologyRepository) {
        this.termMentionRepository = termMentionRepository;
        this.graph = graph;
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.modelSession = modelSession;
        this.visibilityTranslator = visibilityTranslator;
        this.ontologyRepository = ontologyRepository;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        // required parameters
        final String artifactId = getRequiredParameter(request, "artifactId");
        final long mentionStart = getRequiredParameterAsLong(request, "mentionStart");
        final long mentionEnd = getRequiredParameterAsLong(request, "mentionEnd");
        final String sign = getRequiredParameter(request, "sign");
        final String conceptId = getRequiredParameter(request, "conceptId");
        final String graphVertexId = getRequiredParameter(request, "graphVertexId");
        final String visibilitySource = getRequiredParameter(request, "visibilitySource");

        LOGGER.debug(
                "UnresolveTermEntity (artifactId: %s, mentionStart: %d, mentionEnd: %d, sign: %s, conceptId: %s, graphVertexId: %s)",
                artifactId,
                mentionStart,
                mentionEnd,
                sign,
                conceptId,
                graphVertexId);

        User user = getUser(request);
        Authorizations authorizations = userRepository.getAuthorizations(user);
        Visibility visibility = visibilityTranslator.toVisibility(visibilitySource);

        Vertex resolvedVertex = graph.getVertex(graphVertexId, authorizations);
        Vertex artifactVertex = graph.getVertex(artifactId, authorizations);
        JSONObject result = new JSONObject();

        // Unlinking the term with the vertex
        TermMentionRowKey termMentionRowKey = new TermMentionRowKey(artifactId, mentionStart, mentionEnd);
        TermMentionModel termMention = termMentionRepository.findByRowKey(termMentionRowKey.toString(), user.getModelUserContext());
        if (termMention == null) {
            termMention = new TermMentionModel(termMentionRowKey);
        }

        // Clean up term mentions if system analytics wasn't performed on term
        String columnFamilyName = termMention.getMetadata().getColumnFamilyName();
        String columnName = termMention.getMetadata().VERTEX_ID;
        String analyticProcess = termMention.getMetadata().getAnalyticProcess();

        if (analyticProcess == null) {
            modelSession.deleteRow(termMention.getTableName(), termMentionRowKey, user.getModelUserContext());
        } else {
            termMention.get(columnFamilyName).getColumn(columnName).setDirty(true);
            modelSession.deleteColumn(termMention, termMention.getTableName(), columnFamilyName, columnName, user.getModelUserContext());
            termMention.getMetadata().setVertexId("", visibility);

            TermMentionOffsetItem offsetItem = new TermMentionOffsetItem(termMention);
            result = offsetItem.toJson();
        }

        // If there is only instance of the term entity in this artifact delete the relationship
        Iterator<TermMentionModel> termMentionModels = termMentionRepository.findByGraphVertexId(artifactVertex.getId().toString(), user).iterator();
        boolean deleteEdge = false;
        Object edgeId = null;
        int termCount = 0;
        while (termMentionModels.hasNext()) {
            TermMentionModel termMentionModel = termMentionModels.next();
            Object termMentionId = termMentionModel.getMetadata().getGraphVertexId();
            if (termMentionId != null && termMentionId.equals(graphVertexId)) {
                termCount++;
                break;
            }
        }
        if (termCount == 0) {
            Iterable<Edge> edges = artifactVertex.getEdges(resolvedVertex, Direction.OUT, LabelName.RAW_HAS_ENTITY.toString(), authorizations);
            Edge edge = edges.iterator().next();
            if (edge != null) {
                String label = ontologyRepository.getDisplayNameForLabel(edge.getLabel());
                edgeId = edge.getId();
                graph.removeEdge(edge, authorizations);
                deleteEdge = true;
                graph.flush();
                auditRepository.auditRelationship(AuditAction.DELETE, artifactVertex, resolvedVertex, label, "", "", user, visibility);
            }
        }

        if (deleteEdge) {
            result.put("deleteEdge", deleteEdge);
            result.put("edgeId", edgeId);
        }
        respondWithJson(response, result);
    }
}
