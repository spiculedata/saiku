/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
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
    }
}
