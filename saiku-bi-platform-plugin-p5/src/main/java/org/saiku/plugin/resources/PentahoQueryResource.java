package org.saiku.plugin.resources;

import org.saiku.olap.query2.ThinQuery;
import org.saiku.plugin.util.PentahoAuditHelper;
import org.saiku.service.user.UserService;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.saiku.web.rest.resources.Query2Resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.pentaho.platform.util.logging.SimpleLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

@Path("/saiku/api/{username}/query")
public class PentahoQueryResource extends Query2Resource {
  private static final Logger log = LoggerFactory.getLogger(PentahoQueryResource.class);
  private final PentahoAuditHelper pah = new PentahoAuditHelper();
  private UserService userService;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   *
   * Execute a Saiku Query
   * Execute Query
   * @param tq Thin Query model
   * @return A query result set.
   */
  @POST
  @Consumes({"application/json" })
  @Path("/execute")
  public QueryResult execute(ThinQuery tq) {
    long start = System.currentTimeMillis();
    Map<String,String> logelements = new HashMap<String, String>();
    logelements.put("username", userService.getActiveUsername());

    UUID uuid = pah.startAudit("Saiku", "Execute Query", this.getClass().getName(), userService.getActiveUsername(),
        userService.getSessionId(),
        createLogEntry(logelements),
        getLogger());
    QueryResult result = super.execute(tq);
    long end = System.currentTimeMillis();

    logelements.put("Username", userService.getActiveUsername());
    logelements.put("Executed MDX", ((ThinQuery)result.getQuery()).getMdx());
    //logelements.put("Result Set", resultToJson(result));
    pah.endAudit("Saiku", "Execute Query", this.getClass().getName(), userService.getActiveUsername(), userService.getSessionId(),
        createLogEntry(logelements), getLogger(), start,
            uuid,
            end);

    return result;
  }

  public void setUserService(UserService us) {
    userService = us;
  }

  private String createLogEntry(Map<String, String> elements){
    StringBuilder sb = new StringBuilder();
    Iterator it = elements.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry pair = (Map.Entry)it.next();
      sb.append(pair.getKey()).append(":").append(pair.getValue()).append("\n");
      it.remove();
    }
    return sb.toString();
  }

  private SimpleLogger getLogger(){
    return new SimpleLogger(PentahoQueryResource.class.getName());
  }

  private String resultToJson(QueryResult result){
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      log.error("Could not convert result to json for audit logs.");
    }
    return null;
  }
}
