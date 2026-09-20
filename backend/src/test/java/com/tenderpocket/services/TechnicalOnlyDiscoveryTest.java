package com.tenderpocket.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class TechnicalOnlyDiscoveryTest {
    private static final String ADMIN = "Contractors must submit documents and pay taxes. "
            + "This document contains only administrative conditions and no equipment requirements.";

    private byte[] pdf(boolean readable) throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (readable) try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 10);
                content.newLineAtOffset(20, 700); content.showText(ADMIN); content.endText();
            }
            document.save(out); return out.toByteArray();
        }
    }

    @Test void emptyDocumentNeedsOneCombinedCallAndNoSeparateDiscovery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override List<String> identifyProductsForConversion(String text) {
                fail("Discovery must be combined with extraction"); return List.of();
            }
            @Override List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                calls.incrementAndGet(); return completedEmptyRows();
            }
        };
        var generator = new DocumentGeneratorService();
        ReflectionTestUtils.setField(generator, "aiSpecificationIntelligenceService", ai);
        var result = generator.parseSpecificationClauses(pdf(true), "admin.pdf", Map.of());
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(result));
        assertEquals(1, calls.get());
    }

    @Test void failedCombinedBatchIsNotNoProducts() throws Exception {
        var ai = new AISpecificationIntelligenceService() {
            @Override List<String> identifyProductsForConversion(String text) {
                fail("Discovery must be combined with extraction"); return List.of();
            }
            @Override List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                return List.of();
            }
        };
        var generator = new DocumentGeneratorService();
        ReflectionTestUtils.setField(generator, "aiSpecificationIntelligenceService", ai);
        var result = generator.parseSpecificationClauses(pdf(true), "admin.pdf", Map.of());
        assertTrue(result.isEmpty());
        assertFalse(AISpecificationIntelligenceService.isCompletedEmpty(result));
    }

    @Test void scansAreReadNativelyOnceBeforeAnEmptyResult() throws Exception {
        var nativeCalls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override List<String> identifyProductsForConversion(String text) { return confirmedEmptyProducts(); }
            @Override List<String> identifyProductsInPdfBatch(String text, byte[] bytes) {
                fail("A separate native discovery request is unnecessary"); return List.of();
            }
            @Override List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                assertNotNull(bytes); nativeCalls.incrementAndGet(); return completedEmptyRows();
            }
        };
        var generator = new DocumentGeneratorService();
        ReflectionTestUtils.setField(generator, "aiSpecificationIntelligenceService", ai);
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(
                generator.parseSpecificationClauses(pdf(false), "scan.pdf", Map.of())));
        assertEquals(1, nativeCalls.get());
    }

    @Test void discoveryDistinguishesEmptyMalformedAndTruncatedResponses() {
        for (String response : List.of("{\"products\":[],\"readable\":true}",
                "{\"products\":[3],\"readable\":true}", "{\"products\":[],\"readable\":false}",
                "{\"status\":\"incomplete\",\"output_text\":\"{\\\"products\\\":[],\\\"readable\\\":true}\"}")) {
            var ai = new AISpecificationIntelligenceService() {
                @Override String postAzureResponse(String prompt, byte[] bytes, AzureOutput output, List<String> names) {
                    return response;
                }
            };
            assertEquals(response.equals("{\"products\":[],\"readable\":true}"),
                    AISpecificationIntelligenceService.isConfirmedEmptyProducts(ai.identifyProductsForConversion(ADMIN)));
        }
    }

    @Test void lowReasoningIsSetForDiscoveryAndExtraction() throws Exception {
        var ai = new AISpecificationIntelligenceService();
        var method = AISpecificationIntelligenceService.class.getDeclaredMethod("buildAzureResponsesPayload",
                String.class, byte[].class, AISpecificationIntelligenceService.AzureOutput.class, List.class);
        method.setAccessible(true);
        for (var output : AISpecificationIntelligenceService.AzureOutput.values()) {
            var payload = new ObjectMapper().readTree((String) method.invoke(ai, "Test", null, output, List.of()));
            assertEquals("low", payload.path("reasoning").path("effort").asText());
            assertEquals("gpt-5-nano", payload.path("model").asText());
        }
    }

    @Test void warrantyAndGeneralRequirementsAreRetainedWithoutRetry() {
        var calls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override String postAzureResponse(String prompt, byte[] bytes, AzureOutput output, List<String> names) {
                calls.incrementAndGet();
                assertTrue(prompt.contains("warranty, AMC/CMC"));
                return """
                        {"readable":true,"clauseDecisions":{"p1:3.10":"included","p1:3.11":"included"},"rows":[
                        {"clauseReference":"3.10","requirement":"Capacity shall be 100 litres.",
                         "productCategory":"Pump","sourceReference":"PDF p. 1","rowType":"requirement"},
                        {"clauseReference":"3.11","requirement":"Warranty shall be five years.",
                         "productCategory":"Pump","sourceReference":"PDF p. 1","rowType":"requirement"}]}
                        """;
            }
        };
        var rows = ai.processOcrAndSynthesizeClauses(
                "[SOURCE_PAGE pdf=\"1\"]\n3.10 Capacity shall be 100 litres.\n3.11 Warranty shall be five years.\n[/SOURCE_PAGE]",
                new byte[]{1}, Map.of(), List.of("Pump"));
        assertEquals(2, rows.size());
        assertEquals("3.10", rows.get(0)[0]);
        assertEquals("Capacity shall be 100 litres.", rows.get(0)[1]);
        assertEquals("3.11", rows.get(1)[0]);
        assertEquals(1, calls.get());
    }

    @Test void administrativeOnlyPageReturnsExplicitEmptyWithoutRetry() {
        var calls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override String postAzureResponse(String prompt, byte[] bytes, AzureOutput output, List<String> names) {
                calls.incrementAndGet();
                return "{\"rows\":[],\"noApplicableRequirements\":true,\"excludedClauseReferences\":[\"1.1\"]}";
            }
        };
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(ai.processOcrAndSynthesizeClauses(
                "[SOURCE_PAGE pdf=\"1\"]\n1.1 Enter the bidder registration number in the portal.\n[/SOURCE_PAGE]",
                new byte[]{1}, Map.of(), List.of("Pump"))));
        assertEquals(1, calls.get());
    }

    @Test void administrativeKeywordsInsideTechnicalParametersAreRetained() {
        String[] row = {"3.10", "Delivery pressure shall be 3 bar. Transport weight shall be 20 kg. "
                + "Training mode shall support offline operation. Warranty shall be five years.",
                "", "", "", "Pump", "-", "PDF p. 1", "requirement"};
        List<String[]> result = ReflectionTestUtils.invokeMethod(new AISpecificationIntelligenceService(),
                "complianceRequirementsOnly", Collections.singletonList(row));
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.get(0)[1].contains("Delivery pressure"));
        assertTrue(result.get(0)[1].contains("Transport weight"));
        assertTrue(result.get(0)[1].contains("Training mode"));
        assertTrue(result.get(0)[1].contains("Warranty"));
    }
}
