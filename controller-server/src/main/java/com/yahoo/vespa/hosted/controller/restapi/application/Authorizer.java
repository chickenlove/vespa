// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.application;

import com.google.common.collect.ImmutableSet;
import com.yahoo.config.provision.Environment;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.Tenant;
import com.yahoo.vespa.hosted.controller.api.identifiers.AthensDomain;
import com.yahoo.vespa.hosted.controller.api.identifiers.TenantId;
import com.yahoo.vespa.hosted.controller.api.identifiers.UserGroup;
import com.yahoo.vespa.hosted.controller.api.identifiers.UserId;
import com.yahoo.vespa.hosted.controller.api.integration.entity.EntityService;
import com.yahoo.vespa.hosted.controller.api.integration.athens.ZmsClientFactory;
import com.yahoo.vespa.hosted.controller.api.integration.athens.AthensPrincipal;
import com.yahoo.vespa.hosted.controller.api.integration.athens.Athens;
import com.yahoo.vespa.hosted.controller.api.integration.athens.NToken;
import com.yahoo.vespa.hosted.controller.common.ContextAttributes;
import com.yahoo.vespa.hosted.controller.restapi.filter.NTokenRequestFilter;
import com.yahoo.vespa.hosted.controller.restapi.filter.UnauthenticatedUserPrincipal;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;


/**
 * @author Stian Kristoffersen
 * @author Tony Vaagenes
 * @author bjorncs
 */
// TODO: Make this an interface
public class Authorizer {

    private static final Logger log = Logger.getLogger(Authorizer.class.getName());

    // Must be kept in sync with bouncer filter configuration.
    private static final String VESPA_HOSTED_ADMIN_ROLE = "10707.A";

    private static final Set<UserId> SCREWDRIVER_USERS = ImmutableSet.of(new UserId("screwdrv"), 
                                                                         new UserId("screwdriver"), 
                                                                         new UserId("sdrvtest"), 
                                                                         new UserId("screwdriver-test"));

    private final Controller controller;
    private final ZmsClientFactory zmsClientFactory;
    private final EntityService entityService;
    private final Athens athens;

    public Authorizer(Controller controller, EntityService entityService) {
        this.controller = controller;
        this.zmsClientFactory = controller.athens().zmsClientFactory();
        this.entityService = entityService;
        this.athens = controller.athens();
    }

    public void throwIfUnauthorized(TenantId tenantId, HttpRequest request) throws ForbiddenException {
        if (isReadOnlyMethod(request.getMethod().name())) return;
        if (isSuperUser(request)) return;

        Optional<Tenant> tenant = controller.tenants().tenant(tenantId);
        if ( ! tenant.isPresent()) return;

        UserId userId = getUserId(request);
        if (isTenantAdmin(userId, tenant.get())) return;

        throw loggedForbiddenException("User " + userId + " does not have write access to tenant " + tenantId);
    }

    public UserId getUserId(HttpRequest request) {
        String name = getPrincipal(request).getName();
        if (name == null)
            throw loggedForbiddenException("Not authorized: User name is null");
        return new UserId(name);
    }

    /** Returns the principal or throws forbidden */ // TODO: Avoid REST exceptions
    public Principal getPrincipal(HttpRequest request) {
        return getPrincipalIfAny(request).orElseThrow(() -> Authorizer.loggedForbiddenException("User is not authenticated"));
    }

    /** Returns the principal if there is any */
    public Optional<Principal> getPrincipalIfAny(HttpRequest request) {
        return securityContextOf(request).map(SecurityContext::getUserPrincipal);
    }

    public Optional<NToken> getNToken(HttpRequest request) {
        String nTokenHeader = (String)request.getJDiscRequest().context().get(NTokenRequestFilter.NTOKEN_HEADER);
        return Optional.ofNullable(nTokenHeader).map(athens::nTokenFrom);
    }

    public boolean isSuperUser(HttpRequest request) {
        // TODO Check membership of admin role in Vespa's Athens domain
        return isMemberOfVespaBouncerGroup(request) || isScrewdriverPrincipal(athens, getPrincipal(request));
    }

    public static boolean isScrewdriverPrincipal(Athens athens, Principal principal) {
        if (principal instanceof UnauthenticatedUserPrincipal) // Host-based authentication
            return SCREWDRIVER_USERS.contains(new UserId(principal.getName()));
        else if (principal instanceof AthensPrincipal)
            return ((AthensPrincipal)principal).getDomain().equals(athens.screwdriverDomain());
        else
            return false;
    }

    private static ForbiddenException loggedForbiddenException(String message, Object... args) {
        String formattedMessage = String.format(message, args);
        log.info(formattedMessage);
        return new ForbiddenException(formattedMessage);
    }

    private boolean isTenantAdmin(UserId userId, Tenant tenant) {
        switch (tenant.tenantType()) {
            case ATHENS:
                return isAthensTenantAdmin(userId, tenant.getAthensDomain().get());
            case OPSDB:
                return isGroupMember(userId, tenant.getUserGroup().get());
            case USER:
                return isUserTenantOwner(tenant.getId(), userId);
        }
        throw new IllegalArgumentException("Unknown tenant type: " + tenant.tenantType());
    }

    private boolean isAthensTenantAdmin(UserId userId, AthensDomain tenantDomain) {
        return zmsClientFactory.createClientWithServicePrincipal()
                .hasTenantAdminAccess(athens.principalFrom(userId), tenantDomain);
    }

    public boolean isAthensDomainAdmin(UserId userId, AthensDomain tenantDomain) {
        return zmsClientFactory.createClientWithServicePrincipal()
                .isDomainAdmin(athens.principalFrom(userId), tenantDomain);
    }

    public boolean isGroupMember(UserId userId, UserGroup userGroup) {
        return entityService.isGroupMember(userId, userGroup);
    }

    private static boolean isUserTenantOwner(TenantId tenantId, UserId userId) {
        return tenantId.equals(userId.toTenantId());
    }

    public static boolean environmentRequiresAuthorization(Environment environment) {
        return environment != Environment.dev && environment != Environment.perf;
    }

    private static boolean isReadOnlyMethod(String method) {
        return method.equals(HttpMethod.GET) || method.equals(HttpMethod.HEAD) || method.equals(HttpMethod.OPTIONS);
    }

    private boolean isMemberOfVespaBouncerGroup(HttpRequest request) {
        Optional<SecurityContext> securityContext = securityContextOf(request);
        if ( ! securityContext.isPresent() ) throw Authorizer.loggedForbiddenException("User is not authenticated");
        return securityContext.get().isUserInRole(Authorizer.VESPA_HOSTED_ADMIN_ROLE);
    }

    protected Optional<SecurityContext> securityContextOf(HttpRequest request) {
        return Optional.ofNullable((SecurityContext)request.getJDiscRequest().context().get(ContextAttributes.SECURITY_CONTEXT_ATTRIBUTE));
    }

}
