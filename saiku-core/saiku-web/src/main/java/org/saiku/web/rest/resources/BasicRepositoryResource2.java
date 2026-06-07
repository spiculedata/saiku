/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.resources;

import com.qmino.miredot.annotations.ReturnType;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.saiku.repository.AclEntry;
import org.saiku.repository.IRepositoryObject;
import org.saiku.service.ISessionService;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Path("/saiku/api/repository")
public class BasicRepositoryResource2 implements ISaikuRepository {

    private static final Logger log = LoggerFactory.getLogger(BasicRepositoryResource2.class);

    private ISessionService sessionService;

    // private Acl acl;
    private DatasourceService datasourceService;
    private File repo;

    public void setDatasourceService(DatasourceService ds) {
        datasourceService = ds;
    }

    public void setPath(String path) throws Exception {
        try {
            if (!path.endsWith("" + File.separatorChar)) {
                path += File.separatorChar;
            }
            File f = new File(path);
            if (!f.exists()) {
                throw new IOException("File does not exist: " + path);
            }
            repo = f;
        } catch (Exception e) {
            log.error("Error setting path for repository: " + path, e);
        }
    }

    /*public void setAcl(Acl acl) {
    	this.acl = acl;
    }*/

    /**
     * Sets the sessionService
     * @summary Set the session service
     * @param sessionService The session service
     */
    public void setSessionService(ISessionService sessionService) {
        this.sessionService = sessionService;
    }

    /* (non-Javadoc)
     * @see org.saiku.web.rest.resources.ISaikuRepository#getRepository(java.lang.String, java.lang.String)
     */
    @GET
    @Produces({"application/json"})
    public List<IRepositoryObject> getRepository(@QueryParam("path") String path, @QueryParam("type") String type) {
        if (sessionService == null
                || sessionService.getAllSessionObjects() == null
                || sessionService.getAllSessionObjects().get("username") == null) {
            return new ArrayList<IRepositoryObject>();
        }

        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        // type=null is a perfectly valid "give me everything" call — the legacy
        // unconditional split() NPE'd whenever a Basic-auth request hit a
        // ScopedRepo cache populated by a prior form-login (CsrfIT was the
        // first test to expose this). The separate ScopedRepo session-bleed
        // is filed; this just stops the resource from NPEing under it.
        String[] t = type == null ? new String[0] : type.split(",");
        List<IRepositoryObject> l;

        if (path == null) {
            l = (datasourceService.getFiles(Arrays.asList(t), username, roles));
        } else {
            l = (datasourceService.getFiles(Arrays.asList(t), username, roles, path));
        }

        return l;
    }

    /**
     * Get the ACL information for a given resource.
     * @summary Get ACL information.
     * @param file The file object
     * @return An AclEntry Object.
     */
    @GET
    @Produces({"application/json"})
    @Path("/resource/acl")
    @ReturnType("org.saiku.repository.AclEntry")
    public AclEntry getResourceAcl(@QueryParam("file") String file) {
        try {
            String username =
                    sessionService.getAllSessionObjects().get("username").toString();
            List<String> roles =
                    (List<String>) sessionService.getAllSessionObjects().get("roles");
            return datasourceService.getResourceACL(file, username, roles);

        } catch (Exception e) {
            log.error("Error retrieving ACL for file: " + file, e);
        }
        throw new SaikuServiceException("You dont have permission to retrieve ACL for file: " + file);
    }

    /**
     * Set the ACL information for a file/folder.
     * @summary Set the ACL information
     * @param file The file you want to change
     * @param aclEntry The ACL information.
     * @return A response 200.
     */
    @POST
    @Produces({"application/json"})
    @Path("/resource/acl")
    public Response setResourceAcl(@FormParam("file") String file, @FormParam("acl") String aclEntry) {
        try {
            String username =
                    sessionService.getAllSessionObjects().get("username").toString();
            List<String> roles =
                    (List<String>) sessionService.getAllSessionObjects().get("roles");
            datasourceService.setResourceACL(file, aclEntry, username, roles);
            return Response.ok().build();

            // log.debug("Repo file does not exist or cannot grant access. repo file:" + repoFile + " - file: " + file);
        } catch (Exception e) {
            log.error("An error occured while setting permissions to file: " + file, e);
        }
        return Response.serverError().build();
    }

    /**
     * Get an object from the repository.
     * @summary Fetch from the repository.
     * @param file - The name of the repository file to load.
     * @return A response containing the file data.
     */
    @GET
    @Produces({"text/plain"})
    @Path("/resource")
    public Response getResource(@QueryParam("file") String file) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");

