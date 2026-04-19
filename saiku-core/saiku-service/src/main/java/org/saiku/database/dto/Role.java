package org.saiku.database.dto;

import java.util.Set;
import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 * Created by bugg on 01/05/14.
 */
@Entity
@Table(name = "USER_ROLES")
public class Role {

    @Id
    @GeneratedValue
    private Integer id;

    private String role;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinTable(
            name = "USER_ROLES",
            joinColumns = {@JoinColumn(name = "USER_ROLE_ID", referencedColumnName = "id")},
            inverseJoinColumns = {@JoinColumn(name = "USER_ID", referencedColumnName = "id")})
    private Set<User> userRoles;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Set<User> getUserRoles() {
        return userRoles;
    }

    public void setUserRoles(Set<User> userRoles) {
        this.userRoles = userRoles;
    }
}
