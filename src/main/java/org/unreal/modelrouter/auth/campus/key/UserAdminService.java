package org.unreal.modelrouter.auth.campus.key;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@lombok.extern.slf4j.Slf4j
public class UserAdminService {
    private final JdbcTemplate jdbc;
    public UserAdminService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Department codes are authoritative; names are a fallback only when a code is absent.
    private static final String DEPARTMENT_KEY = "CASE WHEN NULLIF(BTRIM(c.department_code), '') IS NOT NULL "
            + "THEN 'code:' || BTRIM(c.department_code) WHEN NULLIF(BTRIM(c.department_name), '') IS NOT NULL "
            + "THEN 'name:' || BTRIM(c.department_name) ELSE 'unassigned' END";
    // One current identity per billing user, even when legacy/other-provider bindings coexist.
    private static final String CAMPUS_JOIN = " LEFT JOIN LATERAL (SELECT b.* FROM campus_identity_binding b "
            + "WHERE b.system_user_id = u.id ORDER BY b.enabled DESC NULLS LAST, "
            + "b.updated_at DESC NULLS LAST, b.created_at DESC NULLS LAST, b.id LIMIT 1) c ON true ";

    private static String filter(String search) {
        return "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public Map<String, Object> departments(String search, int page, int size) {
        String grouped = " FROM (SELECT " + DEPARTMENT_KEY + " AS id, "
                + "COALESCE(MAX(NULLIF(BTRIM(c.department_name), '')), MAX(NULLIF(BTRIM(c.department_code), '')), '未分配部门') AS name, "
                + "COALESCE(MAX(NULLIF(BTRIM(c.department_code), '')), '') AS code, COUNT(*) AS \"userCount\", "
                + "COUNT(*) FILTER (WHERE COALESCE(p.free_usage, d.free_usage, false)) AS \"freeUserCount\" "
                + "FROM sldd_system_users u" + CAMPUS_JOIN
                + "LEFT JOIN campus_user_usage_policy p ON p.owner_id = u.id AND p.user_id = u.user_id AND p.department_id=("+DEPARTMENT_KEY+") "
                + "LEFT JOIN campus_department_policy d ON d.department_id=("+DEPARTMENT_KEY+") "
                + "GROUP BY " + DEPARTMENT_KEY + ") departments WHERE name ILIKE ? OR code ILIKE ?";
        String term = filter(search);
        var rows = jdbc.queryForList("SELECT *" + grouped + " ORDER BY (id = 'unassigned'), name, id LIMIT ? OFFSET ?",
                term, term, size, page * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + grouped, Long.class, term, term);
        return Map.of("records", rows, "total", total == null ? 0 : total, "page", page, "size", size);
    }

    public Map<String, Object> list(String search, int page, int size) {
        return list(search, null, page, size);
    }