        byte[] data = new byte[0];
        try {
            data = datasourceService.getFileData(file, username, roles).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("Error reading file encoding", e);
        }
        return Response.ok(data, MediaType.TEXT_PLAIN)
                .header("content-length", data.length)
                .build();
        /*
        			if ( !acl.canRead(file, username, roles) ) {
        				return Response.serverError().status(Status.FORBIDDEN).build();
        			}
        */
    }

    /**
     * Save an object to the repository.
     * @summary Save object
     * @param file - The name of the repository file to load.
     * @param content - The content to save.
     * @return A response status 200.
     */
    @POST
    @Path("/resource")
    public Response saveResource(@FormParam("file") String file, @FormParam("content") String content) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        String resp = datasourceService.saveFile(content, file, username, roles);
        if (resp.equals("Save Okay")) {
            return Response.ok().build();
        } else {
            return Response.serverError()
                    .entity("Cannot save resource to ( file: " + file + ")")
                    .type("text/plain")
                    .build();
        }
        /*
        		return Response.serverError().status(Status.FORBIDDEN)
        					.entity("You don't have permissions to save here!")
        						.type("text/plain").build();
        */
    }

    /**
     * Delete a resource from the repository
     * @param file - The name of the repository file to load.
     * @return a response status 200.
     */
    @DELETE
    @Path("/resource")
    public Response deleteResource(@QueryParam("file") String file) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        String resp = datasourceService.removeFile(file, username, roles);
        if (resp.equals("Remove Okay")) {
            return Response.ok().build();
        } else {
            return Response.serverError()
                    .entity("Cannot save resource to ( file: " + file + ")")
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Move an object within the repository.
     * @summary Move object.
     * @param source Source object
     * @param target Target location
     * @return A response status 200
     */
    @POST
    @Path("/resource/move")
    public Response moveResource(@FormParam("source") String source, @FormParam("target") String target) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        String resp = datasourceService.moveFile(source, target, username, roles);
        if (resp.equals("Move Okay")) {
            return Response.ok().entity("{}").build();
        } else {
            return Response.serverError()
                    .entity("Cannot move resource to ( file: " + target + ")")
                    .type("text/plain")
                    .build();
        }

        /*try {
        	if (source == null || source.startsWith("/") || source.startsWith(".")) {
        		throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + source);
        	}
        	if (target == null || target.startsWith("/") || target.startsWith(".")) {
        		throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + target);
        	}

        	String username = sessionService.getAllSessionObjects().get("username").toString();
        	List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
        	FileObject targetFile = repo.resolveFile(target);

        	if ( !acl.canWrite(target,username, roles) ) {
        		return Response.serverError().status(Status.FORBIDDEN)
        					.entity("You don't have permissions to save here!")
        						.type("text/plain").build();
        	}

        	if (targetFile == null) throw new Exception("Repo File not found");

        	if (targetFile.exists()) {
        		throw new Exception("Target file exists already. Cannot write: " + target);
        	}

        	FileObject sourceFile = repo.resolveFile(source);
        	if ( !acl.canRead(source, username, roles) ) {
        		return Response.serverError().status(Status.FORBIDDEN).entity("You don't have permissions to read the source file: " + source).build();
        	}

        	if (!sourceFile.exists()) {
        		throw new Exception("Source file does not exist: " + source);
        	}
        	if (!sourceFile.canRenameTo(targetFile)) {
        		throw new Exception("Cannot rename " + source + " to " + target);
        	}
        	sourceFile.moveTo(targetFile);
        	return Response.ok().build();
        } catch(Exception e){
        	log.error("Cannot move resource from " + source + " to " + target ,e);
        	return Response.serverError().entity("Cannot move resource from " + source + " to " + target + " ( " + e.getMessage() + ")").type("text/plain").build();
        }
        */
    }

    /**
     * Upload a zip archive to the server.
     * @param test Not used.
     * @param uploadedInputStream File Info
     * @param fileDetail File Info
     * @param directory Location
     * @return A response status 200
     */
    @POST
    @Path("/zipupload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadArchiveZip(
            @QueryParam("test") String test,
            @FormDataParam("file") InputStream uploadedInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail,
            @FormDataParam("directory") String directory) {
        String zipFile = fileDetail.getFileName();
        String output = "";
        ZipInputStream zis = null;
        try {
            if (StringUtils.isBlank(zipFile)) throw new Exception("You must specify a zip file to upload");

            output = "Uploding file: " + zipFile + " ...\r\n";
            // saiku#1165 hardening: bound decompression so a zip bomb can't OOM
            // the server. The #1157 fix validated entry NAMES; this caps entry
            // SIZES. Limits are on UNCOMPRESSED bytes actually read (the zip
            // header sizes are attacker-controlled, so we can't trust them),
            // plus a total-size and entry-count cap. All tunable via
            // -Dsaiku.repo.zip* .
            final long maxEntryBytes = longProp("saiku.repo.zipMaxEntryBytes", 10L * 1024 * 1024);
            final long maxTotalBytes = longProp("saiku.repo.zipMaxTotalBytes", 50L * 1024 * 1024);
            final int maxEntries = intProp("saiku.repo.zipMaxEntries", 1000);
            long totalBytes = 0;
            int entryCount = 0;
            zis = new ZipInputStream(uploadedInputStream);
            ZipEntry ze = zis.getNextEntry();
            byte[] doc = null;
            boolean isFile = false;
            if (ze == null) {
                doc = readCapped(uploadedInputStream, maxEntryBytes);
                isFile = true;
            }
            while (ze != null || doc != null) {
                String fileName = null;
                if (!isFile) {
                    fileName = ze.getName();
                    doc = readCapped(zis, maxEntryBytes);
                } else {
                    fileName = zipFile;
                }
                if (++entryCount > maxEntries) {
                    throw new ZipLimitExceededException("archive has too many entries (limit " + maxEntries + ")");
                }
                totalBytes += doc.length;
                if (totalBytes > maxTotalBytes) {
                    throw new ZipLimitExceededException(
                            "archive exceeds the total uncompressed size limit (" + maxTotalBytes + " bytes)");
                }

                // saiku#1157: zip-slip defense-in-depth. Reject a traversal /
                // absolute entry name at the zip layer BEFORE it reaches
                // saveResource (whose resolveWithinDatadir is the primary
                // guard) so a future regression in the path resolver can't
                // silently re-open the hole. Fail closed with a 400 naming the
                // offending entry; nothing further in the archive is written.
                if (isUnsafeZipEntryName(fileName)) {
                    log.warn("Rejected unsafe zip entry name (saiku#1157): {}", fileName);
                    return Response.status(Status.BAD_REQUEST)
                            .entity(output + "REJECTED: unsafe entry name '" + fileName
                                    + "' — path traversal / absolute paths are not allowed.\r\n")
                            .type("text/plain")
                            .build();
                }

                output += "Saving " + fileName + "... ";
                String fullPath = (StringUtils.isNotBlank(directory)) ? directory + "/" + fileName : fileName;

                String content = new String(doc);
                Response r = saveResource(fullPath, content);
                doc = null;

                if (Status.OK.getStatusCode() != r.getStatus()) {
                    output += " ERROR: " + r.getEntity().toString() + "\r\n";
                } else {
                    output += " OK\r\n";
                }
                if (!isFile) ze = zis.getNextEntry();
            }

            if (!isFile) {
                zis.closeEntry();
            }

            output += " SUCCESSFUL!\r\n";
            return Response.ok(output).build();

        } catch (ZipLimitExceededException e) {
            // saiku#1165: a decompression-size guard tripped — treat as a bad
            // upload (400), not a server error, and never write a partial result.
            log.warn("Rejected oversized zip upload (saiku#1165): {}", e.getMessage());
            return Response.status(Status.BAD_REQUEST)
                    .entity(output + "REJECTED: " + e.getMessage() + "\r\n")
                    .type("text/plain")
                    .build();
        } catch (Exception e) {
            log.error("Cannot unzip resources " + zipFile, e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return Response.serverError().entity(output + "\r\n" + error).build();
        } finally {
            // saiku#1191: close the zip + multipart streams on every path — the success
            // path used to do this inline, but the unsafe-entry early return and the
            // zip-bomb/size-limit throws (saiku#1157/#1165) skipped it and leaked the
            // upload stream. Closing the ZipInputStream also closes the wrapped
            // uploadedInputStream; the second close is a null-safe no-op fallback for the
            // case where an error fired before the ZipInputStream was created.
            if (zis != null) {
                try {
                    zis.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
            try {
                uploadedInputStream.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /**
     * saiku#1165: read at most {@code max} uncompressed bytes from {@code in}
     * (a single zip entry, or the whole non-zip upload), throwing rather than
     * buffering an unbounded amount — the defense against a zip bomb.
     */
    static byte[] readCapped(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new ZipLimitExceededException(
                        "a zip entry exceeds the per-entry uncompressed size limit (" + max + " bytes)");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static long longProp(String name, long def) {
        try {
            String v = System.getProperty(name);
            return v == null ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intProp(String name, int def) {
        try {
            String v = System.getProperty(name);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Thrown when an uploaded archive breaches a decompression safety limit (saiku#1165). */
    static final class ZipLimitExceededException extends IOException {
        ZipLimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * saiku#1157: defense-in-depth zip-slip guard. Returns {@code true} when a
     * zip archive entry name could escape the target directory and must be
     * rejected before {@code saveResource} is called. A name is unsafe when it
     * is blank, absolute (a unix leading {@code /}, a Windows drive like
     * {@code C:\}, or a leading backslash / UNC path), or contains a
     * {@code ..} path segment after separator normalisation. The downstream
     * {@code FilesystemRepositoryManager.resolveWithinDatadir} remains the
     * primary guard; this is a second, independent layer.
     */
    static boolean isUnsafeZipEntryName(String name) {
        if (StringUtils.isBlank(name)) return true;
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/")) return true; // unix-absolute or leading slash (also catches UNC)
        if (normalized.matches("(?i)^[a-z]:/.*")) return true; // Windows drive-absolute, e.g. C:/ or C:\
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) return true; // traversal segment
        }
        try {
            if (Paths.get(name).isAbsolute()) return true; // platform-specific backstop
        } catch (InvalidPathException e) {
            return true; // not a legal path on this platform → reject
        }
        return false;
    }
}
