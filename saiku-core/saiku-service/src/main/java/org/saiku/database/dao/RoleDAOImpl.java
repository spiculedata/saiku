package org.saiku.database.dao;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.saiku.database.dto.Role;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by bugg on 01/05/14.
 */
public class RoleDAOImpl implements RoleDAO {

    @Autowired
    private SessionFactory sessionFactory;

    private Session getCurrentSession() {
        return sessionFactory.getCurrentSession();
    }

    public Role getRole(int id) {
        return (Role) getCurrentSession().load(Role.class, id);
    }
}
