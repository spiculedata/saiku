package org.saiku.web.rest.objects;

/**
 * Created by bugg on 03/12/14.
 */
public class UserList {
    private String name;
    private int id;

    public UserList() {}

    public UserList(String l2, int id) {
        name = l2;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}
