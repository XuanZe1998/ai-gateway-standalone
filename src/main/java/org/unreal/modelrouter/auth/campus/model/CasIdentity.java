package org.unreal.modelrouter.auth.campus.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** CAS serviceValidate 成功响应中允许使用的校园身份字段。 */
public record CasIdentity(String subject, Map<String, String> attributes) {
    public CasIdentity {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public String attribute(final String name) {
        return attributes.get(name);
    }
}
