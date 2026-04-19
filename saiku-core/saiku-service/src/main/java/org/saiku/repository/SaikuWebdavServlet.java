package org.saiku.repository;

import java.io.IOException;
import java.util.List;
import javax.jcr.LoginException;
import javax.jcr.Repository;
import javax.jcr.SimpleCredentials;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.jackrabbit.server.BasicCredentialsProvider;
import org.apache.jackrabbit.webdav.*;
import org.apache.jackrabbit.webdav.simple.SimpleWebdavServlet;
import org.apache.jackrabbit.webdav.util.CSRFUtil;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.datasource.RepositoryDatasourceManager;
import org.saiku.service.user.UserService;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Created by bugg on 04/09/14.
 */
public final class SaikuWebdavServlet extends SimpleWebdavServlet {

    private RepositoryDatasourceManager bean;
    private CSRFUtil csrfUtil;
    private UserService us;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        String csrfParam = getInitParameter(INIT_PARAM_CSRF_PROTECTION);
        csrfUtil = new CSRFUtil(csrfParam);
        ServletContext context = getServletContext();

        WebApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(context);
        bean = (RepositoryDatasourceManager) applicationContext.getBean("repositoryDsManager");
        us = (UserService) applicationContext.getBean("userServiceBean");
    }

    private boolean checkUserRole(HttpServletRequest request) {
        for (SaikuUser u : us.getUsers()) {
            String req = request.getRemoteUser();
            BasicCredentialsProvider b = new BasicCredentialsProvider(null);
            SimpleCredentials creds = null;
            try {
                creds = (SimpleCredentials) b.getCredentials(request);
            } catch (LoginException | ServletException e) {
                e.printStackTrace();
            }
            if (u.getUsername().equals(creds.getUserID())) {
                String[] roles = us.getRoles(u);
                List<String> admin = us.getAdminRoles();

                for (String r : roles) {
                    if (admin.contains(r)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean checkUnsecured(HttpServletRequest request) {
        return request.getRequestURI().contains("/etc/theme");
    }

    private boolean checkSecret(HttpServletRequest request) {
        if (request.getRequestURI().contains("/datasources")) {
            return checkUserRole(request);
        }
        return true;
    }
    /**
     * Service the given request.
     *
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        WebdavRequest webdavRequest = new WebdavRequestImpl(request, getLocatorFactory(), isCreateAbsoluteURI());
        // DeltaV requires 'Cache-Control' header for all methods except 'VERSION-CONTROL' and 'REPORT'.
        int methodCode = DavMethods.getMethodCode(request.getMethod());
        boolean noCache = DavMethods.isDeltaVMethod(webdavRequest)
                && !(DavMethods.DAV_VERSION_CONTROL == methodCode || DavMethods.DAV_REPORT == methodCode);
        WebdavResponse webdavResponse = new WebdavResponseImpl(response, noCache);

        try {
            if (checkUnsecured(request) && !getDavSessionProvider().attachSession(webdavRequest)) {
                request.setAttribute("org.apache.jackrabbit.server.SessionProvider", new SaikuSessionProvider());
            }
        } catch (DavException e) {
            if (checkUnsecured(request)) {
                request.setAttribute("org.apache.jackrabbit.server.SessionProvider", new SaikuSessionProvider());
            }
        }

        try {

            // make sure there is a authenticated user
            if (!getDavSessionProvider().attachSession(webdavRequest)) {
                return;
            }

            if (!checkUnsecured(webdavRequest) && !checkUserRole(webdavRequest)) {
                return;
            }
            // perform referrer host checks if CSRF protection is enabled

            if (!checkSecret(request)) {
                webdavResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            if (!csrfUtil.isValidRequest(webdavRequest)) {
                webdavResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // check matching if=header for lock-token relevant operations
            DavResource resource = getResourceFactory()
                    .createResource(webdavRequest.getRequestLocator(), webdavRequest, webdavResponse);
            if (!isPreconditionValid(webdavRequest, resource)) {
                webdavResponse.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                return;
            }
            if (!execute(webdavRequest, webdavResponse, methodCode, resource)) {
                super.service(request, response);
            }

        } catch (DavException e) {
            if (e.getErrorCode() == HttpServletResponse.SC_UNAUTHORIZED) {
                sendUnauthorized(webdavRequest, webdavResponse, e);
            } else {
                webdavResponse.sendError(e);
            }
        } catch (Exception e) {
            log("Exception:", e.getCause());
        } finally {
            getDavSessionProvider().releaseSession(webdavRequest);
        }
    }

    @Override
    public Repository getRepository() {

        return (Repository) bean.getRepository();
    }

    @Override
    public void doPost(WebdavRequest request, WebdavResponse response, DavResource resource)
            throws IOException, DavException {

        //        super.doPost(request, response, resource);
        DavResource parentResource = resource.getCollection();
        if (parentResource == null || !parentResource.exists()) {
            // parent does not exist
            response.sendError(DavServletResponse.SC_CONFLICT);
            return;
        }

        int status;
        // test if resource already exists
        if (resource.exists()) {
            status = DavServletResponse.SC_NO_CONTENT;
        } else {
            status = DavServletResponse.SC_CREATED;
        }

        parentResource.addMember(resource, getInputContext(request, request.getInputStream()));
        response.setStatus(status);
    }

    @Override
    public void doPut(WebdavRequest request, WebdavResponse response, DavResource resource)
            throws IOException, DavException {

        super.doPut(request, response, resource);
    }
}
