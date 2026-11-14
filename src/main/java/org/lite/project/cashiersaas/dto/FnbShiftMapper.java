package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbCashExpenseResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftMethodTotalResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftResponse;
import org.lite.project.cashiersaas.entity.fnb.CashExpenseEntity;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.repository.FnbShiftRepository;

import java.math.BigDecimal;
import java.util.List;

public final class FnbShiftMapper {
    private FnbShiftMapper() { }

    /** expectedCash is the live value while the shift is open and the stored value once closed (decided by the caller). */
    public static FnbShiftResponse shift(CashShiftEntity shift, FnbShiftRepository.CashSummary summary,
                                         BigDecimal expectedCash, int openOrderCount,
                                         BigDecimal expensesTotal, List<CashExpenseEntity> expenses) {
        FnbShiftResponse response = new FnbShiftResponse();
        response.setId(shift.getId());
        response.setStatus(shift.getStatus());
        response.setUserId(shift.getMembership().getUser().getId());
        response.setFullName(shift.getMembership().getUser().getFullName());
        response.setOpeningCash(shift.getOpeningCash());
        response.setOpenedAt(shift.getOpenedAt());
        response.setClosedAt(shift.getClosedAt());
        response.setExpectedCash(expectedCash);
        response.setCountedCash(shift.getCountedCash());
        response.setCashDifference(shift.getCashDifference());
        response.setClosingNote(shift.getClosingNote());
        response.setPaymentCount(summary.getPaymentCount());
        response.setTotalsByMethod(summary.getTotalsByMethod().stream().map(FnbShiftMapper::methodTotal).toList());
        response.setCashIn(summary.getCashIn());
        response.setCashRefunds(summary.getCashRefunds());
        response.setOpenOrderCount(openOrderCount);
        response.setExpensesTotal(expensesTotal);
        if (expenses != null) response.setExpenses(expenses.stream().map(FnbShiftMapper::expense).toList());
        return response;
    }

    public static FnbCashExpenseResponse expense(CashExpenseEntity expense) {
        FnbCashExpenseResponse response = new FnbCashExpenseResponse();
        response.setId(expense.getId());
        response.setAmount(expense.getAmount());
        response.setDescription(expense.getDescription());
        response.setRecordedAt(expense.getRecordedAt());
        response.setRecordedBy(expense.getRecordedBy().getFullName());
        return response;
    }

    private static FnbShiftMethodTotalResponse methodTotal(FnbShiftRepository.MethodTotal total) {
        FnbShiftMethodTotalResponse response = new FnbShiftMethodTotalResponse();
        response.setMethod(total.getMethod());
        response.setMethodLabel(total.getMethodLabel());
        response.setCount(total.getCount());
        response.setAmount(total.getAmount());
        return response;
    }
}
