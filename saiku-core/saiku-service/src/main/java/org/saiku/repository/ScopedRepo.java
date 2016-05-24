package org.saiku.repository;


import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * Created by bugg on 06/05/16.
 */
public class ScopedRepo {


    private HttpSession httpSession;

    public ScopedRepo() {

    }

    public void setSession(HttpSession httpSession){
        this.httpSession = httpSession;
    }

    public HttpSession getSession(){
        if (this.httpSession == null) {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest req = attr.getRequest();
            this.httpSession = req.getSession(false); // true == allow create
        }

        return this.httpSession;
    }
}
