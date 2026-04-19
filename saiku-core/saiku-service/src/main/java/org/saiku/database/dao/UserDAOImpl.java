package org.saiku.database.dao;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.saiku.database.dto.User;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by bugg on 01/05/14.
 */
public class UserDAOImpl implements UserDAO {

    @Autowired
    private SessionFactory sessionFactory;

    private Session openSession() {
        return sessionFactory.getCurrentSession();
    }

    public User getUser(String login) {
        List<User> userList = new ArrayList<>();
        Query query = null; // openSession().createQuery("from User u where u.login = :login");
        query.setParameter("login", login);
        userList = query.list();
        if (userList.size() > 0) return userList.get(0);
        else return null;
    }
}
