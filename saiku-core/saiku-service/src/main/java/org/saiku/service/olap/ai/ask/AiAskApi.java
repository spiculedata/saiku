/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;

/**
 * DTOs for the {@code POST /saiku/api/ai/ask} wire contract.
 *
 * <p>Held in saiku-service so both the resource and any future MCP/agent consumer can deserialise
 * against the same types. Names follow the existing AI Query convention.
 */
public final class AiAskApi {

    private AiAskApi() {}

    /** Wire shape for {@code POST /saiku/api/ai/ask} body. */
    public static class AskRequest {
        private String question;
        private AiCubeRef cube;
        private List<NlAskMessageDto> history;
        /**
         * Optional markdown digest of the user's currently-rendered cellset (built client-side from
         * {@code query.result}). When present, the model can route to the {@code emit_insight} tool
         * ('spot trends') or pick a sensible chart for {@code emit_view_change}. When absent, only
         * {@code emit_query} is realistically reachable. Subject to {@code AiPolicyGuard} on the
         * server — schema-only mode strips the digest before it reaches the LLM.
         */
        private String cellsetDigest;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String v) {
            this.question = v;
        }

        public AiCubeRef getCube() {
            return cube;
        }

        public void setCube(AiCubeRef v) {
            this.cube = v;
        }

        public List<NlAskMessageDto> getHistory() {
            return history;
        }

        public void setHistory(List<NlAskMessageDto> v) {
            this.history = v;
        }

        public String getCellsetDigest() {
            return cellsetDigest;
        }

        public void setCellsetDigest(String v) {
            this.cellsetDigest = v;
        }

        /** Convert the wire-shape history into the service-layer record list. */
        public List<NlAskMessage> historyAsMessages() {
            if (history == null || history.isEmpty()) return List.of();
            List<NlAskMessage> out = new ArrayList<>(history.size());
            for (NlAskMessageDto dto : history) {
                if (dto == null || dto.getRole() == null || dto.getContent() == null) continue;
                if ("assistant".equalsIgnoreCase(dto.getRole())) {
                    out.add(NlAskMessage.assistant(dto.getContent()));
                } else {
                    // Default to user for any other / unknown role string — the UI shouldn't
                    // surface system messages.
                    out.add(NlAskMessage.user(dto.getContent()));
                }
            }
            return out;
        }
    }

    /** One conversation turn over the wire — role + content. */
    public static class NlAskMessageDto {
        private String role;
        private String content;

        public NlAskMessageDto() {}

        public NlAskMessageDto(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String v) {
            this.role = v;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String v) {
            this.content = v;
        }
    }

    /**
     * Wire response for {@code POST /saiku/api/ai/ask}.
     *
     * <p>On success: {@code degraded=false}, {@code request} is the structured query the model
     * emitted (handy for the UI's "edit in canvas" flow), {@code response} is the full
     * {@link AiQueryResponse} from executing it, and {@code generatedMdx} mirrors
     * {@code response.metadata.generatedMdx} for callers that don't want to dig.
     *
     * <p>On degraded: {@code degraded=true}, {@code reason} carries the explanation, other fields
     * are null. HTTP status is 503 when the provider isn't configured, 200 when execution failed
     * after a successful translation (so the client can still display the model's MDX guess).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AskResponse {
        private boolean degraded;
        private String reason;
        private String model;
        private AiQueryRequest request;
        private AiQueryResponse response;
        private String generatedMdx;
        /**
         * The structured {@link ThinQueryModel} the converter produced for the AI's request — the
         * same shape the workbench's chip UI manipulates. When present, "edit in canvas" can hydrate
         * the workspace builder directly (interactive chips) instead of pasting the generated MDX
         * (which the user can't drag/drop). Absent on degraded paths and on paths where conversion
         * failed before producing a ThinQuery.
         */
        private ThinQueryModel queryModel;
        /**
         * Markdown analysis of the user's current cellset. Present when the model picked {@code
         * emit_insight}. Mutually exclusive with {@link #request} / {@link #queryModel} / {@link
         * #viewChange} — exactly one is non-null on success.
         */
        private AiInsight insight;
        /**
         * Target view mode + chart type. Present when the model picked {@code emit_view_change}.
         * Mutually exclusive with the other intents as above.
         */
        private AiViewChange viewChange;

        public boolean isDegraded() {
            return degraded;
        }

        public void setDegraded(boolean v) {
            this.degraded = v;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String v) {
            this.reason = v;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String v) {
            this.model = v;
        }

        public AiQueryRequest getRequest() {
            return request;
        }

        public void setRequest(AiQueryRequest v) {
            this.request = v;
        }

        public AiQueryResponse getResponse() {
            return response;
        }

        public void setResponse(AiQueryResponse v) {
            this.response = v;
        }

        public String getGeneratedMdx() {
            return generatedMdx;
        }

        public void setGeneratedMdx(String v) {
            this.generatedMdx = v;
        }

        public ThinQueryModel getQueryModel() {
            return queryModel;
        }

        public void setQueryModel(ThinQueryModel v) {
            this.queryModel = v;
        }

        public AiInsight getInsight() {
            return insight;
        }

        public void setInsight(AiInsight v) {
            this.insight = v;
        }

        public AiViewChange getViewChange() {
            return viewChange;
        }

        public void setViewChange(AiViewChange v) {
            this.viewChange = v;
        }
    }
}
