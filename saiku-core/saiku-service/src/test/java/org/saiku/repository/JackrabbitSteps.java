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
package org.saiku.repository;

import org.saiku.service.user.UserService;

import net.thucydides.core.annotations.Step;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.jcr.*;

import static junit.framework.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by bugg on 14/05/14.
 */
public class JackrabbitSteps {

    private static final String DEFAULT_ROLE = "ROLE_USER";

    private final String repoBasePath = System.getProperty("user.dir") + "/target";
    private final String repoLocation = repoBasePath + "/repo-data" + System.currentTimeMillis();
    private final URL repoConf = this.getClass().getClassLoader().getResource("repo-conf.xml");
    private final String repoConfPath = repoConf.getPath();
    private final IRepositoryManager iRepositoryManager = JackRabbitRepositoryManager.getJackRabbitRepositoryManager
        (repoConfPath, repoLocation, "admin", "admin", DEFAULT_ROLE);
    private final UserService userService = mock(UserService.class);
    private final List<String> defaultRole = Collections.singletonList(DEFAULT_ROLE);

    @Rule
    public ExpectedException throwable = ExpectedException.none();

    @Step
    public void initializeRepository() {
        iRepositoryManager.init();
    }

    @Step
    public boolean startRepository() {
        when(userService.getAdminRoles()).thenReturn(Collections.singletonList("ROLE_ADMIN"));

        try {
            return iRepositoryManager.start(userService);
        } catch (RepositoryException e) {
            e.printStackTrace();
        }

        return false;
    }

    @Step
    public void initializeUsers(List<String> users) {
        for (String u : users) {
            try {
                iRepositoryManager.createUser(u);
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        }
    }

    public List<String> getHomeDirectoryList() {
        List<String> names = new ArrayList<>();
        NodeIterator nodes;
        nodes = null;//iRepositoryManager.getHomeFolders();
        while (nodes.hasNext()) {
            Node node = nodes.nextNode();
            try {
                AclEntry entry = new Acl2(node).getEntry(node.getPath());
                names.add(entry.getOwner());
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        }
        return names;
    }

    public Node getHomeDirectory(String directory) {
        Node node = null;
        node = null;// iRepositoryManager.getHomeFolder(directory);
        return node;
    }

    @Step
    public void shutdownRepository() {
        iRepositoryManager.shutdown();
    }

    @Step
    public void initializeDuplicateUsers(List<String> users) throws RepositoryException {
        try {
            for (String u : users) {
                iRepositoryManager.createUser(u);
            }
            fail("Expected to throw " + ItemExistsException.class.getName());
        }catch(ItemExistsException ignored){}
    }

    @Step
    public boolean createFolder(String username, String folder) throws RepositoryException {
        return iRepositoryManager.createFolder(username, folder);

    }

    @Step
    public boolean deleteFolder(String username, String folder) throws RepositoryException {
        return iRepositoryManager.deleteFolder(folder);
    }

    @Step
    @Test(expected = PathNotFoundException.class)
    public void getBrokenFolders(String user, String folder) throws RepositoryException {
        iRepositoryManager.getFolder(user, folder);
    }

    @Step
    public Node getFolders(String user, String folder) throws RepositoryException {
        return (Node) iRepositoryManager.getFolder(user, folder);
    }

    @Step
    public void deleteAllNodes() throws RepositoryException {
        iRepositoryManager.deleteRepository();
    }

    @Step
    public void moveFolder(String user, String folder, String source, String target) throws RepositoryException {
        if (target.equals("home")) {
            target = null;
        }
        iRepositoryManager.moveFolder(user, folder, source, target);
    }

    @Step
    public void saveFile(Object file, String path, String user, String type) throws RepositoryException {
        iRepositoryManager.saveFile(file, path, user, type, defaultRole);
    }

    @Step
    public String getFile(String s, String username) throws RepositoryException {
        return iRepositoryManager.getFile(s, username, defaultRole);
    }

    @Step
    public byte[] getBackup() throws IOException, RepositoryException {
        return iRepositoryManager.exportRepository();
    }

    @Step
    public void cleanRepositoryData() throws IOException {
        iRepositoryManager.shutdown();
        FileFilter fileFilter = new WildcardFileFilter("repo-data*");
        File[] files = new File(repoBasePath).listFiles(fileFilter);
        for(File file: files){
            if(file.isDirectory()) FileUtils.deleteDirectory(file);
        }
    }
}

