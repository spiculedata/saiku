package org.saiku.repository;

import java.io.Serializable;
import javax.servlet.http.HttpSession;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.session.HttpSessionCreatedEvent;

/**
 * Created by bugg on 06/05/16.
 */
public class ScopedRepo implements ApplicationListener<HttpSessionCreatedEvent>, Serializable {

    static final long serialVersionUID = 1L;

    private transient HttpSession httpSession;

    public ScopedRepo() {}

    public void onApplicationEvent(HttpSessionCreatedEvent sessionEvent) {
        if (httpSession == null) {
            this.setSession(sessionEvent.getSession());
        }
    }

    public void setSession(HttpSession httpSession) {
        this.httpSession = httpSession;
    }

    public HttpSession getSession() {
        return this.httpSession;
    }
}
