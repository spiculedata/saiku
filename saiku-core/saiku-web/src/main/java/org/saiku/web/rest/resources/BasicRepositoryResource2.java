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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
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

    public void setSessionService(ISessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<IRepositoryObject> getRepository(
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "type", required = false) String type) {
        if (sessionService == null
                || sessionService.getAllSessionObjects() == null
                || sessionService.getAllSessionObjects().get("username") == null) {
            return new ArrayList<IRepositoryObject>();
        }

        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        String[] t = type.split(",");
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
     */
    @GetMapping(path = "/resource/acl", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.repository.AclEntry")
    public AclEntry getResourceAcl(@RequestParam(name = "file", required = false) String file) {
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
     */
    @PostMapping(path = "/resource/acl", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setResourceAcl(@RequestParam("file") String file, @RequestParam("acl") String aclEntry) {
        try {
            String username =
                    sessionService.getAllSessionObjects().get("username").toString();
            List<String> roles =
                    (List<String>) sessionService.getAllSessionObjects().get("roles");
            datasourceService.setResourceACL(file, aclEntry, username, roles);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("An error occured while setting permissions to file: " + file, e);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @Override
    @GetMapping(path = "/resource", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> getResource(@RequestParam(name = "file", required = false) String file) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");

        byte[] data = new byte[0];
        try {
            data = datasourceService.getFileData(file, username, roles).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("Error reading file encoding", e);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header("content-length", String.valueOf(data.length))
                .body(data);
    }

    @Override
    @PostMapping("/resource")
    public ResponseEntity<?> saveResource(
            @RequestParam(name = "file") String file, @RequestParam(name = "content") String content) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        String resp = datasourceService.saveFile(content, file, username, roles);
        if (resp.equals("Save Okay")) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Cannot save resource to ( file: " + file + ")");
        }
    }

    @Override
    @DeleteMapping("/resource")
    public ResponseEntity<?> deleteResource(@RequestParam(name = "file", required = false) String file) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        String resp = datasourceService.removeFile(file, username, roles);
        if (resp.equals("Remove Okay")) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Cannot save resource to ( file: " + file + ")");
        }
    }

    /**
     * Move an object within the repository.
     */
    @PostMapping("/resource/move")
    public ResponseEntity<?> moveResource(
            @RequestParam("source") String source, @RequestParam("target") String target) {
        String username = sessionService.getAllSessionObjects().get("username").toString();
        List<String> roles =
                (List<String>) sessionService.getAllSessionObjects().get("roles");
        String resp = datasourceService.moveFile(source, target, username, roles);
        if (resp.equals("Move Okay")) {
            return ResponseEntity.ok("{}");
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Cannot move resource to ( file: " + target + ")");
        }
    }

    /**
     * Upload a zip archive to the server.
     */
    @PostMapping(path = "/zipupload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadArchiveZip(
            @RequestParam(name = "test", required = false) String test,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "directory", required = false) String directory) {
        String zipFile = file.getOriginalFilename();
        String output = "";
        try {
            if (StringUtils.isBlank(zipFile)) throw new Exception("You must specify a zip file to upload");

            output = "Uploding file: " + zipFile + " ...\r\n";
            InputStream uploadedInputStream = file.getInputStream();
            ZipInputStream zis = new ZipInputStream(uploadedInputStream);
            ZipEntry ze = zis.getNextEntry();
            byte[] doc = null;
            boolean isFile = false;
            if (ze == null) {
                doc = IOUtils.toByteArray(uploadedInputStream);
                isFile = true;
            }
            while (ze != null || doc != null) {
                String fileName = null;
                if (!isFile) {
                    fileName = ze.getName();
                    doc = IOUtils.toByteArray(zis);
                } else {
                    fileName = zipFile;
                }

                output += "Saving " + fileName + "... ";
                String fullPath = (StringUtils.isNotBlank(directory)) ? directory + "/" + fileName : fileName;

                String content = new String(doc);
                ResponseEntity<?> r = saveResource(fullPath, content);
                doc = null;

                if (HttpStatus.OK.value() != r.getStatusCodeValue()) {
                    output += " ERROR: " + String.valueOf(r.getBody()) + "\r\n";
                } else {
                    output += " OK\r\n";
                }
                if (!isFile) ze = zis.getNextEntry();
            }

            if (!isFile) {
                zis.closeEntry();
                zis.close();
            }
            uploadedInputStream.close();

            output += " SUCCESSFUL!\r\n";
            return ResponseEntity.ok(output);

        } catch (Exception e) {
            log.error("Cannot unzip resources " + zipFile, e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(output + "\r\n" + error);
        }
    }
}
