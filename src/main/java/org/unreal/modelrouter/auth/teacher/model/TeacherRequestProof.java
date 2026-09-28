package org.unreal.modelrouter.auth.teacher.model;

/**
 * 教师模型请求中由网关自行提取的设备证明。
 *
 * @param certificateFingerprint mTLS 客户端证书 SHA-256 指纹（Base64URL）
 * @param endpointId 请求 URL 中的教师专属入口 ID
 */
public record TeacherRequestProof(String certificateFingerprint, String endpointId) {
}
