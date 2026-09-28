# Kubernetes cluster deployment

This directory contains a production-oriented baseline for the standalone gateway.
PostgreSQL and Redis are deliberately external dependencies; use managed or independently
operated high-availability services in production.

## Prerequisites

1. Build and push `Dockerfile.cluster` with an immutable tag.
2. Configure `external-secret.example.yaml` for the campus vault, or generate an untracked
   `secret.yaml` from `secret.example.yaml`.
3. Update the image and public host in `ai-gateway.yaml`.
4. Ensure the namespace can reach PostgreSQL, Redis, the school CAS service and model endpoints.

## First deployment

The pre-Flyway 2.6.11 schema is treated as baseline V8. Bootstrap a brand-new empty database
once with the single-pod Hibernate job; normal replicas then baseline it, execute V9+, and run
Hibernate strictly in validation mode:

```bash
kubectl apply -f namespace.yaml
kubectl apply -f secret.yaml
kubectl apply -f database-bootstrap-job.yaml
kubectl -n ai-gateway wait --for=condition=complete job/ai-gateway-database-bootstrap --timeout=10m
kubectl apply -f ai-gateway.yaml
```

For an existing database, skip the bootstrap Job. Take and restore-test a backup, review V9,
then start one canary replica so Flyway baselines the existing schema at V8 and applies V9.
Never run a normal replica with `ddl-auto=update`.

After installing Prometheus Operator and metrics-server, apply the production add-ons:

```bash
kubectl apply -f production-addons.yaml
```

The add-on file contains the HPA, ServiceMonitor and PrometheusRule. Adjust its Prometheus
release label to match the campus cluster. Review `network-policy.example.yaml` separately:
the allowed namespaces and kubelet probe path depend on the cluster CNI, so it must not be
applied unchanged.

## Verify

```bash
kubectl -n ai-gateway get pods
kubectl -n ai-gateway rollout status deployment/ai-gateway
kubectl -n ai-gateway logs deployment/ai-gateway-worker --tail=200
kubectl -n ai-gateway exec deployment/ai-gateway -- \
  sh -c 'wget -qO- http://localhost:8080/actuator/info'
```

Only API Pods are selected by the Service. The worker runs scheduled jobs but receives no
user traffic. API Pods poll the shared configuration revision independently of business
scheduling, so configuration refresh remains active when `SCHEDULING_ENABLED=false`.

See `docs/zh/deployment/production-readiness.md` for CAS typeCode mappings, monthly quota provisioning,
HA connection settings, backup requirements, load testing and the release gate.

## CAS activation checklist

CAS is intentionally disabled in the checked-in manifest. Before setting `CAMPUS_AUTH_ENABLED=true`:

1. Register the exact callback `https://<public-host>/api/auth/cas/callback` with the school.
2. Configure the real teacher/student `typeCode` values through an external config file or Spring map environment variables; do not guess them.
3. Store `CAMPUS_ADMIN_ACCOUNTS` in the cluster secret and keep it empty in source control.
4. Keep `CAMPUS_SESSION_STORE_TYPE=redis` and verify that two different API Pods can restore the same login session.
5. Enable `TEACHER_ACCESS_ENABLED` only after the CAS teacher mapping is active. Choose `TEACHER_REQUIRE_CLIENT_CERTIFICATE=true` for managed mTLS devices or `false` for device-ID binding without certificate claims.
