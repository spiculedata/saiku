/*
 * Copyright 2014 OSBI Ltd
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
 */

package org.saiku.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.commons.JcrUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

/**
 * Created by bugg on 24/06/14.
 */
class Acl2 {

  private static final Logger LOG = LoggerFactory.getLogger(Acl2.class);

  private List<String> adminRoles;


  public Acl2(File n) {
  }

  public void setAdminRoles(List<String> adminRoles) {
    this.adminRoles = adminRoles;
  }

  private AclMethod rootMethod = AclMethod.WRITE;

  @NotNull
  private final Map<String, AclEntry> acl = new TreeMap<>();

  public Acl2(@NotNull Node root) {
    readAclTree(root);
  }

  /**
   * Returns the access method to the specified resource for the user or role
   *
   * @param node     the resource to which you want to access
   * @param username the username of the user that's accessing
   * @param roles    the role of the user that's accessing
   * @return
   */
  @NotNull
  public List<AclMethod> getMethods(@NotNull Node node, String username, @NotNull List<String> roles) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      //LOG.debug("Set ACL to " + object + " : " + acl);
      //String acl = null;
      AclEntry entry = null;
      Map<String, AclEntry> acl = new TreeMap<>();

      try {
        TypeReference ref = new TypeReference<Map<String, AclEntry>>() { };
        acl = mapper.readValue(node.getProperty("owner").getString(), ref);
        // mapper.readValue(acl, AclEntry.class);
        entry = acl.get(node.getPath());
        ///entry = e.getValue();
      } catch (PathNotFoundException e) {
        //Figure out if its a folder

        if(node.getMixinNodeTypes().length==0 && (FilenameUtils.getExtension(node.getName()).equals("")

                                                  || FilenameUtils.getExtension(node.getName())== null)){
          node.addMixin("nt:saikufolders");
        }




        HashMap<String, List<AclMethod>> m = new HashMap<>();
        AclEntry e2 = new AclEntry("admin", AclType.PUBLIC, m, null);
        Acl2 acl2 = new Acl2(node);
        acl2.addEntry(node.getPath(), e2);
        acl2.serialize(node);

        node.getSession().save();

        LOG.debug("Path(owner) not found: " + node.getPath(), e.getCause());
      } catch (Exception e) {
        LOG.debug("Exception: " + node.getPath(), e.getCause());
      }
      AclMethod method;

