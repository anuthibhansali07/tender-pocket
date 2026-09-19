package com.tenderpocket.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Apply price confidentiality to nested DTOs as well as direct tender responses. */
@ControllerAdvice(basePackages = "com.tenderpocket.controllers")
public class ManufacturerPriceAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper mapper;
    public ManufacturerPriceAdvice(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter);
    }

    @Override public Object beforeBodyWrite(Object body, MethodParameter type, MediaType media,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || WorkflowPermissions.allowed(WorkflowPermissions.Action.VIEW_TPC_PRICE)) return body;
        JsonNode tree = mapper.valueToTree(body);
        redact(tree);
        return tree;
    }

    static void redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String key : new String[]{"tpc_purchase_price", "tpcPurchasePrice",
                    "ai_details_summary", "ai_history_summary", "aiDetailsSummary", "aiHistorySummary"}) {
                if (object.has(key)) object.putNull(key);
            }
            if ("TPC_PRICING".equals(object.path("stage").asText()) || "TPC_PRICING".equals(object.path("phase").asText())) {
                for (String key : new String[]{"comment", "comment_text", "commentText"}) {
                    if (object.has(key)) object.put(key, "[Manufacturer pricing note restricted]");
                }
            }
        }
        if (node.isContainerNode()) node.forEach(ManufacturerPriceAdvice::redact);
    }
}
