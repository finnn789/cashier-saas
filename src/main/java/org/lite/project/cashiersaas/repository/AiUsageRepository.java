package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/** Per-tenant monthly AI usage (ai_usage_periods). Every write is one atomic statement, safe without the tenant lock. */
@Repository
@RequiredArgsConstructor
public class AiUsageRepository {
    /** Questions included per month in Premium; top-ups add to bonus_questions. */
    public static final int MONTHLY_QUESTIONS = 300;
    public static final int DAILY_QUESTION_CAP = 40;

    /** The day counter restarts when the outlet-local date changes. */
    private static final String EFFECTIVE_DAY_QUESTIONS =
            "(CASE WHEN ai_usage_periods.day_date = :today THEN ai_usage_periods.day_questions ELSE 0 END)";

    private final EntityManager entityManager;

    /** Counts one question when both the monthly and the daily limit still allow it; returns the resulting state either way. */
    @Transactional
    public Quota consumeQuestion(UUID tenantId, LocalDate periodMonth, LocalDate today) {
        Object updated = entityManager.createNativeQuery("INSERT INTO ai_usage_periods "
                        + "(tenant_id, period_month, questions_used, bonus_questions, day_date, day_questions, input_tokens, output_tokens, updated_at) "
                        + "VALUES (:tenantId, :periodMonth, 1, 0, :today, 1, 0, 0, now()) "
                        + "ON CONFLICT (tenant_id, period_month) DO UPDATE SET "
                        + "questions_used = ai_usage_periods.questions_used + 1, "
                        + "day_questions = " + EFFECTIVE_DAY_QUESTIONS + " + 1, day_date = :today, updated_at = now() "
                        + "WHERE ai_usage_periods.questions_used < :monthly + ai_usage_periods.bonus_questions "
                        + "AND " + EFFECTIVE_DAY_QUESTIONS + " < :dailyCap "
                        + "RETURNING questions_used, bonus_questions")
                .setParameter("tenantId", tenantId).setParameter("periodMonth", periodMonth).setParameter("today", today)
                .setParameter("monthly", MONTHLY_QUESTIONS).setParameter("dailyCap", DAILY_QUESTION_CAP)
                .getResultStream().findFirst().orElse(null);
        if (updated != null) {
            Object[] row = (Object[]) updated;
            return new Quota(true, ((Number) row[0]).intValue(), ((Number) row[1]).intValue(), false);
        }
        // The WHERE refused the update (the row exists): read it to tell which limit was hit.
        Object[] row = (Object[]) entityManager.createNativeQuery("SELECT questions_used, bonus_questions FROM ai_usage_periods "
                        + "WHERE tenant_id = :tenantId AND period_month = :periodMonth")
                .setParameter("tenantId", tenantId).setParameter("periodMonth", periodMonth).getSingleResult();
        int used = ((Number) row[0]).intValue();
        int bonus = ((Number) row[1]).intValue();
        return new Quota(false, used, bonus, used < MONTHLY_QUESTIONS + bonus);
    }

    /** Adds the tokens of one answered question to the month row, creating it when absent. */
    @Transactional
    public void addTokens(UUID tenantId, LocalDate periodMonth, LocalDate today, long inputTokens, long outputTokens) {
        entityManager.createNativeQuery("INSERT INTO ai_usage_periods "
                        + "(tenant_id, period_month, questions_used, bonus_questions, day_date, day_questions, input_tokens, output_tokens, updated_at) "
                        + "VALUES (:tenantId, :periodMonth, 0, 0, :today, 0, :input, :output, now()) "
                        + "ON CONFLICT (tenant_id, period_month) DO UPDATE SET "
                        + "input_tokens = ai_usage_periods.input_tokens + :input, "
                        + "output_tokens = ai_usage_periods.output_tokens + :output, updated_at = now()")
                .setParameter("tenantId", tenantId).setParameter("periodMonth", periodMonth).setParameter("today", today)
                .setParameter("input", inputTokens).setParameter("output", outputTokens)
                .executeUpdate();
    }

    @Getter
    @RequiredArgsConstructor
    public static class Quota {
        private final boolean allowed;
        private final int used;
        private final int bonus;
        /** Only meaningful when not allowed: true when the monthly limit still had room, so the daily cap refused it. */
        private final boolean dailyCapReached;
    }
}
