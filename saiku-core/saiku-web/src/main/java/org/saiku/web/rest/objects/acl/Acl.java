package org.saiku.web.rest.objects.acl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs.FileContent;
import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.NameScope;
import org.apache.commons.vfs.VFS;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.type.TypeFactory;
import org.saiku.web.rest.objects.acl.enumeration.AclMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * main object to which one can query resources access
 * 
 * @author ferrema
 * 
 */
public class Acl {

	private Map<String, AclEntry> acl = new TreeMap<String, AclEntry>();
	private static final Logger logger = LoggerFactory.getLogger(Acl.class);
	private static final String SAIKUACCESS_FILE = ".saikuaccess";

	private List<String> adminRoles;
	private AclMethod rootMethod = AclMethod.WRITE;
	private FileObject repoRoot;
	

	public void setPath(String path) throws Exception {
		FileSystemManager fileSystemManager;
		try {
			if (!path.endsWith("" + File.separatorChar)) {
				path += File.separatorChar;
			}
			fileSystemManager = VFS.getManager();
			FileObject fileObject;
			fileObject = fileSystemManager.resolveFile(path);
			if (fileObject == null) {
				throw new IOException("File cannot be resolved: " + path);
			}
			if(!fileObject.exists()) {
				throw new IOException("File does not exist: " + path);
			}
			repoRoot = fileObject;
			String rootPath = getPath(repoRoot);
			readAclTree(rootPath);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Sets the list of the administrator roles
	 * @param adminRoles
	 */
	public void setAdminRoles(List<String> adminRoles) {
		this.adminRoles = adminRoles;
	}
	
	public void setRootAcl(String rootAcl) {
		try {
			if (StringUtils.isNotBlank(rootAcl)) {
				AclMethod.valueOf(rootAcl);
			}
		} catch (Exception e) {	}
	}


	/**
	 * Returns the access method to the specified resource for the user or role
	 * @param resource the resource to which you want to access
	 * @param username the username of the user that's accessing
	 * @param roles the role of the user that's accessing
	 * @return {@link AclMethod}
	 */
	public List<AclMethod> getMethods(String path, String username, List<String> roles) {
		try {
			AclEntry entry = acl.get(path);
			AclMethod method;

			if (path.startsWith("..")) return getAllAcls(AclMethod.NONE);
			if ( isAdminRole(roles) ) return getAllAcls(AclMethod.GRANT);
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
					List<AclMethod> allMethods = new ArrayList<AclMethod>();

					List<AclMethod> userMethods = 
						entry.getUsers() != null && entry.getUsers().containsKey(username) ?
								entry.getUsers().get(username) : new ArrayList<AclMethod>();

								List<AclMethod> roleMethods = new ArrayList<AclMethod>();
								for (String role:roles) {
									List<AclMethod> r =
										entry.getRoles() != null && entry.getRoles().containsKey(role) ?
												entry.getRoles().get(role) : new ArrayList<AclMethod>();									
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
				FileObject resource = repoRoot.resolveFile(path);
				if (resource.getParent() == null) {
					method = AclMethod.NONE;
				} else if (resource.equals(repoRoot)) {
					return getAllAcls(rootMethod);
				} else {
					FileObject parent = resource.getParent();
					String parentPath = repoRoot.getName().getRelativeName(parent.getName());
					List<AclMethod> parentMethods = getMethods(parentPath, username, roles);
					method = AclMethod.max(parentMethods);
				}
			}

			return getAllAcls(method);
		} catch (Exception e) {
			logger.error("Cannot get methods for: " + path, e);
		}
		List<AclMethod> noMethod = new ArrayList<AclMethod>();
		noMethod.add(AclMethod.NONE);
		return noMethod;
	}

	private List<AclMethod> getAllAcls(AclMethod maxMethod) {
		List<AclMethod> methods = new ArrayList<AclMethod>();
		if (maxMethod != null) {
			for (AclMethod m : AclMethod.values()) {
				if (m.ordinal() > 0 && m.ordinal() <= maxMethod.ordinal()) {
					methods.add(m);
				}
			}
		}
		return methods;
	}

	/**
	 * helper method to add an acl entry
	 * @param resource resource for which we're setting the 
	 * access control
	 * @param entry
	 */
	public void addEntry(String path, AclEntry entry) {
		try {
			if (entry != null) {
				acl.put(path, entry);
				writeAcl(path, entry);
			}
		} catch (Exception e) {
			logger.error("Cannot add entry for resource: " + path, e);
		}
	}

	public AclEntry getEntry(String path) {
		return (acl.containsKey(path) ? acl.get(path) : null);
	}

	public boolean canRead(String path, String username, List<String> roles) {
		if ( path == null ) return false;
		List<AclMethod> acls = getMethods(path, username, roles);
		return acls.contains(AclMethod.READ); 
	}

	public boolean canWrite(String path, String username, List<String> roles) {
		if ( path == null ) return false;
		List<AclMethod> acls = getMethods(path, username, roles);
		return acls.contains(AclMethod.WRITE);
	}

	public boolean canGrant(String path, String username, List<String> roles) {
		List<AclMethod> acls = getMethods(path, username, roles);
		return acls.contains(AclMethod.GRANT);
	}

	private void readAclTree(String path) {
		try {
			FileObject resource = repoRoot.resolveFile(path);
			FileObject folder = 
				resource.getType().equals(FileType.FOLDER) ?
						resource : resource.getParent();
			
			FileObject jsonFile = getAccessFile(path);

			if (jsonFile.exists() && jsonFile.isReadable()) {
				Map<String, AclEntry> folderAclMap = deserialize(jsonFile);
				Map<String, AclEntry> aclMap = new TreeMap<String, AclEntry>();
				
				for (String key : folderAclMap.keySet()) {
					AclEntry entry = folderAclMap.get(key);
					FileName fn = folder.resolveFile(key).getName();					
					String childPath = repoRoot.getName().getRelativeName(fn); 
					aclMap.put(childPath, entry);
				}

				acl.putAll(aclMap);
			}

			for ( FileObject file :  folder.getChildren()) {
				if (file.getType().equals(FileType.FOLDER)) {
					String childPath = getPath(file);
					readAclTree(childPath);
				}
			}
		} catch (Exception e) {
			logger.error("Error while reading ACL files at path: " + path, e);
		}
	}

	/**
	 * writes the acl of the resource in the resource's directory
	 * @param resource
	 */
	private void writeAcl(String path, AclEntry entry) throws Exception {
		FileObject accessFile = getAccessFile(path);
		Map<String, AclEntry> map = deserialize(accessFile);
		FileObject f = repoRoot.resolveFile(path);
		String relativeKey = accessFile.getParent().getName().getRelativeName(f.getName());
		map.put(relativeKey, entry);
		serialize(accessFile, map);			
	}
	

	private void serialize(FileObject accessFile, Map<String, AclEntry> map) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			accessFile.delete();
			File outputFile = new File(accessFile.getURL().toURI());
			mapper.writeValue(outputFile, map);
		} catch (Exception e) {
			logger.error("Error writing data to file",e);
		}
	}
	
