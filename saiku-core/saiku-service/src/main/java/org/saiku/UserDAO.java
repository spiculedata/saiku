package org.saiku;

import java.util.Collection;
import org.saiku.database.dto.SaikuUser;

/**
 * Created by bugg on 09/06/14.
 */
public interface UserDAO {

    SaikuUser insert(SaikuUser user);

    void insertRole(SaikuUser user);

    void deleteUser(SaikuUser user);

    void deleteRole(SaikuUser user);

    String[] getRoles(SaikuUser user);

    SaikuUser findByUserId(int userId);

    Collection findAllUsers();

    void deleteUser(String username);

    SaikuUser updateUser(SaikuUser user, boolean updatepassword);

    void updateRoles(SaikuUser user);
}