      if (node.getPath().startsWith("..")) {
        return getAllAcls(AclMethod.NONE);
      }
      if (isAdminRole(roles)) {
        return getAllAcls(AclMethod.GRANT);
      }
      if (entry != null) {
        switch (entry.getType()) {
        case PRIVATE:
          if (!entry.getOwner().equals(username)) {
            method = AclMethod.NONE;
          } else {
            method = AclMethod.GRANT;
          }
          break;
        case SECURED:
          // check user permission
          List<AclMethod> allMethods = new ArrayList<>();

          if (StringUtils.isNotBlank(entry.getOwner()) && entry.getOwner().equals(username)) {
            allMethods.add(AclMethod.GRANT);

          }
          List<AclMethod> userMethods =
              entry.getUsers() != null && entry.getUsers().containsKey(username)
              ? entry.getUsers().get(username) : new ArrayList<AclMethod>();

          List<AclMethod> roleMethods = new ArrayList<>();
          for (String role : roles) {
            List<AclMethod> r =
                entry.getRoles() != null && entry.getRoles().containsKey(role)
                ? entry.getRoles().get(role) : new ArrayList<AclMethod>();
            roleMethods.addAll(r);
          }

          allMethods.addAll(userMethods);
          allMethods.addAll(roleMethods);

          if (allMethods.size() == 0) {
            // no role nor user acl
            method = AclMethod.NONE;
          } else {
            // return the strongest role
            method = AclMethod.max(allMethods);
          }

          break;
        default:
          // PUBLIC ACCESS
          method = AclMethod.WRITE;
          break;
        }
      } else {


        if (node.getParent() == null) {
          method = AclMethod.NONE;
        } else if (node.getParent().getName().equals("/")) {
          return getAllAcls(rootMethod);
        } else {
          Node parent = node.getParent();

          List<AclMethod> parentMethods = getMethods(parent, username, roles);
          method = AclMethod.max(parentMethods);
        }
      }
      //  String parentPath = repoRoot
      return getAllAcls(method);
    } catch (Exception e) {
      LOG.debug("Error", e.getCause());
    }
    List<AclMethod> noMethod = new ArrayList<>();
    noMethod.add(AclMethod.NONE);
    return noMethod;
  }


  public void setRootAcl(String rootAcl) {
    try {
      if (StringUtils.isNotBlank(rootAcl)) {
        rootMethod = AclMethod.valueOf(rootAcl);
      }
    } catch (Exception e) {
      LOG.error("Failed to set root ACL", e);
    }
  }


  @NotNull
  private List<AclMethod> getAllAcls(@Nullable AclMethod maxMethod) {
    List<AclMethod> methods = new ArrayList<>();
    if (maxMethod != null) {
      for (AclMethod m : AclMethod.values()) {
        if (m.ordinal() > 0 && m.ordinal() <= maxMethod.ordinal()) {
          methods.add(m);
        }
      }
    }
    return methods;
  }

  public boolean canGrant(@NotNull Node node, String username, @NotNull List<String> roles) {
    List<AclMethod> acls = getMethods(node, username, roles);
    return acls.contains(AclMethod.GRANT);
  }

  public void addEntry(String path, @Nullable AclEntry entry) {
    try {
      if (entry != null) {
        acl.put(path, entry);
        //writeAcl( path, entry );
      }
    } catch (Exception e) {
      //logger.error( "Cannot add entry for resource: " + path, e );
    }
  }

  public Node serialize(@NotNull Node node) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      node.setProperty("owner", "");
      node.setProperty("owner", mapper.writeValueAsString(acl));
      return node;
    } catch (Exception e) {
      try {
        LOG.info("Error while reading ACL files at path: " + node.getPath(), e.getCause());
      } catch (RepositoryException e1) {
        LOG.info("Repository Exception", e1.getCause());
      }
    }
    return node;
  }

  private Map<String, AclEntry> deserialize(@Nullable Node node) {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, AclEntry> acl = new TreeMap<>();
    try {
      if (node != null && node.getProperty("owner") != null) {
        TypeReference ref = new TypeReference<Map<String, AclEntry>>() { };

        acl = mapper.readValue(node.getProperty("owner").getString(), ref);
      }
    } catch (Exception e) {

      try {
        LOG.info("Error while reading ACL files at path: " + node.getPath(), e.getCause());
      } catch (RepositoryException e1) {
        LOG.info("Repository Exception", e1.getCause());
      }
    }

    return acl;
  }

  @Nullable
  public AclEntry getEntry(String path) {
    return acl.containsKey(path) ? acl.get(path) : null;
  }

  public boolean canRead(@Nullable Node path, String username, @NotNull List<String> roles) {
    if (path == null) {
      return false;
    }
    List<AclMethod> acls = getMethods(path, username, roles);
    return acls.contains(AclMethod.READ);
  }

  public boolean canWrite(@Nullable Node path, String username, @NotNull List<String> roles) {
    if (path == null) {
      return false;
    }
    List<AclMethod> acls = getMethods(path, username, roles);
    return !acls.contains(AclMethod.WRITE);
  }


  private void readAclTree(@NotNull Node resource) {
    try {

      String s = resource.getPrimaryNodeType().getName();
      //resource.getPrimaryNodeType().getName().equals( "nt:folder" )
      //        ? resource : resource.getParent();

      String jsonFile = resource.getProperty("owner").getString();

      if (jsonFile != null && !jsonFile.equals("")) {
        Map<String, AclEntry> folderAclMap = deserialize(resource);
        Map<String, AclEntry> aclMap = new TreeMap<>();

        for (String key : folderAclMap.keySet()) {
          if (key.equals(resource.getPath())) {
            AclEntry entry = folderAclMap.get(key);
            //FileName fn = folder.resolveFile( key ).getName();
            //String childPath = repoRoot.getName().getRelativeName( fn );
            aclMap.put(resource.getPath(), entry);
          }
        }

        acl.putAll(aclMap);
      }

      for (Node file : JcrUtils.getChildNodes(resource)) {
        //if ( file.getPrimaryNodeType().equals( "nt:folder" ) ) {
        if (!file.getName().equals("/") && !file.getName().startsWith("jcr:") && !file.getName().startsWith("rep:")) {

          readAclTree(file);
        }
        //}
      }
    } catch (Exception e) {

      try {
        LOG.info("Error while reading ACL files at path: " + resource.getPath(), e.getCause());

      } catch (RepositoryException e1) {
        LOG.info("Repository Exception", e1.getCause());
      }
    }
  }

  /**
   * Returns the list of the administrator roles
   *
   * @return
   */
  public List<String> getAdminRoles() {
    return adminRoles;
  }

  /**
   * Checks if a specific role is in the list of the admin roles
   *
   * @param role
   * @return
   */
  private boolean isAdminRole(String role) {
    return adminRoles.contains(role);
  }

  /**
   * Checks if a list of roles contains an admin role
   *
   * @param roles
   * @return
   */
  private boolean isAdminRole(@NotNull List<String> roles) {
    for (String role : roles) {
      if (isAdminRole(role)) {
        return true;
      }
    }
    return false;
  }

  // File based methods
  public void serialize(File n) {
    try {
      File f = new File(n, "acl.json");
      ObjectMapper mapper = new ObjectMapper();
      mapper.writeValue(f, acl);
    } catch (Exception e) {
      LOG.info("Error while writing ACL files at path: " + n.getPath(), e.getCause());
    }
  }

  public boolean canWrite(File node, String user, List<String> roles) {
    if (node == null) {
      return false;
    }
    List<AclMethod> acls = getMethods(node, user, roles);
    return acls.contains(AclMethod.WRITE);
  }

  public boolean canRead(File node, String user, List<String> roles) {
    if (node == null) {
      return false;
    }
    List<AclMethod> acls = getMethods(node, user, roles);
    return acls.contains(AclMethod.READ);
  }

  public boolean canGrant(File node, String user, List<String> roles) {
    if (node == null) {
      return false;
    }
    List<AclMethod> acls = getMethods(node, user, roles);
    return acls.contains(AclMethod.GRANT);
  }

  public boolean canRead(String relativePath, String user, List<String> roles) {
    if (relativePath == null) {
      return false;
    }
    List<AclMethod> acls = getMethods(new File(relativePath), user, roles);
    return acls.contains(AclMethod.READ);
  }

  @NotNull
  public List<AclMethod> getMethods(@NotNull File file, String username, @NotNull List<String> roles) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      AclEntry entry = null;
      Map<String, AclEntry> aclData = new TreeMap<>();

      try {
        TypeReference ref = new TypeReference<Map<String, AclEntry>>() { };
        aclData = mapper.readValue(new File(file, "acl.json"), ref);
        entry = aclData.get(file.getPath());
      } catch (Exception e) {
        LOG.debug("Exception: " + file.getPath(), e.getCause());
      }

      AclMethod method;

      if (file.getPath().startsWith("..")) {
        return getAllAcls(AclMethod.NONE);
      }
      if (isAdminRole(roles)) {
        return getAllAcls(AclMethod.GRANT);
      }

      if (entry != null) {
        switch (entry.getType()) {
          case PRIVATE:
            if (!entry.getOwner().equals(username)) {
              method = AclMethod.NONE;
            } else {
              method = AclMethod.GRANT;
            }
            break;
          case SECURED:
            // check user permission
            List<AclMethod> allMethods = new ArrayList<>();

            if (StringUtils.isNotBlank(entry.getOwner()) && entry.getOwner().equals(username)) {
              allMethods.add(AclMethod.GRANT);
            }

            List<AclMethod> userMethods =
                entry.getUsers() != null && entry.getUsers().containsKey(username)
                    ? entry.getUsers().get(username) : new ArrayList<AclMethod>();

            List<AclMethod> roleMethods = new ArrayList<>();
            for (String role : roles) {
              List<AclMethod> r =
                  entry.getRoles() != null && entry.getRoles().containsKey(role)
                      ? entry.getRoles().get(role) : new ArrayList<AclMethod>();
              roleMethods.addAll(r);
            }

            allMethods.addAll(userMethods);
            allMethods.addAll(roleMethods);

            if (allMethods.size() == 0) {
              // no role nor user acl
              method = AclMethod.NONE;
            } else {
              // return the strongest role
              method = AclMethod.max(allMethods);
            }

            break;
          default:
            // PUBLIC ACCESS
            method = AclMethod.WRITE;
            break;
        }
      } else {
        if (file.getParentFile() == null) {
          method = AclMethod.NONE;
        } else if (file.getParentFile().getName().equals("/")) {
          return getAllAcls(rootMethod);
        } else {
          List<AclMethod> parentMethods = getMethods(file.getParentFile(), username, roles);
          method = AclMethod.max(parentMethods);
        }
      }

      //  String parentPath = repoRoot
      return getAllAcls(method);
    } catch (Exception e) {
      LOG.debug("Error", e.getCause());
    }

    List<AclMethod> noMethod = new ArrayList<>();
    noMethod.add(AclMethod.NONE);
    return noMethod;
  }

}