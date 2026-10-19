package org.unreal.modelrouter.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Live department defaults and membership-scoped individual exceptions. */
@Service
public class UserUsagePolicyService {
    private final JdbcTemplate jdbc;
    public UserUsagePolicyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void membershipChanged(long owner) {
        jdbc.update("WITH retired AS (DELETE FROM campus_user_usage_policy p USING campus_current_member m "
                + "WHERE p.owner_id=? AND m.owner_id=p.owner_id AND (p.department_id IS DISTINCT FROM m.department_id OR p.user_id IS DISTINCT FROM m.user_id) RETURNING p.*) "
                + "INSERT INTO campus_user_policy_history(owner_id,previous_policy,event_type) SELECT owner_id,to_jsonb(retired),'DEPARTMENT_CHANGED' FROM retired",owner);
    }

    public boolean isFreeUsage(String userId) {
        if (userId == null || userId.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM campus_current_member m "
                        + "LEFT JOIN campus_user_usage_policy p ON p.owner_id=m.owner_id AND p.user_id=m.user_id AND p.department_id=m.department_id "
                        + "LEFT JOIN campus_department_policy d ON d.department_id=m.department_id "
                        + "WHERE m.user_id=? AND COALESCE(p.free_usage,d.free_usage,false))", Boolean.class, userId));
    }
}
