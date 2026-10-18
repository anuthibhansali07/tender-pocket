package com.tenderpocket.controllers;

import com.tenderpocket.models.*;
import com.tenderpocket.repositories.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ApprovalControllerWorkflowTest {

    @Mock
    private TenderRepository tenderRepository;

    @Mock
    private TenderApprovalRepository approvalRepository;

    @Mock
    private TenderCommentRepository commentRepository;

    @InjectMocks
    private ApprovalController approvalController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT).replace(' ', '_')))
                )
        );
    }

    @Test
    void testMisPricingApprovalUpdatesTenderPriceAndAdvancesStage() {
        authenticate("misteam", "MIS Team");

        Tender tender = new Tender();
        tender.setId("T-MIS-1");
        tender.setCurrentStage("MIS_PRICING");
        tender.setSpecVerificationStatus("Approved");
        tender.setTpcPurchasePrice(100000.0);
        tender.setMisFinalPrice(null);

        TenderApprovalRequest request = new TenderApprovalRequest("T-MIS-1", TenderWorkflowStage.MIS_PRICING, "tpc", "misteam", "PENDING");
        request.setId(10L);

        when(approvalRepository.findById(10L)).thenReturn(Optional.of(request));
        when(tenderRepository.findById("T-MIS-1")).thenReturn(Optional.of(tender));
        when(approvalRepository.findByTenderIdAndStageOrderByCreatedAtDesc(eq("T-MIS-1"), any())).thenReturn(List.of(request));

        Map<String, Object> body = Map.of(
                "action", "APPROVED",
                "comment", "MIS pricing reviewed and finalized",
                "misFinalPrice", 135000.0
        );

        ResponseEntity<?> response = approvalController.reviewApproval(10L, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(135000.0, tender.getMisFinalPrice(), "Tender mis_final_price must be updated from review body");
        assertEquals("BID_DOC_PENDING", tender.getCurrentStage(), "Stage must advance to BID_DOC_PENDING");
        assertEquals("APPROVED", request.getStatus());
        assertEquals(135000.0, request.getMisFinalPrice());
        verify(tenderRepository).save(tender);
        verify(approvalRepository).save(request);
    }

    @Test
    void testTpcPricingApprovalUpdatesTenderPriceAndCreatesMisRequest() {
        authenticate("tpc", "TPC Pricing Team");

        Tender tender = new Tender();
        tender.setId("T-TPC-1");
        tender.setCurrentStage("TPC_PRICING");
        tender.setSpecVerificationStatus("Approved");
        tender.setTpcPurchasePrice(null);

        TenderApprovalRequest request = new TenderApprovalRequest("T-TPC-1", TenderWorkflowStage.TPC_PRICING, "clearance", "tpc", "PENDING");
        request.setId(20L);

        when(approvalRepository.findById(20L)).thenReturn(Optional.of(request));
        when(tenderRepository.findById("T-TPC-1")).thenReturn(Optional.of(tender));
        when(approvalRepository.findByTenderIdAndStageOrderByCreatedAtDesc(eq("T-TPC-1"), eq(TenderWorkflowStage.TPC_PRICING))).thenReturn(List.of(request));
        when(approvalRepository.findByTenderIdAndStageOrderByCreatedAtDesc(eq("T-TPC-1"), eq(TenderWorkflowStage.MIS_PRICING))).thenReturn(Collections.emptyList());

        Map<String, Object> body = Map.of(
                "action", "APPROVED",
                "comment", "TPC price obtained from vendor",
                "tpcPurchasePrice", 95000.0
        );

        ResponseEntity<?> response = approvalController.reviewApproval(20L, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(95000.0, tender.getTpcPurchasePrice(), "Tender tpc_purchase_price must be updated from review body");
        assertEquals("MIS_PRICING", tender.getCurrentStage(), "Stage must advance to MIS_PRICING");
        assertEquals("APPROVED", request.getStatus());
        assertEquals(95000.0, request.getTpcPurchasePrice());
        verify(tenderRepository).save(tender);
        // Verify forward MIS_PRICING request is created carrying TPC price
        verify(approvalRepository).save(argThat(r ->
                r.getStage() == TenderWorkflowStage.MIS_PRICING
                        && "PENDING".equals(r.getStatus())
                        && Double.valueOf(95000.0).equals(r.getTpcPurchasePrice())
        ));
    }

    @Test
    void testSpecClearanceApprovalAdvancesToTpcPricingAndCreatesTpcRequest() {
        authenticate("clearance", "Clearance Team");

        Tender tender = new Tender();
        tender.setId("T-SPEC-1");
        tender.setCurrentStage("SPEC_CLEARANCE");
        tender.setSpecVerificationStatus("Pending");

        TenderApprovalRequest request = new TenderApprovalRequest("T-SPEC-1", TenderWorkflowStage.SPEC_CLEARANCE, "executive", "clearance", "PENDING");
        request.setId(30L);

        when(approvalRepository.findById(30L)).thenReturn(Optional.of(request));
        when(tenderRepository.findById("T-SPEC-1")).thenReturn(Optional.of(tender));
        when(approvalRepository.findByTenderIdAndStageOrderByCreatedAtDesc(eq("T-SPEC-1"), eq(TenderWorkflowStage.SPEC_CLEARANCE))).thenReturn(List.of(request));
        when(approvalRepository.findByTenderIdAndStageOrderByCreatedAtDesc(eq("T-SPEC-1"), eq(TenderWorkflowStage.TPC_PRICING))).thenReturn(Collections.emptyList());

        Map<String, Object> body = Map.of(
                "action", "APPROVED",
                "comment", "Specifications fully compliant and verified"
        );

        ResponseEntity<?> response = approvalController.reviewApproval(30L, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Approved", tender.getSpecVerificationStatus());
        assertEquals("TPC_PRICING", tender.getCurrentStage());
        assertEquals("APPROVED", request.getStatus());
        verify(tenderRepository).save(tender);
        // Verify forward TPC_PRICING request is created
        verify(approvalRepository).save(argThat(r ->
                r.getStage() == TenderWorkflowStage.TPC_PRICING
                        && "PENDING".equals(r.getStatus())
        ));
    }
}
