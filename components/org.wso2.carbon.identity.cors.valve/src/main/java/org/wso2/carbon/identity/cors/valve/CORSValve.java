/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTE: The code/logic in this class is copied from https://bitbucket.org/thetransactioncompany/cors-filter.
 * All credits goes to the original authors of the project https://bitbucket.org/thetransactioncompany/cors-filter.
 */

package org.wso2.carbon.identity.cors.valve;

import com.google.gson.JsonObject;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.cors.mgt.core.exception.CORSManagementServiceException;
import org.wso2.carbon.identity.cors.mgt.core.model.CORSConfiguration;
import org.wso2.carbon.identity.cors.service.CORSManager;
import org.wso2.carbon.identity.cors.valve.constant.ErrorMessages;
import org.wso2.carbon.identity.cors.valve.exception.CORSException;
import org.wso2.carbon.identity.cors.valve.internal.CORSValveServiceHolder;
import org.wso2.carbon.identity.cors.valve.internal.handler.CORSRequestHandler;
import org.wso2.carbon.identity.cors.valve.internal.util.CORSUtils;
import org.wso2.carbon.identity.cors.valve.internal.util.RequestTagger;
import org.wso2.carbon.identity.cors.valve.model.CORSRequestType;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

/**
 * Valve class for CORS.
 */
public class CORSValve extends ValveBase {

    private static final Log log = LogFactory.getLog(CORSValve.class);

    private final CORSRequestHandler corsRequestHandler;

    /**
     * Default constructor for the CORSValve.
     */
    public CORSValve() {

        corsRequestHandler = new CORSRequestHandler();
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // Determine the type of the request.
        CORSRequestType corsRequestType = CORSUtils.getRequestType(request);
        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();

        if (!validateTenantDomain(response, tenantDomain)) {
            return;
        }
        try {
            // Tag if configured.
            CORSManager corsManager = CORSValveServiceHolder.getInstance().getCorsManager();
            CORSConfiguration corsConfiguration = corsManager.getCORSConfiguration(tenantDomain);
            if (corsConfiguration.isTagRequests()) {
                RequestTagger.tag(request, corsRequestType);
            }

            CORSConfiguration config = CORSValveServiceHolder.getInstance().getCorsManager()
                    .getCORSConfiguration(tenantDomain);

            if (corsRequestType == CORSRequestType.ACTUAL) {
                // ACTUAL request - Simple / actual CORS request.
                corsRequestHandler.handleActualRequest(request, response);
                getNext().invoke(request, response);
            } else if (corsRequestType == CORSRequestType.PREFLIGHT) {
                // PREFLIGHT request - Handle but don't pass further down the chain.
                corsRequestHandler.handlePreflightRequest(request, response);
            } else if (config.isAllowGenericHttpRequests()) {
                // OTHER request - Not a CORS request, but allow it through.
                getNext().invoke(request, response);
            } else {
                // Generic HTTP requests denied.
                printMessage(new CORSException(ErrorMessages.ERROR_CODE_GENERIC_HTTP_NOT_ALLOWED), response);
            }
        } catch (CORSException e) {
            printMessage(e, response);
        } catch (CORSManagementServiceException e) {
            log.error("CORS management service error when intercepting an HTTP request.", e);
        }
    }

    private boolean validateTenantDomain(Response response, String tenantDomain) throws IOException, ServletException {

        try {
            TenantManager tenantManager = CORSValveServiceHolder.getInstance().getRealmService()
                    .getTenantManager();
            if (tenantDomain != null && !tenantManager.isTenantActive(IdentityTenantUtil.getTenantId(tenantDomain))) {
                handleInvalidTenantDomainErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, tenantDomain);
                return false;
            }
        } catch (UserStoreException ex) {
            if (log.isDebugEnabled()) {
                log.debug("Error occurred while validating tenant domain.", ex);
            }
            handleInvalidTenantDomainErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, tenantDomain);
            return false;
        } catch (IdentityRuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error occurred while validating tenant domain.", e);
            }
            String INVALID_TENANT_DOMAIN = "Invalid tenant domain";
            if (!StringUtils.isBlank(e.getMessage()) && e.getMessage().contains(INVALID_TENANT_DOMAIN)) {
                handleInvalidTenantDomainErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, tenantDomain);
            } else {
                handleRuntimeErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, tenantDomain);
            }
            return false;
        }
        return true;
    }

    /**
     * Produces a simple HTTP text/plain response for the specified CORS
     * exception.
     * Note: The CORS filter avoids falling back to the default web container error page (typically a
     * richly-formatted HTML page) to make it easier for XHR debugger tools to identify the cause of failed requests.
     *
     * @param e        The CORS exception. Must not be {@code null}.
     * @param response The HTTP servlet response. Must not be {@code null}.
     * @throws IOException On a I/O exception.
     */
    private void printMessage(final CORSException e, final HttpServletResponse response) throws IOException {

        // Set the status code.
        response.setStatus(e.getHttpStatusCode());

        // Write the error message.
        response.resetBuffer();
        response.setContentType("text/plain");
        PrintWriter out = response.getWriter();
        out.println("CORS Valve: " + e.getMessage());

        if (log.isDebugEnabled()) {
            log.debug("CORS valve error when intercepting an HTTP request.", e);
        }
    }

    private void handleInvalidTenantDomainErrorResponse(Response response, int error, String tenantDomain) throws
            IOException {

        response.setContentType("application/json");
        response.setStatus(error);
        response.setCharacterEncoding("UTF-8");
        JsonObject errorResponse = new JsonObject();
        String errorMsg = "invalid tenant domain : " + tenantDomain;
        errorResponse.addProperty("code", error);
        errorResponse.addProperty("message", errorMsg);
        errorResponse.addProperty("description", errorMsg);
        response.getWriter().print(errorResponse.toString());
    }

    private void handleRuntimeErrorResponse(Response response, int error, String tenantDomain) throws
            IOException {

        response.setContentType("application/json");
        response.setStatus(error);
        response.setCharacterEncoding("UTF-8");
        JsonObject errorResponse = new JsonObject();
        String errorMsg = "Error occurred while validating tenant domain: " + tenantDomain;
        errorResponse.addProperty("code", error);
        errorResponse.addProperty("message", errorMsg);
        errorResponse.addProperty("description", errorMsg);
        response.getWriter().print(errorResponse.toString());
    }
}