    public Map<String, Object> list(String search, String department, int page, int size) {
        return list(search,department,page,size,null);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> detail(long owner) {
        var result=(List<Map<String,Object>>)list("",null,0,1,owner).get("records");
        if(result.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        return result.get(0);
    }
    private Map<String, Object> list(String search, String department, int page, int size,Long owner) {
        String term = filter(search);
        String from = " FROM sldd_system_users u" + CAMPUS_JOIN
                + "LEFT JOIN campus_user_usage_policy p ON p.owner_id = u.id AND p.user_id = u.user_id AND p.department_id=("+DEPARTMENT_KEY+") "
                + "LEFT JOIN campus_department_policy d ON d.department_id=("+DEPARTMENT_KEY+") "
                + "LEFT JOIN ai_user_free_quota q ON q.user_id = u.user_id AND q.deleted = false "
                + "LEFT JOIN ai_account_balance b ON b.account_id = CASE WHEN u.user_type = 1 THEN u.company_id ELSE u.user_id END "
                + "AND b.account_type = CASE WHEN u.user_type = 1 THEN 1 ELSE 2 END AND b.deleted = false ";
        String where = " WHERE (COALESCE(c.display_name, u.username, '') ILIKE ? "
                + "OR COALESCE(c.user_account, u.user_id, '') ILIKE ? OR COALESCE(u.mobile, '') ILIKE ?)";
        var args = new java.util.ArrayList<Object>(List.of(term, term, term));
        if (department != null) { where += " AND (" + DEPARTMENT_KEY + ") = ?"; args.add(department); }
        if (owner != null) { where += " AND u.id=?"; args.add(owner); }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        args.add(size); args.add(page * size);
        var rows = jdbc.queryForList("SELECT u.id AS \"ownerId\", u.user_id AS \"platformUserId\", "
                + "COALESCE(c.user_account, u.user_id) AS account, COALESCE(c.display_name, u.username) AS \"displayName\", "
                + "u.mobile, u.user_type AS \"userType\", u.verify_status AS \"verifyStatus\", u.company_id AS \"companyId\", "
                + "c.account AS \"campusAccount\", (c.system_user_id IS NOT NULL) AS \"campusBound\", c.local_account AS \"localAccount\", c.staff_no AS \"staffNo\", "
                + "c.department_code AS \"departmentCode\", c.department_name AS department, "
                + "c.type_code AS \"typeCode\", c.type_name AS \"typeName\", c.roles, c.enabled, "
                + "c.created_at AS \"createdAt\", c.updated_at AS \"updatedAt\", "
                + "COALESCE(p.free_usage, d.free_usage, false) AS \"freeUsage\", COALESCE(p.reason,d.reason) AS \"freeUsageReason\", "
                + "COALESCE(p.updated_by,d.updated_by) AS \"policyUpdatedBy\", COALESCE(p.updated_at,d.updated_at) AS \"policyUpdatedAt\", "
                + "COALESCE(b.balance, 0) AS balance, (u.user_type = 1) AS shared, "
                + "q.total_quota AS \"totalQuota\", q.used_quota AS \"usedQuota\", q.remaining_quota AS \"remainingQuota\", "
                + "q.period_end AS \"periodEnd\"" + from + where + " ORDER BY u.id DESC LIMIT ? OFFSET ?", args.toArray());
        return Map.of("records", rows, "total", total == null ? 0 : total, "page", page, "size", size);
    }

    @Transactional
    public void setFreeUsage(long ownerId, Boolean freeUsage, String reason, String operator) {
        if (freeUsage == null || (reason != null && reason.length() > 500))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择使用方式，说明最多 500 字");
        reason = reason == null ? "" : reason.trim();
        var users = jdbc.queryForList("SELECT user_id FROM sldd_system_users WHERE id = ? FOR UPDATE", ownerId);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        String userId = (String) users.get(0).get("user_id");
        if (userId == null || userId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户未关联计费身份");
        jdbc.update("INSERT INTO campus_user_usage_policy(owner_id, user_id, free_usage, reason, updated_by,department_id) VALUES (?, ?, ?, ?, ?, (SELECT department_id FROM campus_current_member WHERE owner_id=?)) "
                + "ON CONFLICT (owner_id) DO UPDATE SET user_id = EXCLUDED.user_id, free_usage = EXCLUDED.free_usage, "
                + "funding_source=CASE WHEN campus_user_usage_policy.department_id IS DISTINCT FROM EXCLUDED.department_id THEN NULL ELSE campus_user_usage_policy.funding_source END, "
                + "monthly_cost=CASE WHEN campus_user_usage_policy.department_id IS DISTINCT FROM EXCLUDED.department_id THEN NULL ELSE campus_user_usage_policy.monthly_cost END, "
                + "monthly_tokens=CASE WHEN campus_user_usage_policy.department_id IS DISTINCT FROM EXCLUDED.department_id THEN NULL ELSE campus_user_usage_policy.monthly_tokens END, "
                + "department_id=EXCLUDED.department_id, reason = EXCLUDED.reason, updated_by = EXCLUDED.updated_by, updated_at = CURRENT_TIMESTAMP",
                ownerId, userId, freeUsage, reason.trim(), operator,ownerId);
        jdbc.update("INSERT INTO campus_security_event(owner_id, event_type, description) VALUES (?, 'USAGE_POLICY_CHANGED', ?)",
                ownerId, "管理员 " + operator + " 设置为" + (freeUsage ? "免费使用" : "正常计费") + "：" + reason.trim());
        log.info("管理员修改使用方式: operator={}, ownerId={}, freeUsage={}", operator, ownerId, freeUsage);
    }

    @Transactional
    public void allocate(long ownerId, BigDecimal amount, Long monthlyTokens, String operator) {
        if (amount == null || amount.signum() < 0 || amount.scale() > 6 || amount.compareTo(new BigDecimal("100000000")) > 0
                || (monthlyTokens != null && (monthlyTokens < 0 || monthlyTokens > 1_000_000_000_000L))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "额度无效：金额须为非负数且最多六位小数，Token 总额须在 0-1 万亿之间");
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT user_id, user_type, company_id FROM sldd_system_users WHERE id = ? FOR UPDATE", ownerId);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        var user = users.get(0);
        boolean shared = Integer.valueOf(1).equals(user.get("user_type"));
        String account = (String) user.get(shared ? "company_id" : "user_id");
        if (account == null || account.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户未关联计费账户");
        int type = shared ? 1 : 2;
        // Serializes allocations to a shared company account across users and replicas.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, type + ":" + account);
        if (amount.signum() > 0) {
            int changed = jdbc.update("UPDATE ai_account_balance SET balance = COALESCE(balance, 0) + ?, "
                    + "updater = ?, update_time = CURRENT_TIMESTAMP WHERE account_id = ? AND account_type = ? AND deleted = false",
                    amount, operator, account, type);
            if (changed == 0) jdbc.update("INSERT INTO ai_account_balance "
                    + "(account_id, account_type, balance, warn_enabled, creator, updater, create_time, update_time, deleted) "
                    + "VALUES (?, ?, ?, false, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false)", account, type, amount, operator, operator);
        }
        if (monthlyTokens != null) {
            var start = LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).atStartOfDay();
            jdbc.update("UPDATE ai_user_free_quota SET used_quota = 0, remaining_quota = total_quota, "
                    + "period_start = ?, period_end = ?, trial_exhausted = false, exhausted_at = NULL, "
                    + "version = version + 1 WHERE user_id = ? AND (period_end IS NULL OR period_end <= ?)",
                    start, start.plusMonths(1), user.get("user_id"), start);
            jdbc.update("INSERT INTO ai_user_free_quota (user_id, total_quota, used_quota, remaining_quota, quota_tier, "
                    + "period_start, period_end, trial_exhausted, create_time, update_time, creator, updater, deleted, version) "
                    + "VALUES (?, ?, 0, ?, 'ADMIN', ?, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, false, 0) "
                    + "ON CONFLICT (user_id) DO UPDATE SET total_quota = EXCLUDED.total_quota, quota_tier = 'ADMIN', "
                    + "remaining_quota = GREATEST(EXCLUDED.total_quota - ai_user_free_quota.used_quota, 0), "
                    + "trial_exhausted = ai_user_free_quota.used_quota >= EXCLUDED.total_quota, "
                    + "exhausted_at = CASE WHEN ai_user_free_quota.used_quota >= EXCLUDED.total_quota THEN CURRENT_TIMESTAMP ELSE NULL END, "
                    + "updater = EXCLUDED.updater, update_time = CURRENT_TIMESTAMP, version = ai_user_free_quota.version + 1, deleted = false",
                    user.get("user_id"), monthlyTokens, monthlyTokens, start, start.plusMonths(1), operator, operator);
        }
        log.info("管理员分配额度: operator={}, ownerId={}, accountId={}, accountType={}, amount={}, monthlyTokens={}",
                operator, ownerId, account, type, amount, monthlyTokens);
    }
}
