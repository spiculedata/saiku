package org.saiku.web.rest.objects.acl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.vfs.FileContent;
import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.NameScope;
import org.apache.commons.vfs.VFS;
import org.aspectj.apache.bcel.generic.RET;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
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

	/**
	 * holds the actual acl map
	 */
	private Map<String, AclEntry> acl = new TreeMap<String, AclEntry>();
	/**
	 * the logger 
	 */
	private static final Logger logger = LoggerFactory.getLogger(Acl.class);
	/**
	 * the file that holds the ACL
	 */
	private static final String SAIKUACCESS_FILE = ".saikuaccess";

	/**
	 * The list of the roles that can always manage a resource.
	 */
	private List<String> adminRoles;

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
			readAcl(repoRoot, true);
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


	/**
	 * Returns the access method to the specified resource for the user or role
	 * @param resource the resource to which you want to access
	 * @param username the username of the user that's accessing
	 * @param roles the role of the user that's accessing
	 * @return {@link AclMethod}
	 */
	public List<AclMethod> getMethods(FileObject resource, String username, List<String> roles) {
		try {
			String name = repoRoot.getName().getRelativeName(resource.getName());
			AclEntry entry = acl.get(name);
			AclMethod method;
			
			if (name.startsWith("..") || resource.getParent() == null) return getAllAcls(AclMethod.NONE);
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
				if (resource.equals(repoRoot)) {
					return getAllAcls(AclMethod.WRITE);
				} else {
					FileObject parent = resource.getParent();
					List<AclMethod> parentMethods = getMethods(parent, username, roles);
					method = AclMethod.max(parentMethods);
				}
			}

			return getAllAcls(method);
		} catch (Exception e) {
			logger.error("Cannot get methods for: " + resource, e);
		}
		List<AclMethod> noMethod = new ArrayList<AclMethod>();
		noMethod.add(AclMethod.NONE);
		return noMethod;
	}

	private List<AclMethod> getAllAcls(AclMethod maxMethod) {
		List<AclMethod> methods = new ArrayList<AclMethod>();
		for (AclMethod m : AclMethod.values()) {
			if (m.ordinal() > 0 && m.ordinal() <= maxMethod.ordinal()) {
				methods.add(m);
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
	public void addEntry(FileObject resource, AclEntry entry) {
		try {
			String key = repoRoot.getName().getRelativeName(resource.getName());
			acl.put(key, entry);
		} catch (FileSystemException e) {
			logger.error("Cannot add entry for resource: " + resource, e);
		}
	}

	public AclEntry getEntry(FileObject resource ) {
		try {
			String key = repoRoot.getName().getRelativeName(resource.getName());
			return acl.get(key);
		} catch (FileSystemException e) {
			logger.error("Cannot get entry for resource: " + resource, e);
		}
		return null;
	}

	/**
	 * Helper method to test if the resource is readable by the 
	 * user or roles
	 * @param resource the resource being tested
	 * @param username the user name that wants to access 
	 * @param roles the roles of the user that wants access
	 * @return true if the resource is marked > {@link AclMethod#NONE} 
	 */
	public boolean canRead(FileObject resource, String username, List<String> roles) {
		if ( resource == null ) return false;
		List<AclMethod> acls = getMethods(resource, username, roles);
		return acls.contains(AclMethod.READ); 
	}

	/**
	 * Helper method to test if the resource is writeable by a user or roles
	 * @param resource the resource being tested
	 * @param username the user name that wants to access 
	 * @param roles the roles of the user that wants access
	 * @return true if the resource is marked  {@link AclMethod#WRITE} or {@link AclMethod#GRANT} 
	 */
	public boolean canWrite(FileObject resource, String username, List<String> roles) {
		if ( resource == null ) return false;
		List<AclMethod> acls = getMethods(resource, username, roles);
		return acls.contains(AclMethod.WRITE);
	}
	/**
	 * Helper method to test if the resource is grantable by a user or role
	 * @param resource the resource being tested
	 * @param username the user name that wants to access 
	 * @param role the role of the user that wants access
	 * @return true if the resource is marked  {@link AclMethod#GRANT} 
	 */
	public boolean canGrant(FileObject resource, String username, List<String> roles) {
		List<AclMethod> acls = getMethods(resource, username, roles);
		return acls.contains(AclMethod.GRANT);
	}


	/**
	 * Searches the acl file for the resource in the resource's directory
	 * ( or inside the resource's children if the resource is a directory ), 
	 * reads the file and adds all the acl entries to the global acl map
	 * @param resource
	 */
	public void readAcl(FileObject resource, boolean recursive) {
		try {
			FileObject folder = 
				resource.getType().equals(FileType.FOLDER) ?
						resource : resource.getParent();
			FileObject jsonFile = folder.getChild(SAIKUACCESS_FILE);
			Map<String, AclEntry> aclMap = deserialize(jsonFile);
			acl.putAll(aclMap);

			if (recursive) {
				for ( FileObject file :  folder.getChildren()) {
					if (file.getType().equals(FileType.FOLDER)) {
						readAcl(file, recursive);
					}
				}
			}
		} catch (Exception e) {
			logger.error("Error while reading ACL files (recursive: " + recursive + ")", e);
		}
	}

	/**
	 * writes the acl of the resource in the resource's directory
	 * @param resource
	 */
	public void writeAcl(FileObject resource) {
		//TODO : shall throw an IllegalArgumentException ? 
		if ( resource == null ) return; //do nothing on null
		FileObject jsonFile = null;
		try{
			if ( resource.getType().equals(FileType.FOLDER) ) {
				// the json file is in here
				jsonFile = resource.resolveFile(SAIKUACCESS_FILE, NameScope.FILE_SYSTEM);
			} else {
				// the json file is in the parent folder
				jsonFile = resource.getParent().resolveFile(SAIKUACCESS_FILE, NameScope.FILE_SYSTEM);
			}
			if ( jsonFile != null && jsonFile.isWriteable() ) {
				serialize(jsonFile, getAcl(resource, false));
			}
		} catch ( FileSystemException e ) {
			logger.error("Error getting hold of the file " + SAIKUACCESS_FILE, e );
		}
	}
	/**
	 * Serializes in json format the acl passed as parameter into 
	 * the specified file
	 * @param jsonFile the file where to write the acl
	 * @param acl the acl to serialize
	 */
	private void serialize(FileObject jsonFile, Map<String,AclEntry> acl) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			Map<String,AclEntry> outputMap = new HashMap<String, AclEntry>();
			for (String key : acl.keySet()) {
				FileObject f = repoRoot.resolveFile(key);
				if (f != null && f.exists()) {
					String filename = jsonFile.getParent().getName().getRelativeName(f.getName());
					if (!filename.equals(SAIKUACCESS_FILE)) {
						outputMap.put(filename, acl.get(key));
					}
				}
			}
			jsonFile.delete();
			OutputStreamWriter ow = new OutputStreamWriter(jsonFile.getContent().getOutputStream());
			BufferedWriter bw = new BufferedWriter(ow);
			mapper.writeValue(bw, outputMap);
			bw.close();
			ow.close();
		} catch (JsonGenerationException e) {
			logger.error("Error generating json file",e);
		} catch (JsonMappingException e) {
			logger.error("Error mapping Acl to JSON ", e);
		} catch (IOException e) {
			logger.error("Error writing data to file",e);

		}

	}
	/**
	 * internal method. reads the acl file and creates an acl map
	 * @param jsonFile the file containing the acl 
	 * @return the acl map represented by the acl file
	 */
	private Map<String, AclEntry> deserialize(FileObject jsonFile) {
		ObjectMapper mapper = new ObjectMapper();

		InputStream is;
		Map<String, AclEntry> acl = new TreeMap<String, AclEntry>();
		if ( jsonFile != null ) {
			try {
				FileContent fc = jsonFile.getContent();
				is = fc.getInputStream();
				acl = (Map<String, AclEntry>) mapper.readValue(is, TypeFactory
						.mapType(HashMap.class, String.class, AclEntry.class));
				fc.close();
				is.close();
				Map<String, AclEntry> returnMap = new TreeMap<String, AclEntry>();
				for (String key : acl.keySet()) {
					AclEntry entry = acl.get(key);
					FileName fn = jsonFile.getParent().resolveFile(key).getName();					
					String path = repoRoot.getName().getRelativeName(fn); 
					returnMap.put(path, entry);
				}
				return returnMap;
			} catch (FileSystemException e) {
				logger.error("Error opening the file ",e);
			} catch (JsonParseException e) {
				logger.error("Error parsing the json file", e);
			} catch (JsonMappingException e) {
				logger.error("Error converting the json file", e);
			} catch (IOException e) {
				logger.error("IO error reading the json file", e);
			}
		}
		return new HashMap<String, AclEntry>();
	}

	/**
	 * Returns the acl of the resource. If the resource is a directory
	 * it returns the acl of the resource and of all the children. If 
	 * any of the children is a folder then its content is not inspected 
	 * @param resource the resource
	 * @return
	 */
	private Map<String, AclEntry> getAcl(FileObject resource) {
		return getAcl(resource, false);
	}
	/**
	 * Returns the acl of the resource. If the resource is a directory
	 * it returns the acl of the resource and of all the children. 
	 * If the parameter <pre>recurse</pre> is <pre>true</pre> then 
	 * if any of the children is a directory its content is inspected 
	 * @param resource
	 * @param recurse
	 * @return
	 */
	private Map<String, AclEntry> getAcl(FileObject resource, boolean recurse) {

		Map<String, AclEntry> acl = new TreeMap<String, AclEntry>();

		try {
			String key = repoRoot.getName().getRelativeName(resource.getName());
			if ( resource.getType().equals(FileType.FOLDER) ) {
				for ( FileObject file :  resource.getChildren() ) {
					acl.putAll(getAcl(file,recurse));
				}
			}

			AclEntry entry = this.acl.get(key);
			if ( entry == null ) {
				entry = new AclEntry();
			}
			if ( entry != null ) acl.put(key, entry);


		} catch (FileSystemException e) {
			logger.error("Error traversing the resource " + resource.getName().getPath() ,e );
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
}
