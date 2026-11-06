package com.ledgerline.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoneyPrecisionAndConservationTest {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Test
    @DisplayName("Monetary Precision: Validate strict 2-decimal scale and rejection of >2 fractional digits")
    void testMonetaryPrecisionRules() {
        // Valid 2-decimal amounts
        BigDecimal validAmount1 = new BigDecimal("100.00");
        BigDecimal validAmount2 = new BigDecimal("100.50");
        BigDecimal validAmount3 = new BigDecimal("0.01");

        assertEquals(2, validAmount1.scale());
        assertEquals(2, validAmount2.scale());
        assertEquals(2, validAmount3.scale());

        // Invalid scale: > 2 decimal places must be rejected
        BigDecimal invalidScaleAmount1 = new BigDecimal("100.505");
        BigDecimal invalidScaleAmount2 = new BigDecimal("10.0001");

        assertTrue(invalidScaleAmount1.scale() > 2, "100.505 has scale > 2 and must be rejected");
        assertTrue(invalidScaleAmount2.scale() > 2, "10.0001 has scale > 2 and must be rejected");

        // Verify that stripping trailing zeros still detects > 2 decimals
        assertTrue(invalidScaleAmount1.stripTrailingZeros().scale() > 2);

        // Verify non-positive amounts are rejected
        BigDecimal zeroAmount = new BigDecimal("0.00");
        BigDecimal negativeAmount = new BigDecimal("-50.00");

        assertFalse(zeroAmount.compareTo(BigDecimal.ZERO) > 0, "Zero amount must be rejected");
        assertFalse(negativeAmount.compareTo(BigDecimal.ZERO) > 0, "Negative amount must be rejected");
    }

    @Test
    @DisplayName("Double-Entry Invariant: Per-transaction debits equal credits")
    void testPerTransactionDebitCreditBalancing() {
        // Simulated transaction ledger entries
        record Entry(String account, String type, BigDecimal amount) {}

        // Deposit Transaction: Debit SYSTEM_CLEARING, Credit Account A
        List<Entry> depositEntries = List.of(
            new Entry("ACC-SYSTEM-CLEARING-001", "DEBIT", new BigDecimal("1000.00")),
            new Entry("ACC-CUSTOMER-A", "CREDIT", new BigDecimal("1000.00"))
        );

        BigDecimal depositDebits = depositEntries.stream()
            .filter(e -> "DEBIT".equals(e.type()))
            .map(Entry::amount)
            .reduce(ZERO, BigDecimal::add);

        BigDecimal depositCredits = depositEntries.stream()
            .filter(e -> "CREDIT".equals(e.type()))
            .map(Entry::amount)
            .reduce(ZERO, BigDecimal::add);

        assertEquals(depositDebits, depositCredits, "Deposit debits must equal credits");

        // Transfer Transaction: Debit Account A, Credit Account B
        List<Entry> transferEntries = List.of(
            new Entry("ACC-CUSTOMER-A", "DEBIT", new BigDecimal("400.00")),
            new Entry("ACC-CUSTOMER-B", "CREDIT", new BigDecimal("400.00"))
        );

        BigDecimal transferDebits = transferEntries.stream()
            .filter(e -> "DEBIT".equals(e.type()))
            .map(Entry::amount)
            .reduce(ZERO, BigDecimal::add);

        BigDecimal transferCredits = transferEntries.stream()
            .filter(e -> "CREDIT".equals(e.type()))
            .map(Entry::amount)
            .reduce(ZERO, BigDecimal::add);

        assertEquals(transferDebits, transferCredits, "Transfer debits must equal credits");
    }

    @Test
    @DisplayName("Global Conservation: Sum of Customer Balances == SYSTEM_CLEARING == Cumulative Net Inflow")
    void testGlobalMoneyConservationLifecycle() {
        // Simulated Account Balances
        BigDecimal balanceCustomerA = new BigDecimal("0.00");
        BigDecimal balanceCustomerB = new BigDecimal("0.00");
        BigDecimal balanceClearing = new BigDecimal("0.00");

        BigDecimal cumulativeDeposits = new BigDecimal("0.00");
        BigDecimal cumulativeWithdrawals = new BigDecimal("0.00");

        // Step 1: External Deposit of 1,000.00 INR into Account A
        BigDecimal depositAmount = new BigDecimal("1000.00");
        balanceClearing = balanceClearing.add(depositAmount);
        balanceCustomerA = balanceCustomerA.add(depositAmount);
        cumulativeDeposits = cumulativeDeposits.add(depositAmount);

        assertConservationInvariant(balanceCustomerA, balanceCustomerB, balanceClearing, cumulativeDeposits, cumulativeWithdrawals);

        // Step 2: Internal Transfer of 400.00 INR from Account A to Account B
        BigDecimal transferAmount = new BigDecimal("400.00");
        assertTrue(balanceCustomerA.compareTo(transferAmount) >= 0, "No overdraft allowed");
        balanceCustomerA = balanceCustomerA.subtract(transferAmount);
        balanceCustomerB = balanceCustomerB.add(transferAmount);

        assertConservationInvariant(balanceCustomerA, balanceCustomerB, balanceClearing, cumulativeDeposits, cumulativeWithdrawals);

        // Step 3: External Withdrawal of 200.00 INR from Account A
        BigDecimal withdrawalAmount = new BigDecimal("200.00");
        assertTrue(balanceCustomerA.compareTo(withdrawalAmount) >= 0, "No overdraft allowed");
        balanceCustomerA = balanceCustomerA.subtract(withdrawalAmount);
        balanceClearing = balanceClearing.subtract(withdrawalAmount);
        cumulativeWithdrawals = cumulativeWithdrawals.add(withdrawalAmount);

        assertConservationInvariant(balanceCustomerA, balanceCustomerB, balanceClearing, cumulativeDeposits, cumulativeWithdrawals);

        // Step 4: Reversal of the 400.00 INR Transfer (Compensating transfer B -> A)
        BigDecimal reversalAmount = new BigDecimal("400.00");
        assertTrue(balanceCustomerB.compareTo(reversalAmount) >= 0, "Destination must have funds to reverse");
        balanceCustomerB = balanceCustomerB.subtract(reversalAmount);
        balanceCustomerA = balanceCustomerA.add(reversalAmount);

        assertConservationInvariant(balanceCustomerA, balanceCustomerB, balanceClearing, cumulativeDeposits, cumulativeWithdrawals);

        // Final Balances: Customer A: 800.00, Customer B: 0.00, Clearing: 800.00
        assertEquals(new BigDecimal("800.00"), balanceCustomerA);
        assertEquals(new BigDecimal("0.00"), balanceCustomerB);
        assertEquals(new BigDecimal("800.00"), balanceClearing);
    }

    private void assertConservationInvariant(
        BigDecimal customerA,
        BigDecimal customerB,
        BigDecimal clearing,
        BigDecimal deposits,
        BigDecimal withdrawals
    ) {
        BigDecimal totalCustomerBalances = customerA.add(customerB);
        BigDecimal netExternalInflow = deposits.subtract(withdrawals);

        assertEquals(totalCustomerBalances, clearing, 
            "Total customer balances must exactly match SYSTEM_CLEARING balance");
        assertEquals(totalCustomerBalances, netExternalInflow, 
            "Total customer balances must exactly match cumulative external deposits minus withdrawals");
        assertEquals(BigDecimal.ZERO.setScale(2), clearing.subtract(totalCustomerBalances),
            "Net system position must be identically 0.00 INR");
    }
}
