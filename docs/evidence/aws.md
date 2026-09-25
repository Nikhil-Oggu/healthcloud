# Evidence — Live AWS Deployment

> Captured 2026-09-25 against the app deployed on AWS and reached over **HTTPS via CloudFront**.
> The environment was stood up on demand with `terraform apply`, captured, and torn down with
> `terraform destroy` afterward to control cost. All data is synthetic.

## Architecture (recap)

The deploy is Terraform-managed: **CloudFront** (HTTPS) → **Application Load Balancer** → **ECS
Fargate** (one task, backend + frontend nginx, ARM64) → **RDS PostgreSQL** (private, encrypted,
password in Secrets Manager), with **Amazon Cognito** as the OIDC identity provider. This apply was
**7 resources added, 0 destroyed** (the VPC/RDS/ECR were already in place). See
[`../adr/ADR-012-ecs-fargate-target-deployment.md`](../adr/ADR-012-ecs-fargate-target-deployment.md)
and [`../adr/ADR-015-on-demand-full-aws-validation.md`](../adr/ADR-015-on-demand-full-aws-validation.md).

## Live app over HTTPS

The deployed sign-in page, served over HTTPS through CloudFront. The production build exposes **only
"Sign in with Cognito"** — the local `dev-login` bypass is compiled out and disabled on the deploy
(the `demo,cognito` profile), so Cognito is the single path in.

![Deployed HealthCloud sign-in over HTTPS](screenshots/aws/aws-01-landing-https.png)

## Real Amazon Cognito login

Clicking "Sign in with Cognito" redirects (OIDC authorization-code + PKCE) to the **Amazon Cognito
hosted login** — real managed authentication, not an app-rendered form.

![Amazon Cognito hosted login page](screenshots/aws/aws-02-cognito-login.png)

## Authenticated on the cloud

After a successful Cognito login, the session is established on the CloudFront URL and the app resolves
the user's identity: **Alex Admin**, `admin@northcare.example.org`, organization **NorthCare Health**,
role **ORG_ADMIN**. Cognito supplies identity; the **roles and tenant come from the application
database** (never from the token) — the layered security model working end-to-end on AWS.

![Signed in on the live cloud deployment as ORG_ADMIN](screenshots/aws/aws-03-authenticated-cloud.png)

## What this proves

- The app **deploys to AWS** (Terraform, reproducible) and is reachable over **HTTPS** via CloudFront.
- **Real Cognito OIDC** authentication works end-to-end on the deployed URL (authorization-code + PKCE).
- Identity from Cognito, **roles/tenant from the DB** — the same authorization model proven locally,
  running in the cloud.

> **Note on the deployed UI.** These cloud screenshots show an **earlier build** of the frontend (a
> top-nav layout). Per the project's workflow, ECR images are pushed manually rather than from CI, so
> the deployed image lags the current codebase. The **current, polished UI** (sidebar shell, bespoke
> landing, etc.) is captured against the live local build in [ui.md](ui.md); this page's purpose is to
> prove **deployment + real HTTPS authentication**, which it does regardless of the frontend build age.

## Reproduce

```bash
cd infrastructure/terraform
terraform apply     # ~10–15 min (CloudFront is the long pole); prints cloudfront_url
# … capture / demo …
terraform destroy   # return to ~$0 (the S3 state bucket is kept)
```
