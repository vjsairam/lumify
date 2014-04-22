package com.altamiracorp.lumify.securegraph.model.user;

import com.altamiracorp.bigtable.model.user.ModelUserContext;
import com.altamiracorp.lumify.core.exception.LumifyException;
import com.altamiracorp.lumify.core.model.ontology.Concept;
import com.altamiracorp.lumify.core.model.ontology.OntologyRepository;
import com.altamiracorp.lumify.core.model.user.AuthorizationRepository;
import com.altamiracorp.lumify.core.model.user.UserPasswordUtil;
import com.altamiracorp.lumify.core.model.user.UserRepository;
import com.altamiracorp.lumify.core.model.user.UserStatus;
import com.altamiracorp.lumify.core.model.workspace.Workspace;
import com.altamiracorp.lumify.core.security.LumifyVisibility;
import com.altamiracorp.lumify.core.user.SystemUser;
import com.altamiracorp.lumify.core.user.User;
import com.altamiracorp.lumify.core.util.LumifyLogger;
import com.altamiracorp.lumify.core.util.LumifyLoggerFactory;
import com.altamiracorp.securegraph.Graph;
import com.altamiracorp.securegraph.Vertex;
import com.altamiracorp.securegraph.VertexBuilder;
import com.altamiracorp.securegraph.util.ConvertingIterable;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.altamiracorp.lumify.core.model.ontology.OntologyLumifyProperties.CONCEPT_TYPE;
import static com.altamiracorp.lumify.core.model.user.UserLumifyProperties.*;
import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class SecureGraphUserRepository extends UserRepository {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(SecureGraphUserRepository.class);
    private final AuthorizationRepository authorizationRepository;
    private Graph graph;
    private String userConceptId;
    private com.altamiracorp.securegraph.Authorizations authorizations;
    private final Cache<String, Set<String>> userAuthorizationCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();

    @Inject
    public SecureGraphUserRepository(
            final AuthorizationRepository authorizationRepository,
            final Graph graph,
            final OntologyRepository ontologyRepository) {
        this.authorizationRepository = authorizationRepository;
        this.graph = graph;

        authorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);
        authorizationRepository.addAuthorizationToGraph(LumifyVisibility.SUPER_USER_VISIBILITY_STRING);

        Concept userConcept = ontologyRepository.getOrCreateConcept(null, LUMIFY_USER_CONCEPT_ID, "lumifyUser");
        userConceptId = userConcept.getTitle();

        Set<String> authorizationsSet = new HashSet<String>();
        authorizationsSet.add(VISIBILITY_STRING);
        authorizationsSet.add(LumifyVisibility.SUPER_USER_VISIBILITY_STRING);
        this.authorizations = authorizationRepository.createAuthorizations(authorizationsSet);
    }

    private SecureGraphUser createFromVertex(Vertex user) {
        if (user == null) {
            return null;
        }

        String[] authorizations = Iterables.toArray(getAuthorizations(user), String.class);
        ModelUserContext modelUserContext = getModelUserContext(authorizations);

        String userName = USERNAME.getPropertyValue(user);
        String userId = (String) user.getId();
        String userStatus = STATUS.getPropertyValue(user);
        LOGGER.debug("Creating user from UserRow. userName: %s, authorizations: %s", userName, AUTHORIZATIONS.getPropertyValue(user));
        return new SecureGraphUser(userId, userName, modelUserContext, userStatus);
    }

    @Override
    public User findByUsername(String username) {
        return createFromVertex(Iterables.getFirst(graph.query(authorizations)
                .has(USERNAME.getKey(), username)
                .has(CONCEPT_TYPE.getKey(), userConceptId)
                .vertices(), null));
    }

    @Override
    public Iterable<User> findAll() {
        return new ConvertingIterable<Vertex, User>(graph.query(authorizations)
                .has(CONCEPT_TYPE.getKey(), userConceptId)
                .vertices()) {
            @Override
            protected User convert(Vertex vertex) {
                return createFromVertex(vertex);
            }
        };
    }

    @Override
    public User findById(String userId) {
        return createFromVertex(findByIdUserVertex(userId));
    }

    public Vertex findByIdUserVertex(String userId) {
        return graph.getVertex(userId, authorizations);
    }

    @Override
    public User addUser(String username, String displayName, String password, String[] userAuthorizations) {
        User existingUser = findByUsername(username);
        if (existingUser != null) {
            throw new LumifyException("duplicate username");
        }

        String authorizationsString = StringUtils.join(userAuthorizations, ",");

        byte[] salt = UserPasswordUtil.getSalt();
        byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);

        username = "USER_" + username;
        VertexBuilder userBuilder = graph.prepareVertex(username, VISIBILITY.getVisibility(), this.authorizations);
        USERNAME.setProperty(userBuilder, displayName, VISIBILITY.getVisibility());
        CONCEPT_TYPE.setProperty(userBuilder, userConceptId, VISIBILITY.getVisibility());
        PASSWORD_SALT.setProperty(userBuilder, salt, VISIBILITY.getVisibility());
        PASSWORD_HASH.setProperty(userBuilder, passwordHash, VISIBILITY.getVisibility());
        STATUS.setProperty(userBuilder, UserStatus.OFFLINE.toString(), VISIBILITY.getVisibility());
        AUTHORIZATIONS.setProperty(userBuilder, authorizationsString, VISIBILITY.getVisibility());
        User user = createFromVertex(userBuilder.save());
        graph.flush();
        return user;
    }

    @Override
    public void setPassword(User user, String password) {
        byte[] salt = UserPasswordUtil.getSalt();
        byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        PASSWORD_SALT.setProperty(userVertex, salt, VISIBILITY.getVisibility());
        PASSWORD_HASH.setProperty(userVertex, passwordHash, VISIBILITY.getVisibility());
        graph.flush();
    }

    @Override
    public boolean isPasswordValid(User user, String password) {
        try {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            return UserPasswordUtil.validatePassword(password, PASSWORD_SALT.getPropertyValue(userVertex), PASSWORD_HASH.getPropertyValue(userVertex));
        } catch (Exception ex) {
            throw new RuntimeException("error validating password", ex);
        }
    }

    @Override
    public User setCurrentWorkspace(String userId, Workspace workspace) {
        User user = findById(userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        if (user == null) {
            throw new RuntimeException("Could not find user: " + userId);
        }
        CURRENT_WORKSPACE.setProperty(userVertex, workspace.getId(), VISIBILITY.getVisibility());
        graph.flush();
        return user;
    }

    @Override
    public String getCurrentWorkspaceId(String userId) {
        User user = findById(userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        if (user == null) {
            throw new RuntimeException("Could not find user: " + userId);
        }
        String workspaceId = CURRENT_WORKSPACE.getPropertyValue(userVertex);
        if (workspaceId == null) {
            throw new RuntimeException("Could not find current workspace: " + workspaceId);
        }
        return workspaceId;
    }

    @Override
    public User setStatus(String userId, UserStatus status) {
        SecureGraphUser user = (SecureGraphUser) findById(userId);
        checkNotNull(user, "Could not find user: " + userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        STATUS.setProperty(userVertex, status.toString(), VISIBILITY.getVisibility());
        graph.flush();
        user.setUserStatus(status.toString());
        return user;
    }

    @Override
    public void addAuthorization(User user, String auth) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        Set<String> authorizations = getAuthorizations(userVertex);
        if (authorizations.contains(auth)) {
            return;
        }
        authorizations.add(auth);

        this.authorizationRepository.addAuthorizationToGraph(auth);

        String authorizationsString = StringUtils.join(authorizations, ",");
        AUTHORIZATIONS.setProperty(userVertex, authorizationsString, VISIBILITY.getVisibility());
        graph.flush();
        userAuthorizationCache.invalidate(user.getUserId());
    }

    @Override
    public void removeAuthorization(User user, String auth) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        Set<String> authorizations = getAuthorizations(userVertex);
        if (!authorizations.contains(auth)) {
            return;
        }
        authorizations.remove(auth);
        String authorizationsString = StringUtils.join(authorizations, ",");
        AUTHORIZATIONS.setProperty(userVertex, authorizationsString, VISIBILITY.getVisibility());
        graph.flush();
        userAuthorizationCache.invalidate(user.getUserId());
    }

    @Override
    public com.altamiracorp.securegraph.Authorizations getAuthorizations(User user, String... additionalAuthorizations) {
        Set<String> userAuthorizations;
        if (user instanceof SystemUser) {
            userAuthorizations = new HashSet<String>();
            userAuthorizations.add(LumifyVisibility.SUPER_USER_VISIBILITY_STRING);
        } else {
            userAuthorizations = userAuthorizationCache.getIfPresent(user.getUserId());
        }
        if (userAuthorizations == null) {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            userAuthorizations = getAuthorizations(userVertex);
            userAuthorizationCache.put(user.getUserId(), userAuthorizations);
        }

        Set<String> authorizationsSet = new HashSet<String>(userAuthorizations);
        Collections.addAll(authorizationsSet, additionalAuthorizations);
        return authorizationRepository.createAuthorizations(authorizationsSet);
    }

    public static Set<String> getAuthorizations(Vertex userVertex) {
        String authorizationsString = AUTHORIZATIONS.getPropertyValue(userVertex);
        if (authorizationsString == null) {
            return new HashSet<String>();
        }
        String[] authorizationsArray = authorizationsString.split(",");
        if (authorizationsArray.length == 1 && authorizationsArray[0].length() == 0) {
            authorizationsArray = new String[0];
        }
        HashSet<String> authorizations = new HashSet<String>();
        for (String s : authorizationsArray) {
            // Accumulo doesn't like zero length strings. they shouldn't be in the auth string to begin with but this just protects from that happening.
            if (s.trim().length() == 0) {
                continue;
            }

            authorizations.add(s);
        }
        return authorizations;
    }
}