	private Map<String, AclEntry> deserialize(FileObject accessFile) {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, AclEntry> acl = new TreeMap<String, AclEntry>();
		try {
			if ( accessFile != null && accessFile.exists()) {
				File inputFile = new File(accessFile.getURL().toURI());
				acl = (Map<String, AclEntry>) mapper.readValue(inputFile, TypeFactory
						.mapType(HashMap.class, String.class, AclEntry.class));
			}
		} catch (Exception e) {
			logger.error("Error reading the json file:" + accessFile, e);
		}

		return acl;
	}

	/**
	 * Returns the list of the administrator roles
	 * @return
	 */
	public List<String> getAdminRoles() {
		return adminRoles;
	}
	/**
	 * Checks if a specific role is in the list of the admin roles
	 * @param role
	 * @return
	 */
	private boolean isAdminRole(String role){
		return adminRoles.contains(role);
	}
	/**
	 * Checks if a list of roles contains an admin role
	 * @param roles
	 * @return
	 */
	private boolean isAdminRole(List<String> roles){
		for (String role:roles)
			if ( isAdminRole(role)) return true;
		return false;
	}

	private String getPath(FileObject resource) throws FileSystemException {
		return repoRoot.getName().getRelativeName(resource.getName());

	}
	
	private FileObject getAccessFile(String path) {
		FileObject accessFile = null;
		try {
			FileObject resource = repoRoot.resolveFile(path);
			if ( resource != null && resource.exists() ) {
				FileObject jsonFile = null;

				if ( resource.getType().equals(FileType.FOLDER) ) {
					// the json file is in here
					jsonFile = resource.resolveFile(SAIKUACCESS_FILE, NameScope.FILE_SYSTEM);
				} else {
					// the json file is in the parent folder
					jsonFile = resource.getParent().resolveFile(SAIKUACCESS_FILE, NameScope.FILE_SYSTEM);
				}
				if ( jsonFile != null && jsonFile.isWriteable() ) {
					accessFile = jsonFile;
				}
			}
		} catch ( Exception e ) {
			logger.error("Error getting hold of the access file for " + path, e );
		}
		return accessFile;
	}

}
